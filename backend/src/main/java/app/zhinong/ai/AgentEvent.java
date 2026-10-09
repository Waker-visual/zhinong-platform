package app.zhinong.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 流式运行事件：与前端 docs/superpowers/specs/2026-10-09-ai-agent-interface-design.md
 * 的 AgentRunEvent 协议一一对应。字段为空时在 JSON 中省略（NON_NULL），类型使用显式常量，
 * 避免前端拿到拼写不一致的事件名。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentEvent(
  int sequence,
  String type,
  Activity activity,
  String delta,
  String messageId,
  String diagnostic,
  String error
) {

  public static final String RUN_STARTED = "run.started";
  public static final String ACTIVITY_STARTED = "activity.started";
  public static final String ACTIVITY_UPDATED = "activity.updated";
  public static final String ACTIVITY_COMPLETED = "activity.completed";
  public static final String MESSAGE_DELTA = "message.delta";
  public static final String MESSAGE_COMPLETED = "message.completed";
  public static final String RUN_COMPLETED = "run.completed";
  public static final String RUN_ERROR = "run.error";

  /** 安全的活动摘要：只包含用户可验证的字段，不含原始工具参数或凭据。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Activity(
    String id,
    String kind,
    String label,
    String status,
    String detail,
    String resultSummary,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt
  ) {}

  public static AgentEvent runStarted(int seq) {
    return new AgentEvent(seq, RUN_STARTED, null, null, null, null, null);
  }

  public static AgentEvent activityStarted(int seq, Activity activity) {
    return new AgentEvent(seq, ACTIVITY_STARTED, activity, null, null, null, null);
  }

  public static AgentEvent activityUpdated(int seq, Activity activity) {
    return new AgentEvent(seq, ACTIVITY_UPDATED, activity, null, null, null, null);
  }

  public static AgentEvent activityCompleted(int seq, Activity activity) {
    return new AgentEvent(seq, ACTIVITY_COMPLETED, activity, null, null, null, null);
  }

  public static AgentEvent messageDelta(int seq, String delta) {
    return new AgentEvent(seq, MESSAGE_DELTA, null, delta, null, null, null);
  }

  public static AgentEvent messageCompleted(int seq, String messageId) {
    return new AgentEvent(seq, MESSAGE_COMPLETED, null, null, messageId, null, null);
  }

  public static AgentEvent runCompleted(int seq) {
    return new AgentEvent(seq, RUN_COMPLETED, null, null, null, null, null);
  }

  public static AgentEvent runError(int seq, String diagnostic, String error) {
    return new AgentEvent(seq, RUN_ERROR, null, null, null, diagnostic, error);
  }
}
