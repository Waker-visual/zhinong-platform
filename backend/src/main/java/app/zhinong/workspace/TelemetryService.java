package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TelemetryService {

  private final JdbcTemplate db;
  private final Store store;
  private final AssetService assets;
  private final DeviceCredentials credentials;
  private final DeviceAlertMonitor monitor;
  private final SecureRandom random = new SecureRandom();

  public TelemetryService(Store store, AssetService assets, DeviceCredentials credentials, DeviceAlertMonitor monitor) {
    this.db = store.db();
    this.store = store;
    this.assets = assets;
    this.credentials = credentials;
    this.monitor = monitor;
  }

  public Map<String, Object> collect(String id) {
    Identity.require("ADMIN", "OPERATOR");
    store.lock("devices", id);
    var asset = assets.detail(id);
    active(asset, "SIMULATED");
    var values = simulatedReadings(Identity.tenant(), id);
    write(Identity.tenant(), id, Instant.now(), values, "SIMULATED", Identity.current().username());
    return assets.detail(id);
  }

  /** System-only sampling restricted to the registered fictional demo farm and seeded devices. */
  public void collectOperatingDemo(String tenant, String farm) {
    String eligible = """
      SELECT d.id FROM devices d
      JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id
      JOIN demo_operating_farms r ON r.tenant_id=d.tenant_id AND r.farm_id=d.farm_id
      JOIN farm_profiles f ON f.tenant_id=d.tenant_id AND f.farm_id=d.farm_id AND f.demo=TRUE
      JOIN tenants t ON t.id=d.tenant_id AND t.enabled=TRUE AND t.code='demo-a'
      WHERE d.tenant_id=? AND d.farm_id=? AND d.adapter='SIMULATED' AND p.protocol='SIMULATED'
        AND p.lifecycle='ACTIVE' AND p.code LIKE 'DEMO-CTRL-%'
      """;
    var ids = db.queryForList(eligible, String.class, tenant, farm);
    for (String id : ids) {
      db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id);
      // Recheck scope and user edits after taking the same lock as device mutations.
      if (db.queryForList(eligible + " AND d.id=?", String.class, tenant, farm, id).isEmpty()) continue;
      var latest = db.queryForObject("SELECT MAX(measured_at) FROM telemetry_readings WHERE tenant_id=? AND device_id=?",
        OffsetDateTime.class, tenant, id);
      if (latest != null && latest.toInstant().isAfter(Instant.now().minusSeconds(55))) continue;
      write(tenant, id, Instant.now(), simulatedReadings(tenant, id), "SIMULATED", "DEMO_SIMULATOR");
    }
  }

  private List<WorkspaceInputs.Reading> simulatedReadings(String tenant, String id) {
    var now = Instant.now();
    var profile = db.queryForMap("SELECT d.farm_id,p.device_type FROM devices d JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id WHERE d.tenant_id=? AND d.id=?", tenant, id);
    var channels = assets.channels(tenant, id);
    var previous = new HashMap<String, BigDecimal>();
    Instant last = now;
    for (var channel : channels) {
      String metric = channel.get("metric").toString();
      var prior = assets.latest(tenant, id, metric);
      if (prior != null && "SIMULATED".equals(prior.get("source"))) {
        previous.put(metric, (BigDecimal) prior.get("value"));
        Instant at = app.zhinong.database.DatabaseTime.offset(prior.get("time")).toInstant();
        if (at.isBefore(last)) last = at;
      }
    }
    var sample = SyntheticTelemetry.sample(profile.get("DEVICE_TYPE").toString(), profile.get("FARM_ID").toString(),
      now, previous, Duration.between(last, now).getSeconds());
    var research = db.queryForList("""
      SELECT q.name FROM devices d JOIN demo_research_farms r ON r.tenant_id=d.tenant_id AND r.farm_id=d.farm_id
      JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id
      LEFT JOIN plots q ON q.tenant_id=p.tenant_id AND q.id=p.plot_id WHERE d.tenant_id=? AND d.id=?
      """, tenant, id);
    var clock = LocalDateTime.now();
    return channels
      .stream()
      .map(c -> {
        var m = MetricCatalog.get(c.get("metric").toString());
        double number = sample.get(m.code()).doubleValue();
        boolean feedback = Set.of("GATE_OPENING", "PUMP_RUNNING", "STANDBY_RUNNING", "PUMP_FREQUENCY", "REMOTE_ENABLED", "FAULT", "CURRENT", "FLOW", "ENERGY", "WATER_TOTAL", "EMERGENCY_STOP", "PARAMETER_WRITE_ENABLED", "RAINFALL").contains(m.code());
        if (!research.isEmpty() && !feedback) number = app.zhinong.bootstrap.ResearchSignals.reading(m.code(), clock.toLocalDate(),
          clock.getHour()+clock.getMinute()/60.0, Objects.toString(research.getFirst().get("NAME"), ""));
        if (MetricCatalog.discrete(m.code())) number = Math.round(number);
        return new WorkspaceInputs.Reading(
          m.code(),
          BigDecimal.valueOf(number).setScale(3, RoundingMode.HALF_UP)
        );
      })
      .toList();
  }

  public Map<String, Object> manual(String id, WorkspaceInputs.Measurements input) {
    Identity.require("ADMIN", "OPERATOR");
    store.lock("devices", id);
    active(assets.detail(id), "MANUAL");
    write(
      Identity.tenant(),
      id,
      input.measuredAt(),
      input.readings(),
      "MANUAL",
      Identity.current().username()
    );
    return assets.detail(id);
  }

  private void active(Map<String, Object> asset, String protocol) {
    if (!"ACTIVE".equals(asset.get("lifecycle"))) throw new ApiException(
      409,
      "设备维护或停用期间不能接收数据"
    );
    if (!protocol.equals(asset.get("protocol"))) throw new ApiException(
      409,
      "请使用与该设备配置相符的数据接入方式"
    );
  }

  public Map<String, Object> rotateKey(String id) {
    Identity.require("ADMIN");
    store.lock("devices", id);
    var asset = assets.detail(id);
    if (!"HTTP_PUSH".equals(asset.get("protocol"))) throw new ApiException(
      409,
      "仅 HTTP 上报设备需要接入凭据"
    );
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update(
      "UPDATE asset_profiles SET credential_hash=?,revision=revision+1 WHERE tenant_id=? AND device_id=?",
      hash(key),
      Identity.tenant(),
      id
    );
    DeviceCommandService.cancelPending(db, Identity.tenant(), id, "接入凭据已轮换，原指令失效");
    store.audit("ROTATE_DEVICE_KEY", id);
    return Map.of("key", key, "deviceId", id, "note", "凭据仅显示一次，重新生成会使旧凭据失效");
  }

  public Map<String, Object> ingest(String key, WorkspaceInputs.Ingest input) {
    var device = credentials.authenticate(key);
    String tenant = device.tenant(), id = device.id();
    String payload = hash(input.measuredAt() + "|" + canonical(input.readings()));
    var previous = db.queryForList(
      "SELECT payload_hash FROM ingestion_batches WHERE tenant_id=? AND device_id=? AND message_id=?",
      tenant,
      id,
      input.messageId()
    );
    if (!previous.isEmpty()) {
      if (!payload.equals(previous.getFirst().get("PAYLOAD_HASH"))) {
        // Preserve retries for batches written before optional units/canonical signatures existed.
        String legacy = hash(input.measuredAt() + "|" + input.readings().stream()
          .map(r -> "Reading[metric=" + r.metric() + ", value=" + r.value() + "]")
          .collect(java.util.stream.Collectors.joining(", ", "[", "]")));
        if (input.readings().stream().anyMatch(r -> r.unit() != null) || !legacy.equals(previous.getFirst().get("PAYLOAD_HASH"))) {
          throw new ApiException(409, "消息编号已使用，重试内容必须相同");
        }
        db.update("UPDATE ingestion_batches SET payload_hash=? WHERE tenant_id=? AND device_id=? AND message_id=?",
          payload, tenant, id, input.messageId());
      }
      return Map.of("accepted", true, "duplicate", true, "count", input.readings().size());
    }
    write(tenant, id, input.measuredAt(), input.readings(), "HTTP_PUSH", "DEVICE_HTTP");
    db.update(
      "INSERT INTO ingestion_batches(tenant_id,device_id,message_id,payload_hash,received_at) VALUES(?,?,?,?,?)",
      tenant,
      id,
      input.messageId(),
      payload,
      OffsetDateTime.now(ZoneOffset.UTC)
    );
    return Map.of("accepted", true, "duplicate", false, "count", input.readings().size());
  }

  void write(
    String tenant,
    String id,
    Instant time,
    List<WorkspaceInputs.Reading> readings,
    String source,
    String actor
  ) {
    if (time.isAfter(Instant.now())) throw new ApiException(400, "采集时间不能晚于当前时间");
    readings = readings.stream().map(r -> new WorkspaceInputs.Reading(r.metric(),
      MetricCatalog.normalize(r.metric(), r.value(), r.unit()))).toList();
    var channels = assets.channels(tenant, id);
    var seen = new HashSet<String>();
    for (var r : readings) {
      if (!seen.add(r.metric())) throw new ApiException(400, "一批数据不能重复包含同一指标");
      if (
        channels.stream().noneMatch(c -> r.metric().equals(c.get("metric")))
      ) throw new ApiException(400, "上报指标未在设备中配置");
      MetricCatalog.validate(r.metric(), r.value());
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    Integer interval = db.queryForObject("SELECT interval_seconds FROM asset_profiles WHERE tenant_id=? AND device_id=?", Integer.class, tenant, id);
    boolean current = time.isAfter(now.toInstant().minusSeconds(Math.max(180, interval * 3L)));
    for (var r : readings) {
      var prior = assets.latest(tenant, id, r.metric());
      db.update(
        """
        INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source)
        VALUES(?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID().toString(),
        tenant,
        id,
        r.metric(),
        r.value(),
        time.atOffset(ZoneOffset.UTC),
        now,
        source
      );
      if (current && (prior == null || !time.isBefore(app.zhinong.database.DatabaseTime.offset(prior.get("time")).toInstant()))) {
        var channel = channels
          .stream()
          .filter(c -> r.metric().equals(c.get("metric")))
          .findFirst()
          .orElseThrow();
        evaluateAlert(tenant, id, r, channel, now);
      }
    }
    db.update(
      "UPDATE asset_profiles SET last_received_at=? WHERE tenant_id=? AND device_id=?",
      now,
      tenant,
      id
    );
    monitor.checkDevice(tenant, id);
    db.update(
      "INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(),
      tenant,
      actor,
      "TELEMETRY_" + source,
      id,
      java.sql.Timestamp.from(Instant.now())
    );
  }

  static String canonical(List<WorkspaceInputs.Reading> readings) {
    return readings.stream().sorted(Comparator.comparing(WorkspaceInputs.Reading::metric))
      .map(r -> r.metric() + "=" + MetricCatalog.normalize(r.metric(), r.value(), r.unit()).stripTrailingZeros().toPlainString())
      .collect(java.util.stream.Collectors.joining("|"));
  }

  private void evaluateAlert(
    String tenant,
    String id,
    WorkspaceInputs.Reading reading,
    Map<String, Object> channel,
    OffsetDateTime now
  ) {
    var lower = (BigDecimal) channel.get("lowerLimit");
    var upper = (BigDecimal) channel.get("upperLimit");
    boolean low = lower != null && reading.value().compareTo(lower) < 0;
    boolean high = upper != null && reading.value().compareTo(upper) > 0;
    var open = db.queryForList(
      "SELECT id FROM device_alerts WHERE tenant_id=? AND device_id=? AND metric=? AND status<>'RESOLVED'",
      tenant,
      id,
      reading.metric()
    );
    if (low || high) {
      String message =
        MetricCatalog.get(reading.metric()).name() +
        (low ? "低于下限 " + lower : "高于上限 " + upper);
      if (open.isEmpty()) db.update(
        """
        INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID().toString(),
        tenant,
        id,
        reading.metric(),
        reading.value(),
        message,
        now,
        now
      );
      else db.update(
        "UPDATE device_alerts SET measured_value=?,message=?,updated_at=? WHERE tenant_id=? AND id=?",
        reading.value(),
        message,
        now,
        tenant,
        open.getFirst().get("ID")
      );
    } else {
      db.update(
        """
        UPDATE device_alerts SET status='RESOLVED',updated_at=?,handled_by='SYSTEM',handle_note='监测值恢复阈值内'
        WHERE tenant_id=? AND device_id=? AND metric=? AND status<>'RESOLVED'
        """,
        now,
        tenant,
        id,
        reading.metric()
      );
    }
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public List<Map<String, Object>> alerts(String farmId, String status) {
    String tenant = alertScope(farmId, status);
    monitor.scanFarm(tenant, farmId);
    return readAlerts(tenant, farmId, status);
  }

  /** Dashboard reads consume the committed monitor snapshot without spawning farm-wide writes. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> savedAlerts(String farmId, String status) {
    return readAlerts(alertScope(farmId, status), farmId, status);
  }

  private String alertScope(String farmId, String status) {
    String tenant = Identity.tenant();
    if (farmId != null && !farmId.isBlank()) store.get("farms", farmId);
    if (
      status != null && !Set.of("OPEN", "ACKNOWLEDGED", "RESOLVED", "ALL").contains(status)
    ) throw new ApiException(400, "告警状态无效");
    return tenant;
  }

  private List<Map<String, Object>> readAlerts(String tenant, String farmId, String status) {
    String sql = """
      SELECT a.id AS "id",a.device_id AS "deviceId",d.name AS "deviceName",d.farm_id AS "farmId",
        f.name AS "farmName",a.metric AS "metric",a.measured_value AS "value",a.message AS "message",
        a.status AS "status",a.opened_at AS "openedAt",a.updated_at AS "updatedAt",
        a.handled_by AS "handledBy",a.handle_note AS "handleNote",p.protocol AS "protocol",
        p.interval_seconds AS "intervalSeconds",p.last_received_at AS "lastReceivedAt",
        c.lower_limit AS "lowerLimit",c.upper_limit AS "upperLimit",i.id AS "issueId",i.status AS "issueStatus",
        p.plot_id AS "plotId",q.name AS "plotName"
      FROM device_alerts a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
      JOIN farms f ON f.tenant_id=d.tenant_id AND f.id=d.farm_id
      JOIN asset_profiles p ON p.tenant_id=a.tenant_id AND p.device_id=a.device_id
      LEFT JOIN plots q ON q.tenant_id=p.tenant_id AND q.id=p.plot_id
      LEFT JOIN device_channels c ON c.tenant_id=a.tenant_id AND c.device_id=a.device_id AND c.metric=a.metric
      LEFT JOIN alert_field_issues l ON l.tenant_id=a.tenant_id AND l.alert_id=a.id
      LEFT JOIN field_issues i ON i.tenant_id=l.tenant_id AND i.id=l.issue_id
      WHERE a.tenant_id=?
      """;
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (AssetService.blank(farmId) != null) {
      sql += " AND d.farm_id=?";
      args.add(farmId);
    } else sql += " AND NOT EXISTS(SELECT 1 FROM farm_archives x WHERE x.tenant_id=d.tenant_id AND x.farm_id=d.farm_id)";
    if (status == null) sql += " AND a.status<>'RESOLVED'";
    else if (!status.equals("ALL")) {
      sql += " AND a.status=?";
      args.add(status);
    }
    var rows = db.queryForList(sql + " ORDER BY a.opened_at DESC LIMIT 200", args.toArray());
    rows.forEach(TelemetryService::explainAlert);
    return rows;
  }

  private static void explainAlert(Map<String, Object> alert) {
    String metric = alert.get("metric").toString();
    boolean offline = metric.equals("OFFLINE"), stale = metric.equals("DATA_STALE");
    long threshold = DeviceAlertMonitor.thresholdSeconds(((Number) alert.get("intervalSeconds")).intValue());
    alert.put("thresholdSeconds", threshold);
    alert.put("lastReceivedAt", app.zhinong.database.DatabaseTime.offset(alert.get("lastReceivedAt")));
    alert.put("kind", offline ? "OFFLINE" : stale ? "DATA_STALE" : "THRESHOLD");
    alert.put("severity", offline || Set.of("FAULT", "EMERGENCY_STOP").contains(metric) ? "HIGH" : "WARNING");
    alert.put("source", "RULE_ENGINE");
    if (offline || stale) {
      alert.put("metricName", offline ? "设备离线" : "采样数据过期");
      alert.put("unit", "秒");
      alert.put("evidence", alert.get("message") + "；判定窗口 " + threshold + " 秒（上报周期的 3 倍，最低 180 秒）。");
      alert.put("recommendation", offline
        ? ("SIMULATED".equals(alert.get("protocol")) ? "先点击设备采集刷新模拟数据；若仍无更新，检查模拟数据流和设备启用状态。"
          : "依次检查设备电源或电量、网关网络及上报服务；确认接入地址与凭据配置，在设备页验证收到最新数据。")
        : "检查缺测通道接线与采样任务，校准设备时钟并上报当前时间的完整通道数据；历史补报不能用于判断当前生产状态。");
      return;
    }
    var spec = MetricCatalog.get(metric);
    alert.put("metricName", spec.name());
    alert.put("unit", spec.unit());
    var value = new BigDecimal(alert.get("value").toString());
    var lower = (BigDecimal) alert.get("lowerLimit");
    var upper = (BigDecimal) alert.get("upperLimit");
    boolean low = lower != null && value.compareTo(lower) < 0;
    alert.put("evidence", spec.name() + " " + value.stripTrailingZeros().toPlainString() + spec.unit()
      + "；设备配置范围 " + (lower == null ? "未设下限" : lower.stripTrailingZeros().toPlainString())
      + " ～ " + (upper == null ? "未设上限" : upper.stripTrailingZeros().toPlainString()) + spec.unit()
      + "。阈值来自设备配置，需结合现场与作物阶段复核。");
    String advice;
    if (metric.startsWith("SOIL_MOISTURE")) advice = low
      ? "复测土壤墒情并核对作物生育期、降雨和灌溉记录；确认缺水后在灌溉助手评估方案，人工确认后执行。"
      : "复测土壤墒情，检查积水、排水和近期灌溉记录；确认过湿后调整灌溉计划并跟踪恢复。";
    else if (metric.contains("TEMPERATURE")) advice = "核查温度探头安装和遮阳条件，对比相邻测点；结合天气与作物阶段评估通风、遮阳或保温措施。";
    else if (metric.equals("BATTERY")) advice = "检查设备供电与电池状态，安排充电或更换，并核对太阳能板、线缆和上报频率。";
    else if (Set.of("FAULT", "EMERGENCY_STOP", "VOLTAGE", "CURRENT").contains(metric)) advice = "先现场确认设备故障与供电状态，停止依赖异常反馈的自动操作；由具备资质的维护人员检查，复测正常后恢复。";
    else if (metric.equals("PEST_COUNT")) advice = "检查诱捕器和识别记录，开展现场虫情复核；结合虫种、密度及作物阶段制定防治方案。";
    else if (Set.of("WATER_LEVEL", "FLOW", "WATER_TOTAL").contains(metric)) advice = "核对水位或流量计读数，检查水源、管路与泵阀运行记录；确认原因后调整供排水安排。";
    else advice = "复测该指标并对比相邻测点，检查传感器安装、校准与阈值配置；确认生产异常后转为现场问题并安排复核。";
    alert.put("recommendation", advice);
  }

  public void handle(String id, WorkspaceInputs.AlertAction input) {
    Identity.require("ADMIN", "OPERATOR");
    var rows = db.queryForList(
      "SELECT status FROM device_alerts WHERE tenant_id=? AND id=? FOR UPDATE",
      Identity.tenant(),
      id
    );
    if (rows.isEmpty()) throw ApiException.missing();
    String old = rows.getFirst().get("STATUS").toString();
    if (old.equals("RESOLVED") || old.equals(input.status())) throw new ApiException(
      409,
      "该告警状态不能执行此操作"
    );
    db.update(
      "UPDATE device_alerts SET status=?,updated_at=?,handled_by=?,handle_note=? WHERE tenant_id=? AND id=?",
      input.status(),
      OffsetDateTime.now(ZoneOffset.UTC),
      Identity.current().username(),
      input.note(),
      Identity.tenant(),
      id
    );
    store.audit("ALERT_" + input.status(), id);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
