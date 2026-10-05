
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

  /** 持久化配置文件：{user.dir}/config/llm.properties，优先级高于环境变量，一次配置永久生效。 */
  private final Path configPath =
    Path.of(System.getProperty("user.dir"), "config", "llm.properties");

  @PostConstruct
  void loadFileConfig() {
    if (!Files.isRegularFile(configPath)) return;
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
    } catch (IOException ignored) {
      /* 读不到就沿用环境变量 */
    }
  }

  /** 保存配置：立即生效并持久化到本地文件，空值表示保持当前值。 */
  public void saveConfig(String newUrl, String newApiKey, String newModel) {
    if (!blank(newUrl)) url = newUrl.trim();
    if (!blank(newApiKey)) apiKey = newApiKey.trim();
    if (!blank(newModel)) model = newModel.trim();
    Properties p = new Properties();
    p.setProperty("url", url == null ? "" : url);
    p.setProperty("api-key", apiKey == null ? "" : apiKey);
    p.setProperty("model", model == null ? "" : model);
    p.setProperty("fallback-url", fallbackUrl == null ? "" : fallbackUrl);
    p.setProperty("fallback-model", fallbackModel == null ? "" : fallbackModel);
    p.setProperty("timeout-seconds", String.valueOf(timeoutSeconds));
    try {
      Files.createDirectories(configPath.getParent());
      try (var out = Files.newOutputStream(configPath)) {
        p.store(out, "Zhinong LLM config (managed by platform admin)");
      }
    } catch (IOException ignored) {
      /* 写盘失败不影响本次运行 */
    }
  }

  public String url() {
    return url;
  }

  public String model() {
    return model;
  }

  public boolean apiKeySet() {
    return !apiKey.isBlank();
  }

  private static boolean blank(String v) {
    return v == null || v.isBlank();
  }

  public boolean cloudEnabled() {
    return !url.isBlank() && !apiKey.isBlank();
  }

  public boolean fallbackEnabled() {
    return !fallbackUrl.isBlank();
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
    if (cloudEnabled()) {
      Optional<LlmResponse> r =
        callCompletions(url, apiKey, model.isBlank() ? "deepseek-chat" : model, messages, tools);
      if (r.isPresent()) return r;
    }
    if (fallbackEnabled()) {
      return callCompletions(
        fallbackUrl,
        "",
        fallbackModel.isBlank() ? "qwen2.5" : fallbackModel,
        messages,
        tools
      );
    }
    return Optional.empty();
  }

  private Optional<LlmResponse> callCompletions(
    String base,
    String key,
    String m,
    List<Map<String, Object>> messages,
    List<Map<String, Object>> tools
  ) {
    try {
      Map<String, Object> body =
        Map.of(
          "model",
          m,
          "messages",
          messages,
          "temperature",
          0.3,
          "max_tokens",
          900,
          "tool_choice",
          "auto",
          "tools",
          tools
        );
      HttpRequest.Builder b =
        HttpRequest
          .newBuilder()
          .uri(URI.create(base + "/chat/completions"))
          .timeout(Duration.ofSeconds(timeoutSeconds))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      if (!key.isBlank()) b.header("Authorization", "Bearer " + key);
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) return Optional.empty();
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
      return Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
