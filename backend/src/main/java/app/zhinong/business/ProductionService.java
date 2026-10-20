package app.zhinong.business;

import app.zhinong.api.ApiException;
import app.zhinong.security.Identity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Tenant-scoped harvest ledger. Additive metadata keeps pre-existing client records readable. */
@Service
@Transactional
public class ProductionService {
  private final Store store;
  private final FarmClock clock;
  private final ObjectMapper json;

  public ProductionService(Store store, FarmClock clock, ObjectMapper json) {
    this.store = store;
    this.clock = clock;
    this.json = json;
  }

  /** Used by every aggregate; void records remain visible in the ledger and its history. */
  public static String active(String alias) {
    return "NOT EXISTS(SELECT 1 FROM production_tracking x WHERE x.tenant_id=" + alias +
      ".tenant_id AND x.production_id=" + alias + ".id AND x.status='VOID')";
  }

  private static final String SELECT = """
    SELECT r.*,CASE WHEN x.production_id IS NULL THEN l.planting_id ELSE x.planting_id END AS PLANTING_ID,
      CASE WHEN x.production_id IS NULL THEN l.task_id ELSE x.task_id END AS TASK_ID,COALESCE(x.status,'ACTIVE') AS STATUS,
      COALESCE(x.revision,0) AS REVISION,COALESCE(x.change_reason,'') AS CHANGE_REASON
    FROM production r
    LEFT JOIN production_tracking x ON x.tenant_id=r.tenant_id AND x.production_id=r.id
    LEFT JOIN production_lineage l ON l.tenant_id=r.tenant_id AND l.production_id=r.id
    """;

  public List<Map<String, Object>> list(String farmId) {
    String tenant = Identity.tenant();
    if (farmId != null && !farmId.isBlank()) {
      store.get("farms", farmId);
      return store.db().queryForList(SELECT + """
        JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
        WHERE r.tenant_id=? AND p.farm_id=? ORDER BY r.record_date DESC,r.id
        """, tenant, farmId);
    }
    return store.db().queryForList(SELECT + "WHERE r.tenant_id=? AND " +
      Store.activeScope("production").replace("production.", "r.") + " ORDER BY r.record_date DESC,r.id", tenant);
  }

