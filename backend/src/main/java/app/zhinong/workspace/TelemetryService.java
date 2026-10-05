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
  private final SecureRandom random = new SecureRandom();

  public TelemetryService(Store store, AssetService assets, DeviceCredentials credentials) {
    this.db = store.db();
    this.store = store;
    this.assets = assets;
    this.credentials = credentials;
  }

  public Map<String, Object> collect(String id) {
    Identity.require("ADMIN", "OPERATOR");
    store.lock("devices", id);
    var asset = assets.detail(id);
    active(asset, "SIMULATED");
    double phase = Instant.now().getEpochSecond() / 1800.0 + Math.abs(id.hashCode() % 37);
    var values = assets
      .channels(Identity.tenant(), id)
      .stream()
      .map(c -> {
        var m = MetricCatalog.get(c.get("metric").toString());
        double amplitude = Math.max(0.2, Math.abs(m.normal()) * 0.12);
        double number = Math.max(
          m.min(),
          Math.min(m.max(), m.normal() + Math.sin(phase + (m.code().hashCode() % 9)) * amplitude)
        );
        // Control feedback must not drift when a user samples environmental metrics.
        if (Set.of("GATE_OPENING", "PUMP_RUNNING", "STANDBY_RUNNING", "PUMP_FREQUENCY", "REMOTE_ENABLED", "FAULT", "CURRENT", "FLOW").contains(m.code())) {
          var prior = assets.latest(Identity.tenant(), id, m.code());
          number = prior == null ? m.normal() : ((Number) prior.get("value")).doubleValue();
        }
        if (MetricCatalog.discrete(m.code())) number = Math.round(number);
        return new WorkspaceInputs.Reading(
          m.code(),
          BigDecimal.valueOf(number).setScale(3, RoundingMode.HALF_UP)
        );
      })
      .toList();
    write(Identity.tenant(), id, Instant.now(), values, "SIMULATED", Identity.current().username());
    return assets.detail(id);
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
      if (prior == null || !time.isBefore(((OffsetDateTime) prior.get("time")).toInstant())) {
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
    db.update(
      "INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(),
      tenant,
      actor,
      "TELEMETRY_" + source,
      id,
      LocalDateTime.now()
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

  public List<Map<String, Object>> alerts(String farmId, String status) {
    String tenant = Identity.tenant();
    if (farmId != null && !farmId.isBlank()) store.get("farms", farmId);
    if (
      status != null && !Set.of("OPEN", "ACKNOWLEDGED", "RESOLVED", "ALL").contains(status)
    ) throw new ApiException(400, "告警状态无效");
    String sql = """
      SELECT a.id AS "id",a.device_id AS "deviceId",d.name AS "deviceName",d.farm_id AS "farmId",
        f.name AS "farmName",a.metric AS "metric",a.measured_value AS "value",a.message AS "message",
        a.status AS "status",a.opened_at AS "openedAt",a.updated_at AS "updatedAt",
        a.handled_by AS "handledBy",a.handle_note AS "handleNote"
      FROM device_alerts a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
      JOIN farms f ON f.tenant_id=d.tenant_id AND f.id=d.farm_id WHERE a.tenant_id=?
      """;
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (AssetService.blank(farmId) != null) {
      sql += " AND d.farm_id=?";
      args.add(farmId);
    }
    if (status == null) sql += " AND a.status<>'RESOLVED'";
    else if (!status.equals("ALL")) {
      sql += " AND a.status=?";
      args.add(status);
    }
    return db.queryForList(sql + " ORDER BY a.opened_at DESC LIMIT 200", args.toArray());
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
