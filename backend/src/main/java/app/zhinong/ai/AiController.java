package app.zhinong.ai;

import app.zhinong.security.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型服务状态与全局模型配置：/api/ai/status 供前端探测点亮，/api/ai/config/** 仅平台管理员可用。
 * 农场问答走 {@link AiConversations} 与 {@link AiStreamService}，按对话绑定的农场取数。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

  private final LlmGateway llm;

  public AiController(LlmGateway llm) {
    this.llm = llm;
  }
  public record LlmConfigInput(
    @Size(max = 300) String url,
    @Size(max = 300) String apiKey,
    @Size(max = 100) String model,
    boolean clearApiKey
  ) {}

  /** 测试连接：字段均可省略，省略时使用已保存配置（便于保存前先行测试）。 */
  public record LlmTestInput(
    @Size(max = 300) String url,
    @Size(max = 300) String apiKey,
    @Size(max = 100) String model
  ) {}

  /** 拉取模型列表：字段均可省略，省略时使用已保存配置（便于保存前先行拉取）。 */
  public record FetchModelsInput(
    @Size(max = 300) String url,
    @Size(max = 300) String apiKey
  ) {}

  public record ModelOptionsInput(
    @Size(max = 50) List<@NotBlank @Size(max = 100) String> ids
  ) {}

  public record RemoveModelOptionInput(@NotBlank @Size(max = 100) String id) {}

  /** 供租户与平台账号共同探测的轻量状态：只暴露“是否已配置云端模型”和模型 id（不是 URL 或密钥）
   * ——两者都不敏感，可以安全地在未鉴权具体权限的前提下返回给任何已登录账号，用于前端头部徽标
   * 展示“云端模型 · deepseek-flash”而不是泛泛的“模型服务已配置”。未配置云端模型时省略 model。 */
  @GetMapping("/status")
  public Map<String, Object> status() {
    boolean enabled = llm.cloudEnabled();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", true);
    body.put("llm", enabled);
    if (enabled) {
      String model = llm.model();
      body.put("model", model == null || model.isBlank() ? "deepseek-flash" : model);
    }
    return body;
  }

  /** 查看全局模型配置（脱敏：不返回密钥原文）。仅平台管理员可用。 */
  @GetMapping("/config")
  public ResponseEntity<Map<String, Object>> llmConfig() {
    if (!isPlatformManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅平台管理员可查看模型配置"));
    }
    return ResponseEntity.ok(
      Map.of(
        "url", llm.url(),
        "model", llm.model(),
        "apiKeySet", llm.apiKeySet(),
        "cloudEnabled", llm.cloudEnabled(),
        "modelOptions", llm.modelOptions()
      )
    );
  }

  /** 保存全局模型配置：立即生效并持久化。仅平台管理员可用。 */
  @PostMapping("/config")
  public ResponseEntity<Map<String, Object>> saveLlmConfig(@RequestBody @Valid LlmConfigInput input) {
    if (!isPlatformManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅平台管理员可配置模型"));
    }
    llm.saveConfig(input.url(), input.apiKey(), input.model(), input.clearApiKey());
    return ResponseEntity.ok(
      Map.of("ok", true, "url", llm.url(), "model", llm.model(), "cloudEnabled", llm.cloudEnabled())
    );
  }

  /**
   * 测试模型连接：只返回诊断码（OK/AUTH_FAILED/...），从不回显密钥或上游响应正文。
   * 省略字段表示使用已保存配置，便于保存前先行测试。仅平台管理员可用。
   */
  @PostMapping("/config/test")
  public ResponseEntity<Map<String, Object>> testLlmConfig(
    @RequestBody(required = false) @Valid LlmTestInput input
  ) {
    if (!isPlatformManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅平台管理员可测试模型连接"));
    }
    LlmTestInput in = input == null ? new LlmTestInput(null, null, null) : input;
    String code = llm.test(in.url(), in.apiKey(), in.model());
    return ResponseEntity.ok(Map.of("code", code));
  }

  /**
   * 拉取 OpenAI 兼容服务的模型列表，供前端“从服务获取”按钮使用；只返回 id 列表和诊断码，
   * 从不回显密钥或上游响应正文。省略字段表示使用已保存配置。仅平台管理员可用。
   */
  @PostMapping("/config/models")
  public ResponseEntity<Map<String, Object>> fetchModels(
    @RequestBody(required = false) @Valid FetchModelsInput input
  ) {
    if (!isPlatformManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅平台管理员可拉取模型列表"));
    }
    FetchModelsInput in = input == null ? new FetchModelsInput(null, null) : input;
    var result = llm.fetchModels(in.url(), in.apiKey());
    return ResponseEntity.ok(Map.of("models", result.models(), "diagnostic", result.diagnostic()));
  }

  /** 新增/合并模型选项（手动添加或从拉取结果中勾选合并）。仅平台管理员可用。 */
  @PostMapping("/config/models/options")
  public ResponseEntity<Map<String, Object>> addModelOptions(@RequestBody @Valid ModelOptionsInput input) {
    if (!isPlatformManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅平台管理员可维护模型选项"));
    }
    List<String> options = llm.mergeModelOptions(input.ids());
    return ResponseEntity.ok(Map.of("modelOptions", options));
  }

  /** 移除一个模型选项；当前生效模型不能直接移除。仅平台管理员可用。 */
  @PostMapping("/config/models/options/remove")
  public ResponseEntity<Map<String, Object>> removeModelOption(@RequestBody @Valid RemoveModelOptionInput input) {
    if (!isPlatformManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅平台管理员可维护模型选项"));
    }
    List<String> options = llm.removeModelOption(input.id());
    return ResponseEntity.ok(Map.of("modelOptions", options));
  }

  /** 模型地址和凭据是全局配置，租户管理员不得修改其他租户使用的网关。 */
  private boolean isPlatformManager() {
    return "PLATFORM_ADMIN".equals(Identity.current().role());
  }
}
