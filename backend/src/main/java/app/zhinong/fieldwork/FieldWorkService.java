package app.zhinong.fieldwork;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped field observations, assigned work and accountable completion. */
@Service
@Transactional
public class FieldWorkService {

  private final Store store;

  public FieldWorkService(Store store) {
    this.store = store;
  }

  public record IssueInput(
    @NotBlank String plotId,
    @NotBlank @Pattern(regexp = "PEST|WATER|EQUIPMENT|OTHER") String category,
    @NotBlank @Pattern(regexp = "NORMAL|HIGH") String severity,
    @NotBlank @Size(max = 500) String description
  ) {}

  public record PlanInput(
    @NotBlank String plotId,
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Pattern(
      regexp = "SOWING|IRRIGATION|FERTILIZING|HARVEST|INSPECTION|PROTECTION"
    ) String taskType,
    @NotNull LocalDate dueDate,
    @NotBlank String assigneeId,
    @NotBlank @Pattern(
      regexp = "UNCONFIRMED|DRONE|MANUAL|SERVICE"
    ) String method,
    @NotNull @Size(max = 500) String note
  ) {}

  public record ProgressInput(
    @NotBlank @Pattern(
      regexp = "RUNNING|BLOCKED|COMPLETED|CANCELLED"
    ) String status,
    @NotBlank @Size(max = 500) String note,
    @NotBlank @Pattern(
      regexp = "UNCONFIRMED|DRONE|MANUAL|SERVICE"
    ) String method,
    @NotNull @DecimalMin("0") @DecimalMax("99999999") BigDecimal actualAreaMu
  ) {}

  public record ReviewInput(@NotBlank @Size(max = 500) String note) {}

  public Object overview(String farmId) {
    store.get("farms", farmId);
    String tenant = Identity.tenant();
    var tasks = store
      .db()
      .queryForList(
        """
        SELECT t.*,p.name AS plot_name,p.area_mu AS plot_area_mu,d.assignee_id,m.display_name AS assignee_name,
        d.method,d.blocked_reason,d.completion_note,d.actual_area_mu,d.completed_at,
        CASE WHEN d.task_id IS NULL THEN FALSE ELSE TRUE END AS tracked
        FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
        LEFT JOIN task_fieldwork d ON d.tenant_id=t.tenant_id AND d.task_id=t.id
        LEFT JOIN members m ON m.tenant_id=d.tenant_id AND m.id=d.assignee_id
        WHERE t.tenant_id=? AND p.farm_id=? ORDER BY t.due_date,t.id
        """,
        tenant,
        farmId
      );
    var issues = store
      .db()
      .queryForList(
        """
        SELECT i.*,p.name AS plot_name,m.display_name AS reporter_name,t.status AS task_status
        FROM field_issues i JOIN plots p ON p.tenant_id=i.tenant_id AND p.id=i.plot_id
        JOIN members m ON m.tenant_id=i.tenant_id AND m.id=i.reporter_id
        LEFT JOIN farm_tasks t ON t.tenant_id=i.tenant_id AND t.id=i.task_id
        WHERE i.tenant_id=? AND p.farm_id=? ORDER BY i.created_at DESC,i.id
        """,
        tenant,
        farmId
      );
    // A minimal roster for assignment; credential and account preference fields are never selected.
    var crew = Identity.current().role().equals("ADMIN")
      ? store
          .db()
          .queryForList(
            "SELECT id,display_name,role FROM members WHERE tenant_id=? AND enabled=TRUE AND role IN ('ADMIN','OPERATOR') ORDER BY display_name",
            tenant
          )
      : List.of();
    return Map.of(
      "tasks",
      tasks,
      "issues",
      issues,
      "crew",
      crew,
      "today",
      LocalDate.now()
    );
  }

  public Object report(IssueInput input) {
    Identity.require("ADMIN", "OPERATOR");
    store.get("plots", input.plotId());
    String id = UUID.randomUUID().toString();
    store
      .db()
      .update(
        "INSERT INTO field_issues(id,tenant_id,plot_id,category,severity,description,reporter_id,status,created_at) VALUES(?,?,?,?,?,?,?,'OPEN',CURRENT_TIMESTAMP)",
        id,
        Identity.tenant(),
        input.plotId(),
        input.category(),
        input.severity(),
        input.description().strip(),
        Identity.current().memberId()
      );
    store.audit("FIELD_ISSUE_REPORTED", id);
    return Map.of("id", id);
  }

