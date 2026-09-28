package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AssetService {

  private final Store store;
  private final JdbcTemplate db;

  public AssetService(Store store) {
    this.store = store;
    this.db = store.db();
  }

  private static final String SELECT = """
    SELECT d.id AS "id",d.farm_id AS "farmId",d.name AS "name",f.name AS "farmName",
      d.metric AS "primaryMetric",p.code AS "code",p.device_type AS "deviceType",
      p.protocol AS "protocol",p.lifecycle AS "lifecycle",p.plot_id AS "plotId",
      q.name AS "plotName",p.plan_x AS "planX",p.plan_y AS "planY",p.model AS "model",
      p.notes AS "notes",p.interval_seconds AS "intervalSeconds",p.revision AS "revision",
      p.last_received_at AS "lastReceivedAt",(p.credential_hash IS NOT NULL) AS "credentialConfigured"
    FROM devices d JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id
    JOIN farms f ON f.tenant_id=d.tenant_id AND f.id=d.farm_id
    LEFT JOIN plots q ON q.tenant_id=p.tenant_id AND q.id=p.plot_id
    WHERE d.tenant_id=?
    """;

  public Map<String, Object> catalog() {
    Identity.tenant();
    return Map.of(
      "metrics",
      MetricCatalog.METRICS,
      "types",
      MetricCatalog.TYPES.entrySet()
        .stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> Map.of("code", e.getKey(), "name", e.getValue()))
        .toList(),
      "protocols",
      MetricCatalog.PROTOCOLS.entrySet()
        .stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> Map.of("code", e.getKey(), "name", e.getValue()))
        .toList()
    );
  }

  public List<Map<String, Object>> list(String farmId) {
    String tenant = Identity.tenant();
    if (farmId != null && !farmId.isBlank()) store.get("farms", farmId);
    var rows = farmId == null || farmId.isBlank()
      ? db.queryForList(SELECT + " ORDER BY f.name,p.code", tenant)
      : db.queryForList(SELECT + " AND d.farm_id=? ORDER BY p.code", tenant, farmId);
    for (var row : rows) enrich(row, tenant);
    return rows;
  }

  public Map<String, Object> detail(String id) {
    String tenant = Identity.tenant();
    var rows = db.queryForList(SELECT + " AND d.id=?", tenant, id);
    if (rows.isEmpty()) throw ApiException.missing();
    var row = rows.getFirst();
    enrich(row, tenant);
    row.put(
      "alerts",
      db.queryForList(
        """
        SELECT id AS "id",metric AS "metric",message AS "message",status AS "status",
        measured_value AS "value",opened_at AS "openedAt",updated_at AS "updatedAt",
        handled_by AS "handledBy",handle_note AS "handleNote"
        FROM device_alerts WHERE tenant_id=? AND device_id=? ORDER BY opened_at DESC LIMIT 50
        """,
        tenant,
        id
      )
    );
    return row;
  }

  private void enrich(Map<String, Object> row, String tenant) {
    String id = row.get("id").toString();
    var channels = channels(tenant, id);
    for (var channel : channels) {
      String metric = channel.get("metric").toString();
      var spec = MetricCatalog.get(metric);
      channel.put("name", spec.name());
      channel.put("unit", spec.unit());
      channel.put("min", spec.min());
      channel.put("max", spec.max());
      var latest = latest(tenant, id, metric);
      channel.put("latest", latest);
    }
    row.put("channels", channels);
    Object last = row.get("lastReceivedAt");
    String freshness = "NO_DATA";
    if (last instanceof OffsetDateTime time) {
      long threshold = Math.max(180, ((Number) row.get("intervalSeconds")).longValue() * 3);
      freshness = time.toInstant().isAfter(Instant.now().minusSeconds(threshold))
        ? "FRESH"
        : "STALE";
    }
    if (!"ACTIVE".equals(row.get("lifecycle"))) freshness = row.get("lifecycle").toString();
    row.put("freshness", freshness);
    row.put(
      "alertCount",
      db.queryForObject(
        "SELECT COUNT(*) FROM device_alerts WHERE tenant_id=? AND device_id=? AND status<>'RESOLVED'",
        Long.class,
        tenant,
        id
      )
    );
  }

  public List<Map<String, Object>> channels(String tenant, String id) {
    return db.queryForList(
      """
      SELECT metric AS "metric",lower_limit AS "lowerLimit",upper_limit AS "upperLimit"
      FROM device_channels WHERE tenant_id=? AND device_id=? ORDER BY metric
      """,
      tenant,
      id
    );
  }

  public Map<String, Object> latest(String tenant, String id, String metric) {
    var rows = db.queryForList(
      "SELECT * FROM (" + historyUnion() + ") r ORDER BY \"time\" DESC LIMIT 1",
      tenant,
      id,
      metric,
      tenant,
      id,
      metric
    );
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private String historyUnion() {
    return """
    SELECT measured_at AS "time",measured_value AS "value",source AS "source"
    FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=?
    UNION ALL
    SELECT CAST(o.measured_at AS TIMESTAMP WITH TIME ZONE) AS "time",
      o.measured_value AS "value",o.source AS "source"
    FROM observations o JOIN devices d ON d.tenant_id=o.tenant_id AND d.id=o.device_id
    WHERE o.tenant_id=? AND o.device_id=? AND d.metric=?
    """;
  }

  public Map<String, Object> history(String id, String metric, int hours) {
    store.get("devices", id);
    MetricCatalog.get(metric);
    if (hours < 1 || hours > 720) throw new ApiException(400, "历史范围为 1–720 小时");
    if (
      channels(Identity.tenant(), id)
        .stream()
        .noneMatch(c -> metric.equals(c.get("metric")))
    ) {
      throw new ApiException(400, "设备未配置该指标");
    }
    var start = OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    String bucket = hours <= 24 ? "MINUTE" : "HOUR";
    var points = db.queryForList(
      "SELECT DATE_TRUNC('" +
        bucket +
        "',\"time\") AS \"time\"," +
        "AVG(\"value\") AS \"value\",MIN(\"value\") AS \"min\",MAX(\"value\") AS \"max\"," +
        "COUNT(*) AS \"samples\" FROM (" +
        historyUnion() +
        ") r WHERE \"time\">=? " +
        "GROUP BY DATE_TRUNC('" +
        bucket +
        "',\"time\") ORDER BY \"time\"",
      Identity.tenant(),
      id,
      metric,
      Identity.tenant(),
      id,
      metric,
      start
    );
    var sources = db.queryForList(
      "SELECT \"source\",COUNT(*) AS \"count\" FROM (" +
        historyUnion() +
        ") r WHERE \"time\">=? GROUP BY \"source\"",
      Identity.tenant(),
      id,
      metric,
      Identity.tenant(),
      id,
      metric,
      start
    );
    return Map.of(
      "metric",
      MetricCatalog.get(metric),
      "hours",
      hours,
      "aggregation",
      bucket,
      "points",
      points,
      "sources",
      sources
    );
  }

  public Map<String, Object> save(String id, WorkspaceInputs.Asset input) {
    Identity.require("ADMIN");
    store.lock("farms", input.farmId());
    validate(input);
    if (id == null) {
      id = UUID.randomUUID().toString();
      var first = MetricCatalog.get(input.channels().getFirst().metric());
      db.update(
        "INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,?)",
        id,
        Identity.tenant(),
        input.farmId(),
        input.name().strip(),
        first.code(),
        first.unit(),
        input.protocol().equals("SIMULATED") ? "SIMULATED" : "MANUAL"
      );
      db.update(
        """
        INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol,lifecycle)
        VALUES(?,?,?,?,?,?)
        """,
        Identity.tenant(),
        id,
        input.code(),
        input.deviceType(),
        input.protocol(),
        input.lifecycle()
      );
    } else {
      store.lock("devices", id);
      var old = detail(id);
      if (((Number) old.get("revision")).intValue() != input.revision()) {
        throw new ApiException(409, "设备已被其他操作更新，请刷新后重试");
      }
      if (!old.get("farmId").equals(input.farmId())) {
        throw new ApiException(
          409,
          "设备已有农场归属；请在原农场维护，跨农场迁移需单独处理历史关联"
        );
      }
      // Metric identities remain immutable once readings exist; limits can be changed.
      var oldMetrics = new HashSet<>(
        channels(Identity.tenant(), id)
          .stream()
          .map(c -> c.get("metric").toString())
          .toList()
      );
      var newMetrics = new HashSet<>(
        input.channels().stream().map(WorkspaceInputs.Channel::metric).toList()
      );
      if (!newMetrics.containsAll(oldMetrics)) throw new ApiException(
        409,
        "已有指标保留用于历史追溯，可新增指标或修改阈值"
      );
      if (!old.get("protocol").equals(input.protocol())) {
        db.update(
          "UPDATE asset_profiles SET credential_hash=NULL,last_received_at=NULL WHERE tenant_id=? AND device_id=?",
          Identity.tenant(),
          id
        );
      }
      db.update(
        "UPDATE devices SET name=?,adapter=? WHERE tenant_id=? AND id=?",
        input.name().strip(),
        input.protocol().equals("SIMULATED") ? "SIMULATED" : "MANUAL",
        Identity.tenant(),
        id
      );
    }
    db.update(
      """
      UPDATE asset_profiles SET code=?,device_type=?,protocol=?,lifecycle=?,plot_id=?,plan_x=?,plan_y=?,
      model=?,notes=?,interval_seconds=?,revision=revision+1 WHERE tenant_id=? AND device_id=?
      """,
      input.code(),
      input.deviceType(),
      input.protocol(),
      input.lifecycle(),
      blank(input.plotId()),
      input.planX(),
      input.planY(),
      input.model(),
      input.notes(),
      input.intervalSeconds(),
      Identity.tenant(),
      id
    );
    for (var channel : input.channels())
      db.update(
        """
        MERGE INTO device_channels(tenant_id,device_id,metric,lower_limit,upper_limit)
        KEY(tenant_id,device_id,metric) VALUES(?,?,?,?,?)
        """,
        Identity.tenant(),
        id,
        channel.metric(),
        channel.lowerLimit(),
        channel.upperLimit()
      );
    store.audit("SAVE_ASSET", id);
    db.update(
      """
      INSERT INTO farm_profiles(tenant_id,farm_id) SELECT ?,? WHERE NOT EXISTS
      (SELECT 1 FROM farm_profiles WHERE tenant_id=? AND farm_id=?)
      """,
      Identity.tenant(),
      input.farmId(),
      Identity.tenant(),
      input.farmId()
    );
    db.update(
      "UPDATE farm_profiles SET layout_revision=layout_revision+1 WHERE tenant_id=? AND farm_id=?",
      Identity.tenant(),
      input.farmId()
    );
    return detail(id);
  }

  private void validate(WorkspaceInputs.Asset input) {
    if (
      !MetricCatalog.TYPES.containsKey(input.deviceType()) ||
      !MetricCatalog.PROTOCOLS.containsKey(input.protocol())
    ) {
      throw new ApiException(400, "不支持的设备类型或接入方式");
    }
    PlanGeometry.point(input.planX(), input.planY());
    if (
      blank(input.plotId()) != null &&
      !store.get("plots", input.plotId()).get("FARM_ID").equals(input.farmId())
    ) {
      throw new ApiException(400, "关联地块必须属于设备所在农场");
    }
    var seen = new HashSet<String>();
    for (var c : input.channels()) {
      MetricCatalog.get(c.metric());
      if (!seen.add(c.metric())) throw new ApiException(400, "监测指标不能重复");
      if (c.lowerLimit() != null) MetricCatalog.validate(c.metric(), c.lowerLimit());
      if (c.upperLimit() != null) MetricCatalog.validate(c.metric(), c.upperLimit());
      if (
        c.lowerLimit() != null &&
        c.upperLimit() != null &&
        c.lowerLimit().compareTo(c.upperLimit()) >= 0
      ) {
        throw new ApiException(400, "告警下限必须小于上限");
      }
    }
  }

  public void adoptLegacy(String id) {
    var d = store.get("devices", id);
    db.update(
      """
      INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol)
      VALUES(?,?,?,?,?)
      """,
      Identity.tenant(),
      id,
      "POINT-" + id.substring(0, 8),
      "OTHER",
      d.get("ADAPTER")
    );
    db.update(
      "INSERT INTO device_channels(tenant_id,device_id,metric) VALUES(?,?,?)",
      Identity.tenant(),
      id,
      d.get("METRIC")
    );
  }

  public static String blank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
