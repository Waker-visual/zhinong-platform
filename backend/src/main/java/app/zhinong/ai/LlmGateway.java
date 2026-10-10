package app.zhinong.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.concurrent.Semaphore;
import app.zhinong.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 大模型网关：优先云端 OpenAI 兼容 API（DeepSeek 等），其次本地 Ollama（fallback），
 * 都不可用时返回 empty，由上层回退到规则模板，保证演示永不白屏。
 * 支持 Function Calling：complete(messages, tools) 返回模型文本或 tool_calls，
 * 由上层执行工具后追加消息再调一轮，形成工具循环（Agent）。
 */
@Component
public class LlmGateway {

  /** 模型请求的单个工具调用：id 用于回填 tool 消息，arguments 为 JSON 字符串。 */
  public record ToolCall(String id, String name, String arguments) {}

  /** 一次补全的结果：content 与 toolCalls 至少一个有值。 */
  public record LlmResponse(String content, List<ToolCall> toolCalls) {}

  private final HttpClient http =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  private final ObjectMapper json = new ObjectMapper();

  @Value("${farm.llm.url:}") String url;
  @Value("${farm.llm.api-key:}") String apiKey;
  @Value("${farm.llm.model:}") String model;
  @Value("${farm.llm.fallback-url:}") String fallbackUrl;
  @Value("${farm.llm.fallback-model:}") String fallbackModel;
  @Value("${farm.llm.timeout-seconds:20}") int timeoutSeconds;
  @Value("${farm.llm.file-enabled:true}") boolean fileEnabled;
  private final Semaphore capacity = new Semaphore(3);
  private final ThreadLocal<String> lastIssue = ThreadLocal.withInitial(() -> "NOT_CONFIGURED");
  public String diagnostic() { return lastIssue.get(); }

  /** 可选模型名单（平台管理员维护），独立于当前生效的 model 字段。 */
  private List<String> modelOptions = new ArrayList<>();
  private static final int MAX_MODEL_OPTIONS = 50;
  private static final int MAX_MODEL_ID_LENGTH = 100;
  private static final int MAX_FETCHED_MODELS = 200;

  /** 一次模型列表拉取的结果：models 为脱敏后的 id 列表，diagnostic 为诊断码。 */
  public record FetchModelsResult(List<String> models, String diagnostic) {}

  /**
   * 持久化配置文件：{user.dir}/config/llm.properties，优先级高于环境变量，一次配置永久生效。
   * 包内可见（非 final）仅供单元测试指向临时文件，避免测试写入开发者本机的真实配置。
   */
  Path configPath = Path.of(System.getProperty("user.dir"), "config", "llm.properties");

  @PostConstruct
  void loadFileConfig() {
    if (!fileEnabled || !Files.isRegularFile(configPath)) return;
    try (var in = Files.newInputStream(configPath)) {
      Properties p = new Properties();
      p.load(in);
      if (!blank(p.getProperty("url"))) url = p.getProperty("url").trim();
      if (!blank(p.getProperty("api-key"))) apiKey = p.getProperty("api-key").trim();
      if (!blank(p.getProperty("model"))) model = p.getProperty("model").trim();
      if (!blank(p.getProperty("fallback-url"))) fallbackUrl = p.getProperty("fallback-url").trim();
      if (!blank(p.getProperty("fallback-model"))) fallbackModel = p.getProperty("fallback-model").trim();
      String t = p.getProperty("timeout-seconds");
      if (!blank(t)) {
        try {
          timeoutSeconds = Integer.parseInt(t.trim());
        } catch (NumberFormatException ignored) {
          /* 保留默认 */
        }
      }
      String mo = p.getProperty("model-options");
      if (!blank(mo)) {
        List<String> parsed = new ArrayList<>();
        for (String id : mo.split(",")) {
          String trimmed = id.trim();
          if (!trimmed.isEmpty() && !parsed.contains(trimmed) && parsed.size() < MAX_MODEL_OPTIONS) parsed.add(trimmed);
        }
        modelOptions = parsed;
      }
    } catch (IOException ignored) {
      /* 读不到就沿用环境变量 */
    }
  }