  public Object create(PlanInput input, String issueId) {
    Identity.require("ADMIN");
    store.get("plots", input.plotId());
    if (issueId != null) {
      var issue = issue(issueId, true);
      if (!"OPEN".equals(issue.get("STATUS"))) throw new ApiException(
        409,
        "该问题已安排处理或已关闭，请刷新"
      );
      if (!input.plotId().equals(issue.get("PLOT_ID"))) throw new ApiException(
        400,
        "处理任务必须属于问题所在的地块"
      );
    }
    validateAssignee(input.assigneeId());
    String id = store.insert(
      "farm_tasks",
      Store.fields(
        "plot_id",
        input.plotId(),
        "title",
        input.title().strip(),
        "task_type",
        input.taskType(),
        "due_date",
        input.dueDate(),
        "status",
        "PENDING",
        "note",
        input.note()
      )
    );
    store
      .db()
      .update(
        "INSERT INTO task_fieldwork(tenant_id,task_id,assignee_id,method,blocked_reason,completion_note,actual_area_mu) VALUES(?,?,?,?,?,?,0)",
        Identity.tenant(),
        id,
        input.assigneeId(),
        input.method(),
        "",
        ""
      );
    if (issueId != null) store
      .db()
      .update(
        "UPDATE field_issues SET status='ASSIGNED',task_id=? WHERE tenant_id=? AND id=?",
        id,
        Identity.tenant(),
        issueId
      );
    log(id, "PLANNED", input.note(), input.method(), BigDecimal.ZERO);
    return Map.of("id", id);
  }

  public Object assign(String id, PlanInput input) {
    Identity.require("ADMIN");
    var task = store.lock("farm_tasks", id);
    if (!input.plotId().equals(task.get("PLOT_ID"))) throw new ApiException(
      400,
      "已派发任务不能更换地块"
    );
    if (
      !Set.of("PENDING", "RUNNING").contains(task.get("STATUS"))
    ) throw new ApiException(409, "已结束任务不能重新安排");
    validateAssignee(input.assigneeId());
    ensureDetails(id);
    store
      .db()
      .update(
        "UPDATE farm_tasks SET title=?,task_type=?,due_date=?,note=? WHERE tenant_id=? AND id=?",
        input.title().strip(),
        input.taskType(),
        input.dueDate(),
        input.note(),
        Identity.tenant(),
        id
      );
    // Keep a reported blocker until an actual progress report clears it.
    store
      .db()
      .update(
        "UPDATE task_fieldwork SET assignee_id=?,method=? WHERE tenant_id=? AND task_id=?",
        input.assigneeId(),
        input.method(),
        Identity.tenant(),
        id
      );
    log(id, "PLANNED", input.note(), input.method(), BigDecimal.ZERO);
    return Map.of("id", id);
  }

  public Object progress(String id, ProgressInput input) {
    Identity.require("ADMIN", "OPERATOR");
    var task = store.lock("farm_tasks", id);
    var details = details(id);
    checkWorker(details);
    String old = task.get("STATUS").toString(),
      next = input.status();
    if (!Set.of("PENDING", "RUNNING").contains(old)) throw new ApiException(
      409,
      "任务已结束，请刷新数据"
    );
    if (next.equals("CANCELLED")) Identity.require("ADMIN");
    if (next.equals("COMPLETED")) {
      if (!old.equals("RUNNING")) throw new ApiException(
        409,
        "请先开始任务，再填写完成回执"
      );
      if (input.method().equals("UNCONFIRMED")) throw new ApiException(
        400,
        "完成时必须确认实际作业方式"
      );
      BigDecimal area = (BigDecimal) store
        .get("plots", task.get("PLOT_ID").toString())
        .get("AREA_MU");
      if (
        input.actualAreaMu().signum() <= 0 ||
        input.actualAreaMu().compareTo(area) > 0
      ) throw new ApiException(400, "实际作业面积应大于零且不超过地块面积");
    }
    ensureDetails(id);
    // Unassigned legacy tasks are claimed atomically while holding the task lock.
    store
      .db()
      .update(
        "UPDATE task_fieldwork SET assignee_id=COALESCE(assignee_id,?),method=?,blocked_reason=? WHERE tenant_id=? AND task_id=?",
        Identity.current().memberId(),
        input.method(),
        next.equals("BLOCKED") ? input.note() : "",
        Identity.tenant(),
        id
      );
    store
      .db()
      .update(
        "UPDATE farm_tasks SET status=? WHERE tenant_id=? AND id=?",
        next.equals("BLOCKED") ? "RUNNING" : next,
        Identity.tenant(),
        id
      );
    if (next.equals("COMPLETED")) store
      .db()
      .update(
        "UPDATE task_fieldwork SET completion_note=?,actual_area_mu=?,completed_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND task_id=?",
        input.note(),
        input.actualAreaMu(),
        Identity.tenant(),
        id
      );
    if (next.equals("CANCELLED")) store
      .db()
      .update(
        "UPDATE field_issues SET status='OPEN',task_id=NULL WHERE tenant_id=? AND task_id=? AND status='ASSIGNED'",
        Identity.tenant(),
        id
      );
    log(id, next, input.note(), input.method(), input.actualAreaMu());
    return store.get("farm_tasks", id);
  }

