package app.zhinong.ai;

import app.zhinong.ai.AiConversations.FinalizedRun;
import app.zhinong.ai.AiConversations.PreparedRun;
import app.zhinong.ai.AiConversations.Question;
import app.zhinong.security.Identity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话运行：POST /api/ai/conversations/{id}/stream 背后的实现。鉴权和幂等检查在
 * 调用方（请求线程）的 {@code AiConversations.prepareStream} 中完成——未授权/跨租户/
 * 幂等冲突在创建 SseEmitter 之前就抛出，转换成普通 404/403/409 JSON 响应，不会先建立一个
 * 流再报错。真正的模型调用、SSE 读取和落库都丢给虚拟线程执行器，不占用请求线程，也不让数据库
 * 事务在整个流式响应期间保持打开（prepareStream 和 finalizeStream 各自是独立的短事务）。
 */
@Service
public class AiStreamService {

  private final AiConversations conversations;
  private final LlmGateway llm;
  private final ExecutorService executor;

  public AiStreamService(AiConversations conversations, LlmGateway llm, ExecutorService aiStreamExecutor) {
    this.conversations = conversations;
    this.llm = llm;
    this.executor = aiStreamExecutor;
  }

  /** 和同步接口一样的输入校验（@Valid Question）；鉴权/幂等检查发生在这里，仍在请求线程上。 */
  public SseEmitter stream(String id, Question input) {
    Identity identity = Identity.current();
    PreparedRun prep = conversations.prepareStream(id, input); // throws 403/404/409 before any emitter exists
    SseEmitter emitter = new SseEmitter(0L); // 不设服务器超时；由模型网关自身的超时与信号量兜底
    AtomicBoolean disconnected = new AtomicBoolean(false);
    emitter.onCompletion(() -> disconnected.set(true));
    emitter.onTimeout(() -> disconnected.set(true));
    emitter.onError(e -> disconnected.set(true));
    executor.submit(() -> Identity.runAs(identity, () -> run(id, input, prep, emitter, disconnected)));
    return emitter;
  }

  private void run(String id, Question input, PreparedRun prep, SseEmitter emitter, AtomicBoolean disconnected) {
    AtomicInteger sequence = new AtomicInteger(0);
    try {
      if (prep.replay()) {
        replay(prep, emitter, sequence);
        return;
      }
      send(emitter, AgentEvent.runStarted(sequence.incrementAndGet()));
      AgentEvent.Activity context = new AgentEvent.Activity(
        "context",
        "context",
        "读取当前农场资料",
        "running",
        null,
        null,
        OffsetDateTime.now(),
        null
      );
      send(emitter, AgentEvent.activityStarted(sequence.incrementAndGet(), context));
      if (disconnected.get()) return;
      StringBuilder accumulated = new StringBuilder();
      var listener = (LlmGateway.StreamListener) text -> {
        if (disconnected.get()) return;
        accumulated.append(text);
        send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), text));
      };
      var result = disconnected.get()
        ? java.util.Optional.<LlmGateway.LlmResponse>empty()
        : llm.stream(prep.context(), List.of(), listener, disconnected::get);
      if (disconnected.get()) return; // 客户端已断开：不写入半截/完整的助手成功消息，直接结束
      String mode, diagnostic, answer;
      boolean modelAnswered = result.isPresent() && result.get().content() != null && !result.get().content().isBlank();
      if (modelAnswered) {
        mode = "llm";
        diagnostic = "OK";
        answer = result.get().content();
      } else {
        mode = "rule";
        diagnostic = llm.diagnostic();
        answer = conversations.fallbackAnswer(prep.report());
        // 规则回退不是模型的逐字增量；一次性整体发出，并通过 mode=rule 明确标记为规则回退，
        // 不能伪装成流式模型回答（规格：模型不可用：明确显示规则回退）。
        send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), answer));
      }
      if (answer.length() > 15000) answer = answer.substring(0, 15000) + "\n（回答过长已截断）";
      send(
        emitter,
        AgentEvent.activityCompleted(
          sequence.incrementAndGet(),
          new AgentEvent.Activity(
            "context",
            "context",
            "读取当前农场资料",
            "completed",
            null,
            modelAnswered ? "已汇总当前农场资料并生成回答" : "已汇总当前农场资料，模型暂不可用，已给出规则回退建议",
            context.startedAt(),
            OffsetDateTime.now()
          )
        )
      );
      if (disconnected.get()) return;
      FinalizedRun finalized = conversations.finalizeStream(
        prep.tenant(),
        id,
        input.requestId(),
        input.question().strip(),
        answer,
        mode,
        diagnostic,
        prep.title()
      );
      send(emitter, AgentEvent.messageCompleted(sequence.incrementAndGet(), finalized.messageId()));
      send(emitter, AgentEvent.runCompleted(sequence.incrementAndGet()));
      emitter.complete();
    } catch (Exception e) {
      // 已经成功发出 run.error 事件后仍调用 completeWithError 会触发 Spring 的异步异常处理流程，
      // 此时响应已经在发送 SSE 数据，再尝试提交一个错误响应会破坏分块编码，导致客户端读到
      // 不完整的流。run.error 事件本身已经把失败原因带给了前端，这里只需正常结束响应。
      try {
        if (!disconnected.get()) {
          send(emitter, AgentEvent.runError(sequence.incrementAndGet(), diagnosticFor(e), "回答失败，请重试"));
        }
      } finally {
        try {
          emitter.complete();
        } catch (Exception ignored) {
          /* emitter 可能已经结束或客户端已断开 */
        }
      }
    }
  }

  private void replay(PreparedRun prep, SseEmitter emitter, AtomicInteger sequence) {
    send(emitter, AgentEvent.runStarted(sequence.incrementAndGet()));
    send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), prep.existingAnswer()));
    send(emitter, AgentEvent.messageCompleted(sequence.incrementAndGet(), prep.existingMessageId()));
    send(emitter, AgentEvent.runCompleted(sequence.incrementAndGet()));
    emitter.complete();
  }

  private String diagnosticFor(Exception e) {
    return e instanceof app.zhinong.api.ApiException api ? String.valueOf(api.status()) : llm.diagnostic();
  }

  private void send(SseEmitter emitter, AgentEvent event) {
    try {
      emitter.send(event);
    } catch (Exception e) {
      // 客户端已断开（典型的 IOException/IllegalStateException）；上层循环会通过
      // onCompletion/onError 回调观察到 disconnected 标记并停止继续处理，这里只需吞掉异常。
    }
  }
}
