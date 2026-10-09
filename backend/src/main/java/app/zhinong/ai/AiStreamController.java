package app.zhinong.ai;

import app.zhinong.ai.AiConversations.Question;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话端点，单独成一个 controller 以避免 AiStreamService 和 AiConversations 之间的
 * Bean 循环依赖（AiStreamService 需要调用 AiConversations 的共享鉴权/落库辅助方法）。
 * 鉴权、幂等检查在 {@link AiStreamService#stream} 内部的请求线程部分完成，失败时在这里
 * 直接抛出，Spring 按普通 JSON 错误响应处理，不会先建立 SSE 连接再报错。
 */
@RestController
@RequestMapping("/api/ai")
public class AiStreamController {

  private final AiStreamService stream;

  public AiStreamController(AiStreamService stream) {
    this.stream = stream;
  }

  // 不在这里声明 produces=text/event-stream：这会让 Spring 在方法执行前就把响应内容协商锁定为
  // SSE，导致鉴权失败时异常处理器想写 JSON 错误体却找不到可用的转换器而变成 500。SseEmitter
  // 被返回后由 Spring 自己按 text/event-stream 处理；未授权等情况在它被创建前就已抛出普通异常。
  @PostMapping("/conversations/{id}/stream")
  public SseEmitter stream(@PathVariable String id, @RequestBody @Valid Question input) {
    return stream.stream(id, input);
  }
}