  private Map<String, Object> get(String id) {
    var rows = store.db().queryForList(SELECT + "WHERE r.tenant_id=? AND r.id=?", Identity.tenant(), id);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> create(Records.Production input) {
    Identity.require("ADMIN", "OPERATOR");
    String tenant = Identity.tenant();
    // Serialize request-id lookup and insert across plots and concurrent retries of one tenant.
    store.db().queryForObject("SELECT id FROM tenants WHERE id=? FOR UPDATE", String.class, tenant);
    String requestId = blank(input.requestId());
    String fingerprint = fingerprint(input);
    if (requestId != null) {
      var previous = store.db().queryForList(
        "SELECT production_id,request_hash FROM production_tracking WHERE tenant_id=? AND request_id=?", tenant, requestId);
      if (!previous.isEmpty()) {
        if (!fingerprint.equals(previous.getFirst().get("REQUEST_HASH")))
          throw new ApiException(409, "该提交标识已用于其他收获记录，请核对后重新提交");
        return get(previous.getFirst().get("PRODUCTION_ID").toString());
      }
    }
    lockTask(input.taskId());
    validate(input.plotId(), input.recordDate(), input.plantingId(), input.taskId());
    String id = store.insert("production", Store.fields("plot_id", input.plotId(), "record_date", input.recordDate(),
      "yield_kg", input.yieldKg(), "note", input.note()));
    store.db().update("""
      INSERT INTO production_tracking(tenant_id,production_id,planting_id,task_id,request_id,request_hash,status,revision,change_reason,updated_at)
      VALUES(?,?,?,?,?,?,'ACTIVE',0,'',?)
      """, tenant, id, blank(input.plantingId()), blank(input.taskId()), requestId, fingerprint, now());
    var result = get(id);
    appendHistory(id, "CREATE", "", result);
    return result;
  }

  public Map<String, Object> correct(String id, Records.ProductionCorrection input) {
    Identity.require("ADMIN");
    store.lock("production", id);
    var previous = get(id);
    requireCurrent(previous, input.expectedRevision());
    lockTask(input.taskId());
    // A correction must not turn archived history into active production by moving its plot.
    // Consistent ordering also avoids deadlocks when two corrections swap target plots.
    var plots = new TreeSet<>(List.of(previous.get("PLOT_ID").toString(), input.plotId()));
    for (String plotId : plots) requireActivePlot(store.lock("plots", plotId));
    validate(input.plotId(), input.recordDate(), input.plantingId(), input.taskId());
    ensureTracking(id, previous);
    store.db().update("UPDATE production SET plot_id=?,record_date=?,yield_kg=?,note=? WHERE tenant_id=? AND id=?",
      input.plotId(), input.recordDate(), input.yieldKg(), input.note(), Identity.tenant(), id);
    // Replacing an inherited research link must also allow explicitly clearing both relations.
    store.db().update("DELETE FROM production_lineage WHERE tenant_id=? AND production_id=?", Identity.tenant(), id);
    store.db().update("""
      UPDATE production_tracking SET planting_id=?,task_id=?,revision=revision+1,change_reason=?,updated_at=?
      WHERE tenant_id=? AND production_id=?
      """, blank(input.plantingId()), blank(input.taskId()), input.reason().strip(), now(), Identity.tenant(), id);
    var result = get(id);
    appendHistory(id, "CORRECT", input.reason().strip(), result);
    store.audit("CORRECT_PRODUCTION", id);
    return result;
  }

  public Map<String, Object> voidRecord(String id, Records.ProductionVoid input) {
    Identity.require("ADMIN");
    store.lock("production", id);
    var previous = get(id);
    requireCurrent(previous, input.expectedRevision());
    ensureTracking(id, previous);
    store.db().update("""
      UPDATE production_tracking SET status='VOID',revision=revision+1,change_reason=?,updated_at=?
      WHERE tenant_id=? AND production_id=?
      """, input.reason().strip(), now(), Identity.tenant(), id);
    var result = get(id);
    appendHistory(id, "VOID", input.reason().strip(), result);
    store.audit("VOID_PRODUCTION", id);
    return result;
  }

  public List<Map<String, Object>> history(String id) {
    get(id);
    return store.db().query("""
      SELECT revision,action,reason,actor,snapshot_json,occurred_at FROM production_history
      WHERE tenant_id=? AND production_id=? ORDER BY revision
      """, (rs, index) -> {
        var row = new LinkedHashMap<String, Object>();
        row.put("REVISION", rs.getInt("revision")); row.put("ACTION", rs.getString("action"));
        row.put("REASON", rs.getString("reason")); row.put("ACTOR", rs.getString("actor"));
        row.put("OCCURRED_AT", rs.getObject("occurred_at"));
        row.put("snapshot", json.readValue(rs.getString("snapshot_json"), Map.class));
        return row;
      }, Identity.tenant(), id);
  }

  private void requireCurrent(Map<String, Object> previous, Integer revision) {
    if ("VOID".equals(previous.get("STATUS"))) throw new ApiException(409, "收获记录已作废，不能再次更正或作废");
    if (revision == null || ((Number) previous.get("REVISION")).intValue() != revision)
      throw new ApiException(409, "记录已发生变更，请刷新台账后重新核对");
  }

  private void ensureTracking(String id, Map<String, Object> previous) {
    if (store.db().queryForObject("SELECT COUNT(*) FROM production_tracking WHERE tenant_id=? AND production_id=?",
      Integer.class, Identity.tenant(), id) == 0) {
      store.db().update("""
        INSERT INTO production_tracking(tenant_id,production_id,planting_id,task_id,status,revision,change_reason,updated_at)
        VALUES(?,?,?,?,'ACTIVE',0,'',?)
        """, Identity.tenant(), id, previous.get("PLANTING_ID"), previous.get("TASK_ID"), now());
      appendHistory(id, "LEGACY", "历史记录首次变更前的快照", previous);
    }
  }

  private void validate(String plotId, LocalDate day, String plantingId, String taskId) {
    var plot = store.lock("plots", plotId);
    requireActivePlot(plot);
    if (day.isAfter(clock.today())) throw new ApiException(400, "收获日期不能晚于农场今天");
    plantingId = blank(plantingId); taskId = blank(taskId);
    Map<String, Object> planting = null;
    if (plantingId != null) {
      planting = store.lock("plantings", plantingId);
      if (!plotId.equals(planting.get("PLOT_ID"))) throw new ApiException(400, "种植批次与收获地块不一致");
      if (day.isBefore(date(planting.get("START_DATE"))) || day.isAfter(date(planting.get("END_DATE"))))
        throw new ApiException(400, "收获日期必须位于所选种植批次周期内");
    }
    if (taskId != null) {
      var task = store.lock("farm_tasks", taskId);
      if (!plotId.equals(task.get("PLOT_ID")) || !"HARVEST".equals(task.get("TASK_TYPE")))
        throw new ApiException(400, "关联任务必须是该地块的收获任务");
      if (!"COMPLETED".equals(task.get("STATUS"))) throw new ApiException(409, "请先完成收获任务，再关联收获记录");
      if (planting == null) throw new ApiException(400, "关联收获任务时请同时选择种植批次");
      LocalDate due = date(task.get("DUE_DATE"));
      if (due.isBefore(date(planting.get("START_DATE"))) || due.isAfter(date(planting.get("END_DATE"))))
        throw new ApiException(400, "收获任务不属于所选种植批次周期");
      var receipts = store.db().queryForList("SELECT * FROM task_fieldwork WHERE tenant_id=? AND task_id=? FOR UPDATE",
        Identity.tenant(), taskId);
      if (!receipts.isEmpty()) {
        var receipt = receipts.getFirst();
        var area = (java.math.BigDecimal) receipt.get("ACTUAL_AREA_MU");
        if (receipt.get("COMPLETED_AT") == null || Objects.toString(receipt.get("COMPLETION_NOTE"), "").isBlank()
          || "UNCONFIRMED".equals(receipt.get("METHOD")) || area == null || area.signum() <= 0)
          throw new ApiException(409, "关联收获任务的完成回执不完整，请先核对实际方式、面积和结果");
        if (area.compareTo((java.math.BigDecimal) planting.get("AREA_MU")) > 0)
          throw new ApiException(400, "收获任务实际作业面积超过所选种植批次面积，请核对批次归属");
      }
    }
  }

  private void requireActivePlot(Map<String, Object> plot) {
    long archived = store.db().queryForObject("SELECT COUNT(*) FROM farm_archives WHERE tenant_id=? AND farm_id=?",
      Long.class, Identity.tenant(), plot.get("FARM_ID"));
    if (archived > 0) throw new ApiException(409, "农场已归档，请恢复后登记或更正收获记录");
  }

  private void lockTask(String taskId) {
    // Field-work completion locks its task before checking the plot's permitted area.
    // Use that order for harvest associations as well, before locking either plot.
    if (blank(taskId) != null) store.lock("farm_tasks", blank(taskId));
  }

  private void appendHistory(String id, String action, String reason, Map<String, Object> snapshot) {
    store.db().update("""
      INSERT INTO production_history(id,tenant_id,production_id,revision,action,reason,actor,snapshot_json,occurred_at)
      VALUES(?,?,?,?,?,?,?,?,?)
      """, UUID.randomUUID().toString(), Identity.tenant(), id, snapshot.get("REVISION"), action, reason,
      Identity.current().username(), json.writeValueAsString(snapshot), now());
  }

  private String fingerprint(Records.Production input) {
    try {
      String value = json.writeValueAsString(List.of(input.plotId(), input.recordDate().toString(),
        input.yieldKg().stripTrailingZeros().toPlainString(), input.note(),
        Objects.toString(blank(input.plantingId()), ""), Objects.toString(blank(input.taskId()), "")));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
  private static LocalDate date(Object value) { return LocalDate.parse(value.toString()); }
  private OffsetDateTime now() { return clock.now(); }
}
