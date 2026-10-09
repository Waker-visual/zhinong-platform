package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, device-scoped commands. This application never connects to a customer broker. */
@Service
@Transactional
public class DeviceCommandService {
  public record Input(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,80}") String requestId,
    @NotBlank String action, BigDecimal value,
    @NotBlank @Size(max = 300) String note
  ) {}
  public record Receipt(
    @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
    @NotBlank @Size(max = 300) String note
  ) {}
  public record Action(String code, String name, String deviceType, String role, Integer min, Integer max, String unit) {}
  public static final List<Action> ACTIONS = List.of(
    new Action("SET_OPENING", "设置闸门开度", "GATE", "OPERATOR", 0, 100, "%"),
    new Action("PUMP_START", "启动主泵", "PUMP", "OPERATOR", null, null, ""),
    new Action("PUMP_STOP", "停止主泵", "PUMP", "OPERATOR", null, null, ""),
    new Action("SET_FREQUENCY", "设置主泵频率", "PUMP", "ADMIN", 5, 50, "Hz"),
    new Action("EMERGENCY_STOP", "泵房急停", "PUMP", "OPERATOR", null, null, "")
  );
  private final Store store;
  private final JdbcTemplate db;
  private final AssetService assets;
  private final TelemetryService telemetry;
  private final DeviceCredentials credentials;

  public DeviceCommandService(Store store, AssetService assets, TelemetryService telemetry, DeviceCredentials credentials) {
    this.store = store;
    this.db = store.db();
    this.assets = assets;
    this.telemetry = telemetry;
    this.credentials = credentials;
  }

  private static final String SELECT = """
    SELECT id AS "id",device_id AS "deviceId",request_id AS "requestId",action AS "action",
      command_value AS "value",protocol AS "protocol",status AS "status",actor AS "actor",note AS "note",
      result_note AS "resultNote",created_at AS "createdAt",expires_at AS "expiresAt",
      dispatched_at AS "dispatchedAt",finished_at AS "finishedAt"
    FROM device_commands WHERE tenant_id=? AND device_id=?
    """;

  public Map<String, Object> list(String id) {
    store.lock("devices", id);
    var asset = assets.detail(id);
    expire(Identity.tenant(), id);
    return Map.of("actions", ACTIONS.stream().filter(a -> a.deviceType().equals(asset.get("deviceType"))).toList(),
      "commands", db.queryForList(SELECT + " ORDER BY created_at DESC,id DESC LIMIT 50", Identity.tenant(), id));
  }

  public Map<String, Object> create(String id, Input input) {
    Identity.require("ADMIN", "OPERATOR");
    String tenant = Identity.tenant();
    // Serialize command creation with tenant suspension, including queued-command invalidation.
    var activeTenant = db.queryForList("SELECT enabled FROM tenants WHERE id=? FOR UPDATE", tenant);
    if (activeTenant.isEmpty() || !Boolean.TRUE.equals(activeTenant.getFirst().get("ENABLED"))) {
      throw new ApiException(403, "租户已停用");
    }
    store.lock("devices", id);
    var asset = assets.detail(id);
    Action action = ACTIONS.stream().filter(a -> a.code().equals(input.action())).findFirst()
      .orElseThrow(() -> new ApiException(400, "不支持的设备指令"));
    if (action.role().equals("ADMIN")) Identity.require("ADMIN");
    validateValue(action, input.value());
    expire(tenant, id);
    var existing = db.queryForList(SELECT + " AND request_id=?", tenant, id, input.requestId());
    if (!existing.isEmpty()) {
      var old = existing.getFirst();
      if (!old.get("action").equals(input.action()) || !sameNumber(old.get("value"), input.value())
          || !old.get("note").equals(input.note().strip()) || !old.get("actor").equals(Identity.current().username())) {
        throw new ApiException(409, "请求编号已使用，重试必须保持相同操作和说明");
      }
      return old;
    }
    if (!action.deviceType().equals(asset.get("deviceType"))) throw new ApiException(400, "指令与设备类型不匹配");
    if (!Boolean.TRUE.equals(asset.get("controlEnabled")) || !"ACTIVE".equals(asset.get("lifecycle"))
        || !Set.of("SIMULATED", "HTTP_PUSH").contains(asset.get("protocol"))) {
      throw new ApiException(409, "请先由管理员启用设备及控制能力，并配置支持的接入方式");
    }
    if ("HTTP_PUSH".equals(asset.get("protocol")) && !Boolean.TRUE.equals(asset.get("credentialConfigured"))) {
      throw new ApiException(409, "请先配置设备接入凭据");
    }
    boolean stopping = Set.of("PUMP_STOP", "EMERGENCY_STOP").contains(action.code());
    if (!stopping && db.queryForObject("SELECT COUNT(*) FROM ai_irrigation_runs WHERE tenant_id=? AND pump_id=? AND status='RUNNING'",Long.class,tenant,id)>0)
      throw new ApiException(409,"设备正在执行定时灌溉，请先停止本次灌溉");
    if (!stopping) interlocks(tenant, id, asset);
    if (stopping) cancelPending(db, tenant, id, "停止操作取代先前未完成指令");
    else if (db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND device_id=? AND status IN ('PENDING','DISPATCHED')",
        Long.class, tenant, id) > 0) throw new ApiException(409, "已有指令等待设备回执，请先处理或等待超时");

    String commandId = UUID.randomUUID().toString();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    db.update("""
      INSERT INTO device_commands(id,tenant_id,device_id,request_id,action,command_value,protocol,status,actor,note,created_at,expires_at)
      VALUES(?,?,?,?,?,?,?,'PENDING',?,?,?,?)
      """, commandId, tenant, id, input.requestId(), action.code(), input.value(), asset.get("protocol"),
      Identity.current().username(), input.note().strip(), now, now.plusSeconds(120));
    store.audit("DEVICE_COMMAND_" + action.code(), commandId);
    if ("SIMULATED".equals(asset.get("protocol"))) {
      simulate(tenant, id, action.code(), input.value(),Identity.current().username());
      db.update("""
        UPDATE device_commands SET status='SUCCEEDED',dispatched_at=?,finished_at=?,result_note='本地模拟完成，未操作实体设备'
        WHERE tenant_id=? AND device_id=? AND id=?
        """, now, now, tenant, id, commandId);
    }
    return command(tenant, id, commandId);
  }

  private static boolean sameNumber(Object old, BigDecimal value) {
    return old == null ? value == null : value != null && ((BigDecimal) old).compareTo(value) == 0;
  }

  private static void validateValue(Action action, BigDecimal value) {
    if (action.min() == null) {
      if (value != null) throw new ApiException(400, "该指令不接受数值参数");
    } else if (value == null || value.compareTo(BigDecimal.valueOf(action.min())) < 0
        || value.compareTo(BigDecimal.valueOf(action.max())) > 0 || value.stripTrailingZeros().scale() > 0) {
      throw new ApiException(400, action.name() + "需要 " + action.min() + "–" + action.max() + " 范围内的整数");
    }
  }

  private void interlocks(String tenant, String id, Map<String, Object> asset) {
    long tolerance = Math.max(180, ((Number) asset.get("intervalSeconds")).longValue() * 3);
    String feedback = "GATE".equals(asset.get("deviceType")) ? "GATE_OPENING" : "PUMP_RUNNING";
    for (String metric : List.of("REMOTE_ENABLED", "FAULT", feedback)) {
      var latest = assets.latest(tenant, id, metric);
      if (latest == null || !app.zhinong.database.DatabaseTime.offset(latest.get("time")).toInstant().isAfter(Instant.now().minusSeconds(tolerance))) {
        throw new ApiException(409, "缺少新鲜的远程模式、故障或运行反馈，请先采集确认；停止指令仍可提交");
      }
      int value = ((Number) latest.get("value")).intValue();
      if ((metric.equals("REMOTE_ENABLED") && value != 1) || (metric.equals("FAULT") && value != 0)) {
        throw new ApiException(409, "设备不处于远程模式或存在故障，不能启动或调整参数");
      }
    }
  }

  private void simulate(String tenant, String id, String action, BigDecimal value,String actor) {
    var feedback = new LinkedHashMap<String, BigDecimal>();
    switch (action) {
      case "SET_OPENING" -> feedback.put("GATE_OPENING", value);
      case "PUMP_START" -> {
        feedback.put("PUMP_RUNNING", BigDecimal.ONE);
        var prior = assets.latest(tenant, id, "PUMP_FREQUENCY");
        feedback.put("PUMP_FREQUENCY", prior != null && ((BigDecimal) prior.get("value")).signum() > 0
          ? (BigDecimal) prior.get("value") : BigDecimal.valueOf(35));
        feedback.put("CURRENT", BigDecimal.valueOf(12));
        feedback.put("FLOW", BigDecimal.valueOf(20));
      }
      case "PUMP_STOP", "EMERGENCY_STOP" -> {
        for (String metric : List.of("PUMP_RUNNING", "PUMP_FREQUENCY", "CURRENT", "FLOW")) feedback.put(metric, BigDecimal.ZERO);
        if (action.equals("EMERGENCY_STOP")) feedback.put("STANDBY_RUNNING", BigDecimal.ZERO);
      }
      case "SET_FREQUENCY" -> feedback.put("PUMP_FREQUENCY", value);
      default -> throw new IllegalStateException("Unknown simulated command");
    }
    var configured = assets.channels(tenant, id).stream().map(c -> c.get("metric")).toList();
    var readings = feedback.entrySet().stream().filter(e -> configured.contains(e.getKey()))
      .map(e -> new WorkspaceInputs.Reading(e.getKey(), e.getValue())).toList();
    if (readings.isEmpty()) throw new ApiException(409, "设备未配置对应的反馈指标，请应用设备指标模板");
    telemetry.write(tenant, id, Instant.now(), readings, "SIMULATED", actor);
  }

  /** Fail-safe stop for an already persisted simulated run, also works after its owner is disabled. */
  public String stopScheduledSimulation(String tenant,String runId) {
    var runs=db.queryForList("SELECT * FROM ai_irrigation_runs WHERE tenant_id=? AND id=? AND status='RUNNING' FOR UPDATE",tenant,runId);
    if(runs.isEmpty()) return "";
    String id=runs.getFirst().get("PUMP_ID").toString();
    db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE",tenant,id);
    var original=db.queryForList("SELECT id FROM device_commands WHERE tenant_id=? AND device_id=? AND id=? AND protocol='SIMULATED' AND action='PUMP_START'",tenant,id,runs.getFirst().get("COMMAND_ID"));
    if(original.isEmpty()) throw new ApiException(409,"缺少可核验的模拟启动记录");
    String commandId=UUID.randomUUID().toString();var now=OffsetDateTime.now(ZoneOffset.UTC);
    simulate(tenant,id,"PUMP_STOP",null,"AI_AUTOMATION");
    db.update("""
      INSERT INTO device_commands(id,tenant_id,device_id,request_id,action,protocol,status,actor,note,result_note,created_at,expires_at,finished_at)
      VALUES(?,?,?,?,'PUMP_STOP','SIMULATED','SUCCEEDED','AI_AUTOMATION','定时灌溉停止','模拟停泵已完成',?,?,?)
      """,commandId,tenant,id,"ai-stop-"+runId,now,now,now);
    db.update("INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,?,'AI_IRRIGATION_STOP',?,?)",UUID.randomUUID().toString(),tenant,"AI_AUTOMATION",runId,java.sql.Timestamp.from(Instant.now()));
    return commandId;
  }

  public Map<String, Object> poll(String key) {
    var device = credentials.authenticate(key);
    expire(device.tenant(), device.id());
    boolean enabled = Boolean.TRUE.equals(db.queryForObject(
      "SELECT control_enabled FROM asset_profiles WHERE tenant_id=? AND device_id=?", Boolean.class, device.tenant(), device.id()));
    if (!enabled) return Map.of("commands", List.of());
    db.update("""
      UPDATE device_commands SET status='DISPATCHED',dispatched_at=CURRENT_TIMESTAMP
      WHERE tenant_id=? AND device_id=? AND status='PENDING' AND expires_at>CURRENT_TIMESTAMP
      """, device.tenant(), device.id());
    // Delivery is at least once. Gateways must deduplicate by id and honor expiresAt.
    var rows = db.queryForList(SELECT + " AND status='DISPATCHED' AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at LIMIT 1",
      device.tenant(), device.id());
    // A field gateway needs no member names or operator free text.
    var commands = rows.stream().map(row -> {
      var result = new LinkedHashMap<String, Object>();
      for (String field : List.of("id", "action", "value", "createdAt", "expiresAt")) result.put(field, row.get(field));
      return result;
    }).toList();
    return Map.of("commands", commands);
  }

  public Map<String, Object> receipt(String key, String commandId, Receipt input) {
    var device = credentials.authenticate(key);
    String tenant = device.tenant(), id = device.id();
    expire(tenant, id);
    var current = command(tenant, id, commandId);
    String hash = TelemetryService.hash(input.status() + "|" + input.note().strip());
    var saved = db.queryForObject("SELECT receipt_hash FROM device_commands WHERE tenant_id=? AND device_id=? AND id=?",
      String.class, tenant, id, commandId);
    if (saved != null) {
      if (!saved.equals(hash)) throw new ApiException(409, "回执内容与已保存结果不一致");
      return Map.of("accepted", true, "duplicate", true);
    }
    if (!"DISPATCHED".equals(current.get("status"))) throw new ApiException(409, "指令尚未领取、已取消或已超时，不能接收回执");
    db.update("""
      UPDATE device_commands SET status=?,result_note=?,finished_at=CURRENT_TIMESTAMP,receipt_hash=?
      WHERE tenant_id=? AND device_id=? AND id=?
      """, input.status(), input.note().strip(), hash, tenant, id, commandId);
    db.update("INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(), tenant, "DEVICE_HTTP", "DEVICE_RECEIPT_" + input.status(), commandId, LocalDateTime.now());
    // A receipt never fabricates a sensor reading. Actual position/running state arrives via telemetry.
    return Map.of("accepted", true, "duplicate", false);
  }

  public Map<String, Object> cancel(String id, String commandId) {
    Identity.require("ADMIN", "OPERATOR");
    store.lock("devices", id);
    String tenant = Identity.tenant();
    expire(tenant, id);
    var current = command(tenant, id, commandId);
    if (!"PENDING".equals(current.get("status"))) throw new ApiException(409, "只能撤销尚未被设备领取的指令；已领取指令需等待回执或提交停止操作");
    if (!"ADMIN".equals(Identity.current().role()) && !Identity.current().username().equals(current.get("actor"))) {
      throw new ApiException(403, "操作员只能撤销自己提交的指令");
    }
    db.update("UPDATE device_commands SET status='CANCELLED',result_note='用户撤销',finished_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND device_id=? AND id=?",
      tenant, id, commandId);
    store.audit("CANCEL_DEVICE_COMMAND", commandId);
    return command(tenant, id, commandId);
  }

  private Map<String, Object> command(String tenant, String id, String commandId) {
    var rows = db.queryForList(SELECT + " AND id=?", tenant, id, commandId);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  private void expire(String tenant, String id) {
    db.update("""
      UPDATE device_commands SET status='EXPIRED',result_note='超过 120 秒未获得有效回执，执行结果未知',finished_at=CURRENT_TIMESTAMP
      WHERE tenant_id=? AND device_id=? AND status IN ('PENDING','DISPATCHED') AND expires_at<=CURRENT_TIMESTAMP
      """, tenant, id);
  }

  public static void cancelPending(JdbcTemplate db, String tenant, String id, String reason) {
    db.update("""
      UPDATE device_commands SET status='CANCELLED',result_note=?,finished_at=CURRENT_TIMESTAMP
      WHERE tenant_id=? AND device_id=? AND status IN ('PENDING','DISPATCHED')
      """, reason, tenant, id);
  }
}