  /**
   * 保存配置：立即生效并持久化到本地文件。
   * 密钥字段留空表示保持当前密钥不变；clearApiKey=true 时显式清除密钥（忽略 newApiKey）。
   */
  public synchronized void saveConfig(String newUrl, String newApiKey, String newModel, boolean clearApiKey) {
    if (!blank(newUrl)) validateEndpoint(newUrl.trim());
    if (!clearApiKey && !blank(newUrl) && !newUrl.trim().equals(url) && blank(newApiKey))
      throw new ApiException(400,"更换模型地址时必须重新提供密钥");
    String nextUrl=blank(newUrl)?url:newUrl.trim();
    String nextKey=clearApiKey?"":(blank(newApiKey)?apiKey:newApiKey.trim());
    String nextModel=blank(newModel)?model:newModel.trim();
    persist(nextUrl, nextKey, nextModel, modelOptions);
    url=nextUrl;apiKey=nextKey;model=nextModel;
  }

  /** 合并新增模型选项（去重、长度与数量限制），用于手动添加或从服务拉取后批量合并。 */
  public synchronized List<String> mergeModelOptions(List<String> ids) {
    List<String> next = new ArrayList<>(modelOptions);
    for (String raw : ids == null ? List.<String>of() : ids) {
      String id = raw == null ? "" : raw.trim();
      if (id.isEmpty()) continue;
      if (id.length() > MAX_MODEL_ID_LENGTH) throw new ApiException(400, "模型名称过长");
      if (next.contains(id)) continue;
      if (next.size() >= MAX_MODEL_OPTIONS) throw new ApiException(400, "模型选项数量已达上限");
      next.add(id);
    }
    persist(url, apiKey, model, next);
    modelOptions = next;
    return List.copyOf(modelOptions);
  }

  /** 移除一个模型选项；当前生效模型必须先切换到其他选项才能移除。 */
  public synchronized List<String> removeModelOption(String rawId) {
    String id = rawId == null ? "" : rawId.trim();
    if (!id.isEmpty() && id.equals(model))
      throw new ApiException(400, "请先选择其他模型，再移除当前使用的模型");
    List<String> next = new ArrayList<>(modelOptions);
    next.remove(id);
    persist(url, apiKey, model, next);
    modelOptions = next;
    return List.copyOf(modelOptions);
  }

  public synchronized List<String> modelOptions() {
    return List.copyOf(modelOptions);
  }