  /** Prevent the legacy status endpoint from bypassing assignment or completion evidence. */
  public void checkLegacyTransition(String id, String status) {
    var details = details(id);
    checkWorker(details);
    if (details != null) throw new ApiException(
      409,
      "该任务需在“今日农场”填写作业回执或资源缺口"
    );
  }

  public Object resolve(String id, ReviewInput input) {
    Identity.require("ADMIN");
    var issue = issue(id, true);
    if (!"ASSIGNED".equals(issue.get("STATUS"))) throw new ApiException(
      409,
      "请先安排并完成处理任务"
    );
    var task = store.get("farm_tasks", issue.get("TASK_ID").toString());
    if (!"COMPLETED".equals(task.get("STATUS"))) throw new ApiException(
      409,
      "处理任务尚未完成，不能关闭问题"
    );
    store
      .db()
      .update(
        "UPDATE field_issues SET status='RESOLVED',review_note=?,reviewed_by=?,resolved_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
        input.note(),
        Identity.current().memberId(),
        Identity.tenant(),
        id
      );
    store.audit("FIELD_ISSUE_RESOLVED", id);
    return Map.of("id", id);
  }

  public Object logs(String id) {
    store.get("farm_tasks", id);
    return store
      .db()
      .queryForList(
        "SELECT l.*,m.display_name AS actor_name FROM field_work_logs l JOIN members m ON m.tenant_id=l.tenant_id AND m.id=l.actor_id WHERE l.tenant_id=? AND l.task_id=? ORDER BY l.occurred_at,l.id",
        Identity.tenant(),
        id
      );
  }

  private void validateAssignee(String id) {
    var m = store.get("members", id);
    if (
      !Boolean.TRUE.equals(m.get("ENABLED")) ||
      !Set.of("ADMIN", "OPERATOR").contains(m.get("ROLE"))
    ) throw new ApiException(400, "请选择本租户已启用的农场主或操作员");
  }

  private Map<String, Object> issue(String id, boolean lock) {
    var rows = store
      .db()
      .queryForList(
        "SELECT * FROM field_issues WHERE tenant_id=? AND id=?" +
          (lock ? " FOR UPDATE" : ""),
        Identity.tenant(),
        id
      );
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  private Map<String, Object> details(String id) {
    var rows = store
      .db()
      .queryForList(
        "SELECT * FROM task_fieldwork WHERE tenant_id=? AND task_id=?",
        Identity.tenant(),
        id
      );
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void ensureDetails(String id) {
    if (details(id) == null) store
      .db()
      .update(
        "INSERT INTO task_fieldwork(tenant_id,task_id,method,blocked_reason,completion_note,actual_area_mu) VALUES(?,?,'UNCONFIRMED','','',0)",
        Identity.tenant(),
        id
      );
  }

  private void checkWorker(Map<String, Object> details) {
    if (
      Identity.current().role().equals("OPERATOR") &&
      details != null &&
      details.get("ASSIGNEE_ID") != null &&
      !Identity.current().memberId().equals(details.get("ASSIGNEE_ID"))
    ) throw new ApiException(
      403,
      "只能执行分配给自己的任务；调整负责人请联系农场主"
    );
  }

  private void log(
    String id,
    String action,
    String note,
    String method,
    BigDecimal area
  ) {
    store
      .db()
      .update(
        "INSERT INTO field_work_logs(id,tenant_id,task_id,actor_id,action,note,method,actual_area_mu,occurred_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        UUID.randomUUID().toString(),
        Identity.tenant(),
        id,
        Identity.current().memberId(),
        action,
        note,
        method,
        area
      );
    store.audit("FIELD_WORK_" + action, id);
  }
}
