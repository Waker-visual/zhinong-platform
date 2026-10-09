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

  private Optional<LlmResponse> callCompletions(
    String base,
    String key,
    String m,
    List<Map<String, Object>> messages,
    List<Map<String, Object>> tools,
    int secondsBudget
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
          1600
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
