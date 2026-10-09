package app.zhinong.ai;

import app.zhinong.ai.AiConversations.ActivitySummary;
import app.zhinong.ai.AiConversations.FinalizedRun;
import app.zhinong.ai.AiConversations.PreparedRun;
import app.zhinong.ai.AiConversations.Question;
import app.zhinong.ai.LlmGateway.ToolCall;
import app.zhinong.security.Identity;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * Task 6：在原有“读取当前农场资料”context 活动之外，增加一个有界（最多 {@link #MAX_TOOL_ROUNDS}
 * 轮，和旧 /api/ai/ask 工具循环一致）的只读工具调用循环，以及运行结束时的灌溉审批活动。工具名、
 * 标签和结果摘要只走 {@link AiStreamTools} 的固定白名单；未知工具名只生成错误活动，绝不执行。
 */
@Service
public class AiStreamService {

  /** 和同步路径的截断长度一致；在发给客户端和写库之间必须用同一个值和同一份文本，否则两边会不一致。 */
  private static final int MAX_ANSWER_LENGTH = 15000;
  private static final String CONTEXT_ACTIVITY_ID = "context";
  /** 和旧 /api/ai/ask 的 Function Calling 循环一致的轮数上限，避免模型反复调用工具不收敛。 */
  private static final int MAX_TOOL_ROUNDS = 3;
  /** 灌溉审批活动的 activityId 约定前缀：id 的其余部分就是 ai_irrigation_runs 的主键，前端据此
   * 解析出 {type:"irrigation-run", id} 目标，不需要额外的数据库列或事件字段。 */
  private static final String IRRIGATION_APPROVAL_PREFIX = "irrigation-run:";

  private final AiConversations conversations;
  private final LlmGateway llm;
  private final AiStreamTools tools;
  private final IrrigationService irrigation;
  private final ExecutorService executor;

  public AiStreamService(
    AiConversations conversations,
    LlmGateway llm,
    AiStreamTools tools,
    IrrigationService irrigation,
    ExecutorService aiStreamExecutor
  ) {
    this.conversations = conversations;
    this.llm = llm;
    this.tools = tools;
    this.irrigation = irrigation;
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

      List<Map<String, Object>> messages = new ArrayList<>(prep.context());
      List<Map<String, Object>> toolDefs = tools.definitions();
      List<ActivitySummary> toolActivities = new ArrayList<>();
      int[] activitySeq = { 1 }; // 1 已被 context 活动占用，context 的摘要在拿到最终结果后才追加

      StringBuilder accumulated = new StringBuilder();
      boolean[] sawDelta = { false };
      boolean[] truncated = { false };
      LlmGateway.StreamListener listener = text -> {
        if (disconnected.get()) return;
        sawDelta[0] = true;
        emitCapped(text, accumulated, truncated, emitter, sequence, disconnected);
      };

      LlmGateway.StreamResult result = null;
      for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        if (disconnected.get()) return;
        try {
          result = llm.stream(messages, toolDefs, listener, disconnected::get);
        } catch (RuntimeException upstreamFailure) {
          // 防御性兜底：即使网关实现本身抛出异常而不是按约定返回 StreamResult，也按“是否已经吐出过正文”
          // 来判断是部分失败还是彻底没有输出。
          result = sawDelta[0]
            ? LlmGateway.StreamResult.interrupted(accumulated.toString(), "UNAVAILABLE")
            : LlmGateway.StreamResult.unavailable("UNAVAILABLE");
        }
        if (disconnected.get()) return;
        if (result.outcome() != LlmGateway.StreamOutcome.TOOL_CALLS) break;
        boolean continueRounds = runToolRound(result.toolCalls(), prep.farmId(), messages, toolActivities, activitySeq, emitter, sequence, disconnected);
        if (!continueRounds) return; // 断线：不再继续，也不落库
      }
      if (disconnected.get()) return;
      if (result == null || result.outcome() == LlmGateway.StreamOutcome.TOOL_CALLS) {
        // 轮数耗尽仍在要求调用工具：和模型完全不可用同等对待，走规则回退，不假装有最终回答。
        result = sawDelta[0]
          ? LlmGateway.StreamResult.interrupted(accumulated.toString(), "UNAVAILABLE")
          : LlmGateway.StreamResult.unavailable("UNAVAILABLE");
      }

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

      List<ActivitySummary> activities = new ArrayList<>();
      activities.add(
        new ActivitySummary(CONTEXT_ACTIVITY_ID, 1, "context", "读取当前农场资料", "completed", null, resultSummary, contextStartedAt, contextFinishedAt)
      );
      activities.addAll(toolActivities);

      // 灌溉审批活动：不创建任何新的设备写入路径——只读取已有的 PROPOSED 建议并提示用户去
      // “灌溉管理”页签确认，真正的批准/取消仍然只能通过现有的 propose/approve/cancel 接口完成。
      if (!disconnected.get()) {
        for (Map<String, Object> proposal : irrigation.pendingProposals(prep.farmId())) {
          if (disconnected.get()) return;
          String runId = String.valueOf(proposal.get("id"));
          String plotName = String.valueOf(proposal.getOrDefault("plotName", ""));
          String reason = String.valueOf(proposal.getOrDefault("reason", ""));
          Object durationObj = proposal.get("durationSeconds");
          String approvalLabel = clip("灌溉建议待确认：" + plotName, 120);
          // 句末标点在拼接时去重：reason 本身已经以“。”收尾，直接拼上“；预计时长”会变成“。；”——
          // 阶段 E 验收截图发现的标点瑕疵。
          String approvalSummary = clip(dedupeSentencePunctuation("原因：" + reason + "；预计时长 " + durationObj + " 秒"), 500);
          OffsetDateTime approvalAt = OffsetDateTime.now();
          activitySeq[0]++;
          // 建议仍在等待人工确认，不是“已完成”的活动：状态是 pending（待确认），没有 finishedAt——
          // 时间线上只保留这一行摘要，真正的原因/时长/“去确认”由前端在时间线下方的独立卡片渲染。
          AgentEvent.Activity approvalActivity = new AgentEvent.Activity(
            IRRIGATION_APPROVAL_PREFIX + runId, "approval", approvalLabel, "pending", null, approvalSummary, approvalAt, null
          );
          send(emitter, AgentEvent.activityCompleted(sequence.incrementAndGet(), approvalActivity), disconnected);
          if (disconnected.get()) return;
          activities.add(
            new ActivitySummary(IRRIGATION_APPROVAL_PREFIX + runId, activitySeq[0], "approval", approvalLabel, "pending", null, approvalSummary, approvalAt, null)
          );
        }
      }
      if (disconnected.get()) return;

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

  /**
   * 执行一轮模型请求的工具调用：逐个发 activity.started/activity.completed，执行白名单内的只读工具
   * （farmId 来自 prep.farmId()，绝不使用模型给出的参数），把结果（或未知工具的错误）回填进
   * messages 供下一轮模型总结。返回 false 表示客户端已断开，调用方应立即停止、不再落库。
   */
  private boolean runToolRound(
    List<ToolCall> calls,
    String farmId,
    List<Map<String, Object>> messages,
    List<ActivitySummary> toolActivities,
    int[] activitySeq,
    SseEmitter emitter,
    AtomicInteger sequence,
    AtomicBoolean disconnected
  ) {
    Map<String, Object> assistantTurn = new LinkedHashMap<>();
    assistantTurn.put("role", "assistant");
    assistantTurn.put("content", null);
    List<Map<String, Object>> toolCallPayloads = new ArrayList<>();
    List<Map<String, Object>> toolReplies = new ArrayList<>();
    for (ToolCall call : calls.stream().limit(8).toList()) {
      if (disconnected.get()) return false;
      toolCallPayloads.add(
        Map.of("id", call.id(), "type", "function", "function", Map.of("name", call.name(), "arguments", call.arguments()))
      );
      String label = AiStreamTools.LABELS.get(call.name());
      activitySeq[0]++;
      String activityId = "tool-" + activitySeq[0];
      if (label == null) {
        // 未知工具名：只生成错误活动，绝不执行；仍需回填一条 tool 消息，否则下一轮请求结构非法。
        OffsetDateTime at = OffsetDateTime.now();
        AgentEvent.Activity errorActivity = new AgentEvent.Activity(activityId, "tool", "不支持的操作", "error", null, null, at, at);
        send(emitter, AgentEvent.activityCompleted(sequence.incrementAndGet(), errorActivity), disconnected);
        if (disconnected.get()) return false;
        toolActivities.add(new ActivitySummary(activityId, activitySeq[0], "tool", "不支持的操作", "error", null, null, at, at));
        toolReplies.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", "{\"error\":\"unsupported_tool\"}"));
        continue;
      }
      OffsetDateTime startedAt = OffsetDateTime.now();
      send(
        emitter,
        AgentEvent.activityStarted(sequence.incrementAndGet(), new AgentEvent.Activity(activityId, "tool", label, "running", null, null, startedAt, null)),
        disconnected
      );
      if (disconnected.get()) return false;
      try {
        Object toolResult = tools.run(call.name(), farmId);
        String summary = tools.resultSummary(call.name(), toolResult);
        OffsetDateTime finishedAt = OffsetDateTime.now();
        send(
          emitter,
          AgentEvent.activityCompleted(sequence.incrementAndGet(), new AgentEvent.Activity(activityId, "tool", label, "completed", null, summary, startedAt, finishedAt)),
          disconnected
        );
        if (disconnected.get()) return false;
        toolActivities.add(new ActivitySummary(activityId, activitySeq[0], "tool", label, "completed", null, summary, startedAt, finishedAt));
        toolReplies.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", tools.toJson(toolResult)));
      } catch (Exception toolFailure) {
        OffsetDateTime finishedAt = OffsetDateTime.now();
        String safeDetail = "读取失败，请重试";
        send(
          emitter,
          AgentEvent.activityCompleted(sequence.incrementAndGet(), new AgentEvent.Activity(activityId, "tool", label, "error", safeDetail, null, startedAt, finishedAt)),
          disconnected
        );
        if (disconnected.get()) return false;
        toolActivities.add(new ActivitySummary(activityId, activitySeq[0], "tool", label, "error", safeDetail, null, startedAt, finishedAt));
        toolReplies.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", "{\"error\":\"tool_failed\"}"));
      }
    }
    assistantTurn.put("tool_calls", toolCallPayloads);
    messages.add(assistantTurn);
    messages.addAll(toolReplies);
    return !disconnected.get();
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

  private static String clip(String value, int max) {
    return value != null && value.length() > max ? value.substring(0, max) : value;
  }

  /** 把拼接后连续出现的句末标点（。！？；，）折叠成最后一个字符，例如把“……天气预报。；预计”
   * 修正为“……天气预报；预计”。只处理这几个全角标点，不触及正文其余内容。 */
  private static String dedupeSentencePunctuation(String text) {
    if (text == null) return null;
    var matcher = java.util.regex.Pattern.compile("[。！？；，]{2,}").matcher(text);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (matcher.find()) {
      out.append(text, last, matcher.start());
      out.append(text.charAt(matcher.end() - 1));
      last = matcher.end();
    }
    out.append(text.substring(last));
    return out.toString();
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
