package app.zhinong.ai;

import app.zhinong.ai.AiConversations.ActivitySummary;
import app.zhinong.ai.AiConversations.FinalizedRun;
import app.zhinong.ai.AiConversations.PreparedRun;
import app.zhinong.ai.AiConversations.Question;
import app.zhinong.security.Identity;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

  /** 和同步路径的截断长度一致；在发给客户端和写库之间必须用同一个值和同一份文本，否则两边会不一致。 */
  private static final int MAX_ANSWER_LENGTH = 15000;
  private static final String CONTEXT_ACTIVITY_ID = "context";

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
        replay(prep, emitter, sequence, disconnected);
        return;
      }
      send(emitter, AgentEvent.runStarted(sequence.incrementAndGet()), disconnected);
      OffsetDateTime contextStartedAt = OffsetDateTime.now();
      send(
        emitter,
        AgentEvent.activityStarted(
          sequence.incrementAndGet(),
          new AgentEvent.Activity(CONTEXT_ACTIVITY_ID, "context", "读取当前农场资料", "running", null, null, contextStartedAt, null)
        ),
        disconnected
      );
      if (disconnected.get()) return;

      StringBuilder accumulated = new StringBuilder();
      boolean[] sawDelta = { false };
      boolean[] truncated = { false };
      LlmGateway.StreamListener listener = text -> {
        if (disconnected.get()) return;
        sawDelta[0] = true;
        emitCapped(text, accumulated, truncated, emitter, sequence, disconnected);
      };
      LlmGateway.StreamResult result;
      try {
        result = disconnected.get() ? null : llm.stream(prep.context(), List.of(), listener, disconnected::get);
      } catch (RuntimeException upstreamFailure) {
        // 防御性兜底：即使网关实现本身抛出异常而不是按约定返回 StreamResult，也按“是否已经吐出过正文”
        // 来判断是部分失败（不能假装模型不可用去拼规则回退）还是彻底没有输出（可以走规则回退）。
        result = sawDelta[0]
          ? LlmGateway.StreamResult.interrupted(accumulated.toString(), "UNAVAILABLE")
          : LlmGateway.StreamResult.unavailable("UNAVAILABLE");
      }
      if (disconnected.get()) return; // 客户端已断开：不写入半截/完整的助手成功消息，直接结束

      if (result.outcome() == LlmGateway.StreamOutcome.INTERRUPTED) {
        // 已经把部分模型正文展示给用户之后才失败：不能在后面悄悄拼一段规则回退——那会让客户端看到的
        // 内容和数据库里(本来什么都不会写)完全不一致。规格要求保留已生成正文，报错，允许用同一个
        // requestId 重试；这里不持久化任何东西，下次用相同 requestId 重试会重新完整走一遍。
        send(emitter, AgentEvent.runError(sequence.incrementAndGet(), result.diagnostic(), "回答失败，请重试"), disconnected);
        completeNormally(emitter);
        return;
      }

      boolean modelAnswered = result.outcome() == LlmGateway.StreamOutcome.COMPLETED;
      String mode, diagnostic;
      if (modelAnswered) {
        mode = "llm";
        diagnostic = "OK";
        // accumulated 是实际发给客户端的文本（包含可能的截断处理），不用 result.content()，
        // 保证落库内容和客户端看到的内容逐字一致。
      } else {
        // UNAVAILABLE：从未吐出过任何正文，等同于同步路径的“模型不可用”，走规则回退——
        // 这不是错误，是一次正常完成的、明确标记为规则回退的运行。
        mode = "rule";
        diagnostic = result.diagnostic();
        String fallbackText = conversations.fallbackAnswer(prep.report());
        emitCapped(fallbackText, accumulated, truncated, emitter, sequence, disconnected);
      }
      if (disconnected.get()) return;
      String answer = accumulated.toString();

      OffsetDateTime contextFinishedAt = OffsetDateTime.now();
      String resultSummary = modelAnswered ? "已汇总当前农场资料并生成回答" : "已汇总当前农场资料，模型暂不可用，已给出规则回退建议";
      send(
        emitter,
        AgentEvent.activityCompleted(
          sequence.incrementAndGet(),
          new AgentEvent.Activity(CONTEXT_ACTIVITY_ID, "context", "读取当前农场资料", "completed", null, resultSummary, contextStartedAt, contextFinishedAt)
        ),
        disconnected
      );
      if (disconnected.get()) return;

      List<ActivitySummary> activities = List.of(
        new ActivitySummary(CONTEXT_ACTIVITY_ID, 1, "context", "读取当前农场资料", "completed", null, resultSummary, contextStartedAt, contextFinishedAt)
      );
      FinalizedRun finalized = conversations.finalizeStream(
        prep.tenant(),
        id,
        input.requestId(),
        input.question().strip(),
        answer,
        mode,
        diagnostic,
        prep.title(),
        activities
      );
      send(emitter, AgentEvent.messageCompleted(sequence.incrementAndGet(), finalized.messageId()), disconnected);
      send(emitter, AgentEvent.runCompleted(sequence.incrementAndGet()), disconnected);
      completeNormally(emitter);
    } catch (Exception e) {
      // 已经成功发出 run.error 事件后仍调用 completeWithError 会触发 Spring 的异步异常处理流程，
      // 此时响应已经在发送 SSE 数据，再尝试提交一个错误响应会破坏分块编码，导致客户端读到
      // 不完整的流。run.error 事件本身已经把失败原因带给了前端，这里只需正常结束响应。
      try {
        if (!disconnected.get()) {
          send(emitter, AgentEvent.runError(sequence.incrementAndGet(), diagnosticFor(e), "回答失败，请重试"), disconnected);
        }
      } finally {
        completeNormally(emitter);
      }
    }
  }

  /** 把一段文本按 MAX_ANSWER_LENGTH 截断后再发给客户端并累加到 accumulated，保证客户端看到的
   * 和最终写库的字节完全一致：一旦累计长度超限，只把能塞进上限的那部分文本发出，加一条截断说明，
   * 之后的文本（包括同一调用方后续传入的 text）直接丢弃，不再增长。 */
  private void emitCapped(String text, StringBuilder accumulated, boolean[] truncated, SseEmitter emitter, AtomicInteger sequence, AtomicBoolean disconnected) {
    if (truncated[0] || disconnected.get()) return;
    int remaining = MAX_ANSWER_LENGTH - accumulated.length();
    if (remaining <= 0) {
      truncated[0] = true;
      String note = "\n（回答过长已截断）";
      accumulated.append(note);
      send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), note), disconnected);
      return;
    }
    if (text.length() <= remaining) {
      accumulated.append(text);
      send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), text), disconnected);
      return;
    }
    String clipped = text.substring(0, remaining);
    accumulated.append(clipped);
    if (!clipped.isEmpty()) send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), clipped), disconnected);
    truncated[0] = true;
    String note = "\n（回答过长已截断）";
    accumulated.append(note);
    send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), note), disconnected);
  }

  private void replay(PreparedRun prep, SseEmitter emitter, AtomicInteger sequence, AtomicBoolean disconnected) {
    send(emitter, AgentEvent.runStarted(sequence.incrementAndGet()), disconnected);
    for (Map<String, Object> row : prep.existingActivities()) {
      send(
        emitter,
        AgentEvent.activityCompleted(
          sequence.incrementAndGet(),
          new AgentEvent.Activity(
            String.valueOf(row.get("ACTIVITY_ID")),
            String.valueOf(row.get("KIND")),
            String.valueOf(row.get("LABEL")),
            String.valueOf(row.get("STATUS")),
            (String) row.get("DETAIL"),
            (String) row.get("RESULT_SUMMARY"),
            toOffsetDateTime(row.get("STARTED_AT")),
            toOffsetDateTime(row.get("FINISHED_AT"))
          )
        ),
        disconnected
      );
    }
    send(emitter, AgentEvent.messageDelta(sequence.incrementAndGet(), prep.existingAnswer()), disconnected);
    send(emitter, AgentEvent.messageCompleted(sequence.incrementAndGet(), prep.existingMessageId()), disconnected);
    send(emitter, AgentEvent.runCompleted(sequence.incrementAndGet()), disconnected);
    completeNormally(emitter);
  }

  private static OffsetDateTime toOffsetDateTime(Object value) {
    if (value == null) return null;
    if (value instanceof OffsetDateTime odt) return odt;
    if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
    if (value instanceof java.time.Instant instant) return instant.atOffset(ZoneOffset.UTC);
    return null;
  }

  private String diagnosticFor(Exception e) {
    return e instanceof app.zhinong.api.ApiException api ? String.valueOf(api.status()) : "UNAVAILABLE";
  }

  private void completeNormally(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (Exception ignored) {
      /* emitter 可能已经结束或客户端已断开 */
    }
  }

  private void send(SseEmitter emitter, AgentEvent event, AtomicBoolean disconnected) {
    try {
      emitter.send(event);
    } catch (Exception e) {
      // 客户端已断开（典型的 IOException/IllegalStateException）：立即标记，让调用方在下一次检查时
      // 停止继续读取上游、停止继续发送，也不会在断开之后还去落库一条“成功”的助手消息。
      disconnected.set(true);
    }
  }
}