  /**
   * 原子写入持久化文件：{user.dir}/config/llm.properties（或测试指向的临时路径）。
   * 只在写入成功后才由调用方更新内存字段，避免写盘失败和内存状态不一致。
   */
  private void persist(String nextUrl, String nextKey, String nextModel, List<String> nextModelOptions) {
    Properties p = new Properties();
    p.setProperty("url", nextUrl == null ? "" : nextUrl);
    p.setProperty("api-key", nextKey == null ? "" : nextKey);
    p.setProperty("model", nextModel == null ? "" : nextModel);
    p.setProperty("fallback-url", fallbackUrl == null ? "" : fallbackUrl);
    p.setProperty("fallback-model", fallbackModel == null ? "" : fallbackModel);
    p.setProperty("timeout-seconds", String.valueOf(timeoutSeconds));
    p.setProperty("model-options", String.join(",", nextModelOptions));
    try {
      Files.createDirectories(configPath.getParent());
      Path temporary=Files.createTempFile(configPath.getParent(),"llm-", ".private.tmp");
      try {
      try (var out = Files.newOutputStream(temporary)) {
        p.store(out, "Zhinong LLM config (managed by platform admin)");
      }
      Files.move(temporary,configPath,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } finally {Files.deleteIfExists(temporary);}
    } catch (IOException ignored) {
      throw new ApiException(500,"模型配置未能写入私有配置文件");
    }
  }

  public synchronized String url() {
    return url;
  }

  public synchronized String model() {
    return model;
  }

  public synchronized boolean apiKeySet() {
    return !blank(apiKey);
  }

  private static boolean blank(String v) {
    return v == null || v.isBlank();
  }

  public synchronized boolean cloudEnabled() {
    return !blank(url) && !blank(apiKey);
  }

  public synchronized boolean fallbackEnabled() {
    return !blank(fallbackUrl);
  }

  /** 流式文本增量回调；不回传原始工具参数或凭据，只回传安全文本片段。 */
  public interface StreamListener {
    void onDelta(String text);
  }

  /** 流式运行的最终状态：区分“正常走完”“中途被打断（已经输出了部分正文）”“完全没有输出任何正文”。
   * 调用方据此决定行为：COMPLETED 正常展示；UNAVAILABLE（模型未配置、鉴权失败、连接失败、超时等但
   * 从未吐出过文本）等同于同步路径的“模型不可用”，走规则回退；INTERRUPTED（已经吐出过文本后才失败）
   * 绝不能再悄悄拼接规则回退文本——那会让客户端看到的内容和落库内容不一致，必须报错并允许用同一个
   * requestId 重试。diagnostic 放在结果里而不是读取 {@link #diagnostic()}，因为后者是按线程保存的，
   * 流式调用横跨虚拟线程时用结果自带的诊断更不容易出错。 */
  public enum StreamOutcome { COMPLETED, INTERRUPTED, UNAVAILABLE, TOOL_CALLS }

  public record StreamResult(StreamOutcome outcome, String content, String diagnostic, List<ToolCall> toolCalls) {
    public static StreamResult unavailable(String diagnostic) {
      return new StreamResult(StreamOutcome.UNAVAILABLE, null, diagnostic, List.of());
    }

    public static StreamResult interrupted(String content, String diagnostic) {
      return new StreamResult(StreamOutcome.INTERRUPTED, content, diagnostic, List.of());
    }

    public static StreamResult completed(String content) {
      return new StreamResult(StreamOutcome.COMPLETED, content, "OK", List.of());
    }

    public static StreamResult toolCalls(List<ToolCall> calls) {
      return new StreamResult(StreamOutcome.TOOL_CALLS, null, "OK", calls);
    }
  }

  /** Accumulates one tool_calls[].index entry across streamed chunks: id and function.name usually
   * arrive once on the first chunk for that index, function.arguments arrives split across many
   * chunks and must be concatenated (never re-parsed chunk-by-chunk — only the final string is valid JSON). */
  private static final class ToolCallAccumulator {
    String id = "";
    String name = "";
    final StringBuilder arguments = new StringBuilder();
  }

  /**
   * 流式补全：仅使用云端 OpenAI 兼容 SSE（stream:true），文本增量通过 listener 实时回传。
   * 与 {@link #complete} 共用同一并发信号量与超时配置；不支持本地 fallback 端点的流式转发——
   * 云端未配置或流式请求在吐出任何文本之前就失败时返回 UNAVAILABLE，由上层回退到规则答案
   * （与同步路径的“模型不可用”语义一致），避免为很少配置的本地回退再实现一套非流式转单片
   * delta 的特殊路径。cancelled 由上游调用方传入（例如 SSE 客户端已断开），为 true 时尽快停止
   * 读取上游响应流——不仅在两行之间检查，还有一个后台看门狗线程在超时或取消时主动关闭底层连接，
   * 避免卡在一次阻塞的 readLine() 里导致信号量被长期占用。
   */
  public StreamResult stream(
    List<Map<String, Object>> messages,
    List<Map<String, Object>> tools,
    StreamListener listener,
    java.util.function.BooleanSupplier cancelled
  ) {
    if (!capacity.tryAcquire()) {
      return StreamResult.unavailable("BUSY");
    }
    try {
      String endpoint, key, selectedModel;
      synchronized (this) {
        endpoint = url;
        key = apiKey;
        selectedModel = model;
      }
      if (blank(endpoint) || blank(key)) return StreamResult.unavailable("NOT_CONFIGURED");
      return streamCompletions(
        endpoint,
        key,
        blank(selectedModel) ? "deepseek-flash" : selectedModel,
        messages,
        tools,
        listener,
        cancelled
      );
    } finally {
      capacity.release();
    }
  }

  private StreamResult streamCompletions(
    String base,
    String key,
    String m,
    List<Map<String, Object>> messages,
    List<Map<String, Object>> tools,
    StreamListener listener,
    java.util.function.BooleanSupplier cancelled
  ) {
    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(Math.max(1, timeoutSeconds)).toNanos();
    StringBuilder content = new StringBuilder();
    boolean[] sawDelta = { false };
    boolean[] cleanFinish = { false };
    boolean[] sawToolCalls = { false };
    Map<Integer, ToolCallAccumulator> toolCallsByIndex = new java.util.TreeMap<>();
    HttpResponse<java.io.InputStream> res = null;
    java.util.concurrent.atomic.AtomicBoolean finishedReading = new java.util.concurrent.atomic.AtomicBoolean(false);
    Thread watchdog = null;
    try {
      validateEndpoint(base);
      Map<String, Object> body =
        new LinkedHashMap<>(Map.of("model", m, "messages", messages, "temperature", 0.3, "max_tokens", 1600, "stream", true));
      if (!tools.isEmpty()) {
        body.put("tools", tools);
        body.put("tool_choice", "auto");
      }
      if ("api.deepseek.com".equals(URI.create(base).getHost())) body.put("thinking", Map.of("type", "disabled"));
      HttpRequest.Builder b =
        HttpRequest
          .newBuilder()
          .uri(URI.create(base.replaceAll("/+$", "") + "/chat/completions"))
          .timeout(Duration.ofSeconds(timeoutSeconds))
          .header("Content-Type", "application/json")
          .header("Accept", "text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      if (!key.isBlank()) b.header("Authorization", "Bearer " + key);
      res = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
      if (res.statusCode() / 100 != 2) {
        return StreamResult.unavailable(statusDiagnostic(res.statusCode()));
      }
      // 看门狗：readLine() 本身只会在收到下一行时才返回，超时/取消标记只在两行之间被检查；
      // 一次缓慢滴水的响应体会让这次检查永远等不到机会。独立线程按截止时间或取消标记主动关闭
      // 响应流，强制唤醒被阻塞的读取，不让信号量被占用超过预算。
      HttpResponse<java.io.InputStream> finalRes = res;
      watchdog = Thread.ofVirtual().start(() -> {
        try {
          while (!finishedReading.get()) {
            if (cancelled.getAsBoolean() || System.nanoTime() > deadlineNanos) {
              try {
                finalRes.body().close();
              } catch (IOException ignored) {
                /* 已经在结束 */
              }
              return;
            }
            Thread.sleep(200);
          }
        } catch (InterruptedException ignored) {
          /* 正常读取已经结束，看门狗被中断退出 */
        }
      });
      try (
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(res.body(), java.nio.charset.StandardCharsets.UTF_8))
      ) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (cancelled.getAsBoolean()) break;
          if (line.isBlank() || !line.startsWith("data:")) continue;
          String data = line.substring(5).trim();
          if ("[DONE]".equals(data)) {
            cleanFinish[0] = true;
            break;
          }
          JsonNode root;
          try {
            root = json.readTree(data);
          } catch (Exception parseError) {
            continue; // 忽略无法解析的分片，不中断整个流
          }
          JsonNode choice = root.path("choices").path(0);
          JsonNode delta = choice.path("delta");
          if (delta.path("content").isTextual()) {
            String text = delta.path("content").asText();
            if (!text.isEmpty()) {
              content.append(text);
              sawDelta[0] = true;
              listener.onDelta(text);
            }
          }
          JsonNode deltaToolCalls = delta.path("tool_calls");
          if (deltaToolCalls.isArray()) {
            for (JsonNode tc : deltaToolCalls) {
              int index = tc.path("index").asInt(0);
              ToolCallAccumulator acc = toolCallsByIndex.computeIfAbsent(index, i -> new ToolCallAccumulator());
              if (tc.path("id").isTextual() && !tc.path("id").asText().isEmpty()) acc.id = tc.path("id").asText();
              JsonNode fn = tc.path("function");
              if (fn.path("name").isTextual() && !fn.path("name").asText().isEmpty()) acc.name = fn.path("name").asText();
              if (fn.path("arguments").isTextual()) acc.arguments.append(fn.path("arguments").asText());
            }
            sawToolCalls[0] = true;
          }
          // 并非所有 OpenAI 兼容实现都会再发 [DONE]；finish_reason 本身就是这个选择已经结束的信号。
          if (choice.path("finish_reason").isTextual()) cleanFinish[0] = true;
        }
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      String diagnostic;
      if (cancelled.getAsBoolean()) diagnostic = "CANCELLED";
      else if (System.nanoTime() > deadlineNanos || e instanceof java.net.http.HttpTimeoutException) diagnostic = "TIMEOUT";
      else diagnostic = "UNAVAILABLE";
      return sawDelta[0] ? StreamResult.interrupted(content.toString(), diagnostic) : StreamResult.unavailable(diagnostic);
    } finally {
      finishedReading.set(true);
      if (watchdog != null) watchdog.interrupt();
      if (res != null) {
        try {
          res.body().close();
        } catch (Exception ignored) {
          /* 已经结束读取，或者已经被看门狗关闭 */
        }
      }
    }
    if (cancelled.getAsBoolean()) {
      return sawDelta[0] ? StreamResult.interrupted(content.toString(), "CANCELLED") : StreamResult.unavailable("CANCELLED");
    }
    if (cleanFinish[0]) {
      if (sawToolCalls[0] && !toolCallsByIndex.isEmpty()) {
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCallsByIndex.values()) {
          if (acc.name.isEmpty()) continue; // 没有拿到函数名的分片无法执行，直接丢弃
          calls.add(new ToolCall(acc.id, acc.name, acc.arguments.length() == 0 ? "{}" : acc.arguments.toString()));
        }
        if (!calls.isEmpty()) return StreamResult.toolCalls(calls);
      }
      return content.length() == 0
        ? StreamResult.unavailable("EMPTY_RESPONSE")
        : StreamResult.completed(content.toString().trim());
    }
    // 循环正常退出（EOF）但既没看到 [DONE]/finish_reason，也没被取消：连接提前断开。
    String diagnostic = System.nanoTime() > deadlineNanos ? "TIMEOUT" : "SERVICE_ERROR";
    return sawDelta[0] ? StreamResult.interrupted(content.toString(), diagnostic) : StreamResult.unavailable(diagnostic);
  }

  private static String statusDiagnostic(int statusCode) {
    return switch (statusCode) {
      case 401, 403 -> "AUTH_FAILED";
      case 402 -> "BALANCE_REQUIRED";
      case 429 -> "RATE_LIMITED";
      default -> "SERVICE_ERROR";
    };
  }

  /** 简单问答：无工具，保留给 insights 等调用。 */
  public Optional<String> chat(String system, String user) {
    return complete(
        List.of(
          Map.of("role", "system", "content", system),
          Map.of("role", "user", "content", user)
        ),
        List.of()
      )
      .filter(r -> r.content() != null)
      .map(LlmResponse::content);
  }

  /**
   * 补全调用：支持 tools（Function Calling）。返回文本或工具调用；
   * 云端失败自动试本地 fallback，都失败返回 empty。
   */
  public Optional<LlmResponse> complete(
    List<Map<String, Object>> messages,
    List<Map<String, Object>> tools
  ) {
    lastIssue.set("NOT_CONFIGURED");
    long started=System.nanoTime();
    if (!capacity.tryAcquire()) { lastIssue.set("BUSY"); return Optional.empty(); }
    try {
    String endpoint,key,selectedModel,backup,backupModel;
    synchronized(this) { endpoint=url;key=apiKey;selectedModel=model;backup=fallbackUrl;backupModel=fallbackModel; }
    if (!blank(endpoint) && !blank(key)) {
      Optional<LlmResponse> r =
        callCompletions(endpoint, key, blank(selectedModel) ? "deepseek-flash" : selectedModel, messages, tools,45);
      if (r.isPresent()) return r;
    }
    int remainingSeconds=45-(int)Duration.ofNanos(System.nanoTime()-started).toSeconds();
    if (!blank(backup) && remainingSeconds>=5) {
      return callCompletions(
        backup,
        "",
        blank(backupModel) ? "qwen2.5" : backupModel,
        messages,
        tools,
        remainingSeconds
      );
    }
    return Optional.empty();
    } finally { capacity.release(); }
  }

  /**
   * 测试连接：对保存的配置（或传入的待保存配置覆盖）发起一次最小请求，只返回诊断码，
   * 从不回显密钥或上游响应正文。不持久化、不影响当前生效配置。
   */
  public String test(String testUrl, String testApiKey, String testModel) {
    String u, k, m;
    synchronized (this) {
      u = blank(testUrl) ? url : testUrl.trim();
      k = blank(testApiKey) ? apiKey : testApiKey.trim();
      m = blank(testModel) ? model : testModel.trim();
    }
    if (blank(u) || blank(k)) return "NOT_CONFIGURED";
    try {
      validateEndpoint(u);
    } catch (ApiException e) {
      return "SERVICE_ERROR";
    }
    if (!capacity.tryAcquire()) return "BUSY";
    try {
      List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "ping"));
      callCompletions(u, k, blank(m) ? "deepseek-chat" : m, messages, List.of(), 15);
      return lastIssue.get();
    } finally {
      capacity.release();
    }
  }

  /**
   * 拉取 OpenAI 兼容服务的可用模型列表（GET {baseUrl}/models），用保存的或表单待提交的配置覆盖。
   * 返回脱敏结果：只有 id 列表与诊断码，从不回显密钥或上游响应正文。
   */
  public FetchModelsResult fetchModels(String testUrl, String testApiKey) {
    String u, k;
    synchronized (this) {
      u = blank(testUrl) ? url : testUrl.trim();
      k = blank(testApiKey) ? apiKey : testApiKey.trim();
    }
    if (blank(u) || blank(k)) return new FetchModelsResult(List.of(), "NOT_CONFIGURED");
    try {
      validateEndpoint(u);
    } catch (ApiException e) {
      return new FetchModelsResult(List.of(), "SERVICE_ERROR");
    }
    if (!capacity.tryAcquire()) return new FetchModelsResult(List.of(), "BUSY");
    try {
      HttpRequest req = HttpRequest
        .newBuilder()
        .uri(URI.create(u.replaceAll("/+$", "") + "/models"))
        .timeout(Duration.ofSeconds(Math.max(5, Math.min(15, timeoutSeconds))))
        .header("Authorization", "Bearer " + k)
        .GET()
        .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) {
        String code = switch (res.statusCode()) {
          case 401, 403 -> "AUTH_FAILED";
          case 402 -> "BALANCE_REQUIRED";
          case 429 -> "RATE_LIMITED";
          default -> "SERVICE_ERROR";
        };
        return new FetchModelsResult(List.of(), code);
      }
      JsonNode root = json.readTree(res.body());
      List<String> ids = new ArrayList<>();
      for (JsonNode m : root.path("data")) {
        String id = m.path("id").asText("");
        if (!id.isBlank() && id.length() <= MAX_MODEL_ID_LENGTH && !ids.contains(id)) ids.add(id);
      }
      List<String> sorted = ids.stream().sorted().limit(MAX_FETCHED_MODELS).toList();
      return new FetchModelsResult(sorted, "OK");
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      String code = e instanceof java.net.http.HttpTimeoutException ? "TIMEOUT" : "UNAVAILABLE";
      return new FetchModelsResult(List.of(), code);
    } finally {
      capacity.release();
    }
  }

  private void validateEndpoint(String base) {
    try {
      URI u=URI.create(base);
      boolean loopback=List.of("localhost","127.0.0.1","[::1]").contains(u.getHost());
      if (u.getHost()==null || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null
          || !("https".equals(u.getScheme()) || (loopback && "http".equals(u.getScheme())))) throw new IllegalArgumentException();
    } catch (Exception ex) { throw new ApiException(400,"模型地址必须是 HTTPS，或本机 HTTP 地址"); }
  }

  /** 会话标题：一次不带工具的短请求，max_tokens 很小、超时 8 秒；只走云端，不试本地回退。 */
  public Optional<String> shortTitle(List<Map<String, Object>> messages) {
    if (!capacity.tryAcquire()) return Optional.empty();
    try {
      String endpoint, key, selectedModel;
      synchronized (this) { endpoint = url; key = apiKey; selectedModel = model; }
      if (blank(endpoint) || blank(key)) return Optional.empty();
      return callCompletions(endpoint, key, blank(selectedModel) ? "deepseek-flash" : selectedModel, messages, List.of(), 8, 32)
        .map(LlmResponse::content);
    } finally { capacity.release(); }
  }

  private Optional<LlmResponse> callCompletions(
    String base,
    String key,
    String m,
    List<Map<String, Object>> messages,
    List<Map<String, Object>> tools,
    int secondsBudget
  ) {
    return callCompletions(base, key, m, messages, tools, secondsBudget, 1600);
  }

  private Optional<LlmResponse> callCompletions(
    String base,
    String key,
    String m,
    List<Map<String, Object>> messages,
    List<Map<String, Object>> tools,
    int secondsBudget,
    int maxTokens
  ) {
    try {
      validateEndpoint(base);
      Map<String, Object> body =
        new LinkedHashMap<>(Map.of(
          "model",
          m,
          "messages",
          messages,
          "temperature",
          0.3,
          "max_tokens",
          maxTokens
        ));
      if (!tools.isEmpty()) { body.put("tools",tools); body.put("tool_choice","auto"); }
      if ("api.deepseek.com".equals(URI.create(base).getHost())) body.put("thinking",Map.of("type","disabled"));
      HttpRequest.Builder b =
        HttpRequest
          .newBuilder()
          .uri(URI.create(base.replaceAll("/+$", "") + "/chat/completions"))
          .timeout(Duration.ofSeconds(Math.max(5,Math.min(secondsBudget,timeoutSeconds))))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      if (!key.isBlank()) b.header("Authorization", "Bearer " + key);
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) {
        lastIssue.set(switch(res.statusCode()) { case 401,403 -> "AUTH_FAILED"; case 402 -> "BALANCE_REQUIRED"; case 429 -> "RATE_LIMITED"; default -> "SERVICE_ERROR"; });
        return Optional.empty();
      }
      lastIssue.set("OK");
      JsonNode root = json.readTree(res.body());
      JsonNode msg = root.path("choices").path(0).path("message");
      String content = msg.path("content").isNull() ? null : msg.path("content").asText();
      List<ToolCall> calls = new ArrayList<>();
      for (JsonNode tc : msg.path("tool_calls")) {
        calls.add(
          new ToolCall(
            tc.path("id").asText(""),
            tc.path("function").path("name").asText(""),
            tc.path("function").path("arguments").asText("{}")
          )
        );
      }
      if (!calls.isEmpty()) return Optional.of(new LlmResponse(content, calls));
      if (content != null && !content.isBlank()) {
        return Optional.of(new LlmResponse(content.trim(), calls));
      }
      lastIssue.set("EMPTY_RESPONSE"); return Optional.empty();
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      lastIssue.set(e instanceof java.net.http.HttpTimeoutException ? "TIMEOUT" : "UNAVAILABLE");
      return Optional.empty();
    }
  }
}
