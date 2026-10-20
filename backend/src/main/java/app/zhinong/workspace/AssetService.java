package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.business.FarmClock;
import app.zhinong.database.DatabaseTime;
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
  private final CameraProfiles cameras;
  private final FarmClock clock;

  public AssetService(Store store, CameraProfiles cameras, FarmClock clock) {
    this.store = store;
    this.db = store.db();
    this.cameras = cameras;
    this.clock = clock;
  }

  private static final String SELECT = """
    SELECT d.id AS "id",d.farm_id AS "farmId",d.name AS "name",f.name AS "farmName",
      d.metric AS "primaryMetric",p.code AS "code",p.device_type AS "deviceType",
      p.protocol AS "protocol",p.lifecycle AS "lifecycle",p.plot_id AS "plotId",
      q.name AS "plotName",p.plan_x AS "planX",p.plan_y AS "planY",p.model AS "model",
      p.notes AS "notes",p.interval_seconds AS "intervalSeconds",p.revision AS "revision",
      p.last_received_at AS "lastReceivedAt",(p.credential_hash IS NOT NULL) AS "credentialConfigured",
      p.location_mode AS "locationMode",p.latitude AS "latitude",p.longitude AS "longitude",
      p.control_enabled AS "controlEnabled"
    FROM devices d JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id
    JOIN farms f ON f.tenant_id=d.tenant_id AND f.id=d.farm_id
    LEFT JOIN plots q ON q.tenant_id=p.tenant_id AND q.id=p.plot_id
    WHERE d.tenant_id=?
    """;

  public Map<String, Object> catalog() {
    Identity.tenant();
    return Map.of(
      "cameraScenes", cameras.scenes(),
      "presets", MetricCatalog.PRESETS.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .map(e -> Map.of("deviceType", e.getKey(), "metrics", e.getValue())).toList(),
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
      ? db.queryForList(SELECT + " AND NOT EXISTS(SELECT 1 FROM farm_archives a WHERE a.tenant_id=d.tenant_id AND a.farm_id=d.farm_id) ORDER BY f.name,p.code", tenant)
      : db.queryForList(SELECT + " AND d.farm_id=? ORDER BY p.code", tenant, farmId);
    for (var row : rows) enrich(row, tenant);
    return rows;
  }

  public Map<String, Object> cameraViews(String farmId) {
    String tenant = Identity.tenant();
    var farm = store.get("farms", farmId);
    var rows = db.queryForList(SELECT + " AND d.farm_id=? AND p.device_type='CAMERA' ORDER BY p.code", tenant, farmId);
    for (var row : rows) enrich(row, tenant);
    return Map.of("farmId", farmId, "farmName", farm.get("NAME"), "devices", rows);
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
        SELECT a.id AS "id",a.metric AS "metric",a.message AS "message",a.status AS "status",
        a.measured_value AS "value",a.opened_at AS "openedAt",a.updated_at AS "updatedAt",
        a.handled_by AS "handledBy",a.handle_note AS "handleNote",i.id AS "issueId",i.status AS "issueStatus"
        FROM device_alerts a LEFT JOIN alert_field_issues l ON l.tenant_id=a.tenant_id AND l.alert_id=a.id
        LEFT JOIN field_issues i ON i.tenant_id=l.tenant_id AND i.id=l.issue_id
        WHERE a.tenant_id=? AND a.device_id=? ORDER BY a.opened_at DESC LIMIT 50
        """,
        tenant,
        id
      )
    );
    return row;
  }

  private void enrich(Map<String, Object> row, String tenant) {
    Instant now = clock.instant();
    Object configured = row.get("credentialConfigured");
    row.put("credentialConfigured", Boolean.TRUE.equals(configured) || configured instanceof Number n && n.intValue() != 0);
    row.put("lastReceivedAt", DatabaseTime.utc(row.get("lastReceivedAt")));
    String id = row.get("id").toString();
    if ("CAMERA".equals(row.get("deviceType"))) row.put("camera", cameras.read(tenant, id));
    if("MACHINERY".equals(row.get("deviceType"))) {
      var profiles=db.queryForList("SELECT profile_json FROM machinery_profiles WHERE tenant_id=? AND device_id=?",String.class,tenant,id);
      if(!profiles.isEmpty())try {row.put("machinery",new tools.jackson.databind.ObjectMapper().readValue(profiles.getFirst(),Map.class));}
      catch(tools.jackson.core.JacksonException e){throw new IllegalStateException("Invalid machinery profile",e);}
    }
    var channels = channels(tenant, id);
    Instant sampled = null;
    int freshChannels = 0, missingChannels = 0;
    long threshold = Math.max(180, ((Number) row.get("intervalSeconds")).longValue() * 3);
    for (var channel : channels) {
      String metric = channel.get("metric").toString();
      var spec = MetricCatalog.get(metric);
      channel.put("name", spec.name());
      channel.put("unit", spec.unit());
      channel.put("min", spec.min());
      channel.put("max", spec.max());
      var latest = latest(tenant, id, metric);
      channel.put("latest", latest);
      Instant at = latest == null ? null : app.zhinong.database.DatabaseTime.offset(latest.get("time")).toInstant();
      var quality = quality(latest, metric, row.get("protocol").toString(), row.get("lifecycle").toString(), ((Number) row.get("intervalSeconds")).intValue(), now);
      channel.put("freshness", quality.freshness());
      channel.put("reason", quality.reason());
      channel.put("freshnessSeconds", quality.thresholdSeconds());
      if ("FRESH".equals(channel.get("freshness"))) freshChannels++;
      if (latest == null) missingChannels++;
      if (at != null && (sampled == null || at.isAfter(sampled))) sampled = at;
    }
    row.put("channels", channels);
    row.put("lastSampledAt", sampled);
    String freshness = freshChannels > 0 ? "FRESH" : sampled == null ? "NO_DATA" : "STALE";
    if (!"ACTIVE".equals(row.get("lifecycle"))) freshness = row.get("lifecycle").toString();
    row.put("freshness", freshness);
    row.put("freshChannelCount", freshChannels);
    row.put("missingChannelCount", missingChannels);
    row.put("dataQuality", missingChannels == channels.size() ? "NO_DATA" : freshChannels == channels.size() ? "COMPLETE" : freshChannels == 0 ? "STALE" : "PARTIAL");
    Instant received = row.get("lastReceivedAt") == null ? null : ((OffsetDateTime) row.get("lastReceivedAt")).toInstant();
    row.put("connectionState", freshness(received, threshold, now));
    row.put("positioned", "WGS84".equals(row.get("locationMode")) || row.get("planX") != null);
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

  private static String freshness(Instant at, long threshold, Instant now) {
    return at == null ? "NO_DATA" : !at.isAfter(now) && at.isAfter(now.minusSeconds(threshold)) ? "FRESH" : "STALE";
  }

  public record ReadingQuality(String freshness, String reason, long thresholdSeconds, Long ageSeconds) {
    public boolean fresh() { return "FRESH".equals(freshness); }
  }

  /** Device cards, agronomy and decision evidence use the same configured-channel validity rules. */
  public static ReadingQuality quality(Map<String,Object> latest, String metric, String protocol, String lifecycle, int interval, Instant now) {
    long threshold = DeviceAlertMonitor.thresholdSeconds(interval);
    if (latest == null) return new ReadingQuality("NO_DATA", "没有观测记录", threshold, null);
    Instant at = DatabaseTime.instant(latest.get("time"));
    long age = Duration.between(at, now).getSeconds();
    if (!"ACTIVE".equals(lifecycle)) return new ReadingQuality(lifecycle, "设备非在用状态", threshold, age);
    if (!Objects.equals(protocol, latest.get("source"))) return new ReadingQuality("STALE", "采集来源与配置不一致", threshold, age);
    if (at.isAfter(now)) return new ReadingQuality("STALE", "观测时间晚于当前时间", threshold, age);
    if (!at.isAfter(now.minusSeconds(threshold))) return new ReadingQuality("STALE", "观测已过期", threshold, age);
    try { MetricCatalog.validate(metric, new BigDecimal(latest.get("value").toString())); }
    catch (ApiException | NumberFormatException e) { return new ReadingQuality("STALE", "读数超出指标允许范围", threshold, age); }
    return new ReadingQuality("FRESH", "有效", threshold, age);
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

  /**
   * Bound each indexed source before merging. Sorting the complete UNION history once per
   * channel made device lists and every monitoring pass grow with retention. The telemetry
   * ORDER BY repeats the equality columns so H2 reads ix_telemetry_latest in index order and
   * stops at the first row; this runs per channel while ingestion holds the device lock.
   */
  String latestSql() {
    return ("""
      SELECT * FROM (
        (SELECT id AS "recordId",'telemetry_readings' AS "recordStore",2 AS "recordPriority",measured_at AS "time",measured_value AS "value",source AS "source",received_at AS "receivedAt",received_at AS "orderingAt"
         FROM telemetry_readings%s WHERE tenant_id=? AND device_id=? AND metric=?
         ORDER BY tenant_id,device_id,metric,measured_at DESC,received_at DESC,id DESC LIMIT 1)
        UNION ALL
        (SELECT o.id AS "recordId",'observations' AS "recordStore",1 AS "recordPriority",%s AS "time",o.measured_value AS "value",o.source AS "source",o.received_at AS "receivedAt",COALESCE(o.received_at,%s) AS "orderingAt"
         FROM observations o JOIN devices d ON d.tenant_id=o.tenant_id AND d.id=o.device_id
         WHERE o.tenant_id=? AND o.device_id=? AND d.metric=?
         ORDER BY o.measured_at DESC,COALESCE(o.received_at,%s) DESC,o.id DESC LIMIT 1)
      ) r ORDER BY "time" DESC,"orderingAt" DESC,"recordPriority" DESC,"recordId" DESC LIMIT 1
      """).formatted(store.sql().useIndex("ix_telemetry_latest"), store.sql().observationTime("o.measured_at"), store.sql().observationTime("o.measured_at"),store.sql().observationTime("o.measured_at"));
  }

  public Map<String, Object> latest(String tenant, String id, String metric) {
    var rows = db.queryForList(
      latestSql(),
      tenant,
      id,
      metric,
      tenant,
      id,
      metric
    );
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    row.put("time", DatabaseTime.utc(row.get("time")));
    row.put("receivedAt", DatabaseTime.utc(row.get("receivedAt")));
    row.put("receivedAtKnown",row.get("receivedAt")!=null);
    row.remove("orderingAt"); row.remove("recordPriority");
    return row;
  }

  /** One indexed seek per configured channel inside SQL, then bounded batch fetches across both stores. */
  public List<Map<String,Object>> farmReadings(String tenant, String farm, Set<String> metrics) {
    return farmReadings(tenant,farm,metrics,null);
  }

  public List<Map<String,Object>> farmReadings(String tenant, String farm, Set<String> metrics, OffsetDateTime before) {
    if (metrics.isEmpty()) return List.of();
    var args = new ArrayList<Object>();if(before!=null){args.add(before);args.add(before);} args.add(tenant); args.add(farm); args.addAll(metrics.stream().sorted().toList());
    var scoped = db.queryForList("""
      SELECT d.id AS "deviceId",d.name AS "deviceName",a.plot_id AS "plotId",p.name AS "plotName",
        a.protocol AS "protocol",a.lifecycle AS "lifecycle",a.interval_seconds AS "intervalSeconds",
        a.last_received_at AS "lastReceivedAt",c.metric AS "metric",
        (SELECT n.id FROM telemetry_readings n%s WHERE n.tenant_id=d.tenant_id AND n.device_id=d.id AND n.metric=c.metric %s
          ORDER BY n.tenant_id,n.device_id,n.metric,n.measured_at DESC,n.received_at DESC,n.id DESC LIMIT 1) AS "readingId",
        (SELECT o.id FROM observations o WHERE o.tenant_id=d.tenant_id AND o.device_id=d.id AND d.metric=c.metric %s
          ORDER BY o.measured_at DESC,COALESCE(o.received_at,%s) DESC,o.id DESC LIMIT 1) AS "observationId"
      FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
      JOIN device_channels c ON c.tenant_id=d.tenant_id AND c.device_id=d.id
      LEFT JOIN plots p ON p.tenant_id=a.tenant_id AND p.id=a.plot_id AND p.farm_id=d.farm_id
      WHERE d.tenant_id=? AND d.farm_id=? AND c.metric IN (%s) ORDER BY d.name,d.id,c.metric
      """.formatted(store.sql().useIndex("ix_telemetry_latest"),before==null?"":"AND n.measured_at<?",before==null?"":"AND "+store.sql().observationTime("o.measured_at")+"<?",store.sql().observationTime("o.measured_at"), String.join(",", Collections.nCopies(metrics.size(), "?"))), args.toArray());
    var modern = batchReadings(tenant, scoped.stream().map(r -> (String) r.get("readingId")).filter(Objects::nonNull).toList(), false);
    var legacy = batchReadings(tenant, scoped.stream().map(r -> (String) r.get("observationId")).filter(Objects::nonNull).toList(), true);
    for (var row : scoped) {
      var current = modern.get(row.remove("readingId"));
      var old = legacy.get(row.remove("observationId"));
      if (old != null && (current == null || later(old, current))) current = old;
      row.put("latest", current);
    }
    return scoped;
  }

  private Map<String,Map<String,Object>> batchReadings(String tenant, List<String> ids, boolean legacy) {
    var result = new HashMap<String,Map<String,Object>>();
    for (int from=0; from<ids.size(); from+=500) {
      var batch=ids.subList(from, Math.min(ids.size(),from+500));
      var args=new ArrayList<Object>(); args.add(tenant); args.addAll(batch);
      String at=legacy?store.sql().observationTime("measured_at"):"measured_at";
      var rows=db.queryForList("SELECT id AS \"recordId\",'"+(legacy?"observations":"telemetry_readings")+"' AS \"recordStore\","+
        at+" AS \"time\",measured_value AS \"value\",source AS \"source\",received_at AS \"receivedAt\" FROM "+
        (legacy?"observations":"telemetry_readings")+" WHERE tenant_id=? AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray());
      for(var row:rows) {
        row.put("time",DatabaseTime.utc(row.get("time"))); row.put("receivedAt",DatabaseTime.utc(row.get("receivedAt")));
        row.put("receivedAtKnown",row.get("receivedAt")!=null);
        result.put(row.get("recordId").toString(),row);
      }
    }
    return result;
  }

  private boolean later(Map<String,Object> candidate, Map<String,Object> current) {
    int time=DatabaseTime.instant(candidate.get("time")).compareTo(DatabaseTime.instant(current.get("time")));
    if(time!=0)return time>0;
    int receipt=DatabaseTime.instant(candidate.get("receivedAt")==null?candidate.get("time"):candidate.get("receivedAt")).compareTo(DatabaseTime.instant(current.get("receivedAt")==null?current.get("time"):current.get("receivedAt")));
    // On an exact cross-store tie the explicit telemetry row wins, matching latestSql().
    return receipt>0;
  }

  public record ScopedHistory(String sql, List<Object> arguments) {}

  /** Bounded canonical samples for farm aggregates: configured channels, one final correction per observation time. */
  public ScopedHistory farmHistory(String tenant,String farm,Set<String> metrics,OffsetDateTime start,OffsetDateTime end) {
    String filter=metrics.isEmpty()?"":" AND c.metric IN ("+String.join(",",Collections.nCopies(metrics.size(),"?"))+")";
    String oldTime=store.sql().observationTime("o.measured_at");
    String columns="d.tenant_id,d.id AS device_id,d.farm_id,a.plot_id,c.metric,a.protocol,a.lifecycle,a.interval_seconds,a.device_type,c.lower_limit,c.upper_limit,";
    String scope=" FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id ";
    String sql="SELECT * FROM (SELECT h.*,ROW_NUMBER() OVER(PARTITION BY device_id,metric,measured_at ORDER BY ordering_at DESC,record_priority DESC,record_id DESC) AS sample_rank FROM ("+
      "SELECT "+columns+"n.id AS record_id,2 AS record_priority,n.measured_value,n.measured_at,n.received_at,n.received_at AS ordering_at,n.source"+scope+
      "JOIN device_channels c ON c.tenant_id=d.tenant_id AND c.device_id=d.id JOIN telemetry_readings n"+store.sql().useIndex("ix_telemetry_history")+
      " ON n.tenant_id=d.tenant_id AND n.device_id=d.id AND n.metric=c.metric WHERE d.tenant_id=? AND d.farm_id=? AND n.measured_at>=? AND n.measured_at<=?"+filter+
      " UNION ALL SELECT "+columns+"o.id AS record_id,1 AS record_priority,o.measured_value,"+oldTime+" AS measured_at,o.received_at,COALESCE(o.received_at,"+oldTime+") AS ordering_at,o.source"+scope+
      "JOIN device_channels c ON c.tenant_id=d.tenant_id AND c.device_id=d.id AND c.metric=d.metric JOIN observations o ON o.tenant_id=d.tenant_id AND o.device_id=d.id "+
      "WHERE d.tenant_id=? AND d.farm_id=? AND "+oldTime+">=? AND "+oldTime+"<=?"+filter+
      ") h) ranked WHERE sample_rank=1 AND "+MetricCatalog.validSampleSql("metric","measured_value");
    var args=new ArrayList<Object>();
    for(int i=0;i<2;i++){args.add(tenant);args.add(farm);args.add(start);args.add(end);args.addAll(metrics.stream().sorted().toList());}
    return new ScopedHistory(sql,List.copyOf(args));
  }

  private String historyUnion() {
    return ("""
    SELECT "time","value","source","receivedAt" FROM (
      SELECT h.*,ROW_NUMBER() OVER(PARTITION BY "time" ORDER BY "orderingAt" DESC,"priority" DESC,"recordId" DESC) AS sample_rank FROM (
        SELECT id AS "recordId",2 AS "priority",measured_at AS "time",measured_value AS "value",source AS "source",received_at AS "receivedAt",received_at AS "orderingAt"
        FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=? AND measured_at>=? AND measured_at<=?
        UNION ALL
        SELECT o.id AS "recordId",1 AS "priority",%s AS "time",o.measured_value AS "value",o.source AS "source",o.received_at AS "receivedAt",COALESCE(o.received_at,%s) AS "orderingAt"
        FROM observations o JOIN devices d ON d.tenant_id=o.tenant_id AND d.id=o.device_id
        WHERE o.tenant_id=? AND o.device_id=? AND d.metric=? AND %s>=? AND %s<=?
      ) h
    ) ranked WHERE sample_rank=1 AND "source"=(SELECT protocol FROM asset_profiles a WHERE a.tenant_id=? AND a.device_id=?)
    """).formatted(store.sql().observationTime("o.measured_at"), store.sql().observationTime("o.measured_at"),store.sql().observationTime("o.measured_at"),store.sql().observationTime("o.measured_at"));
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
    var end = clock.now();
    var start = end.minusHours(hours);
    String bucket = store.sql().bucket("\"time\"", hours <= 24);
    var points = db.queryForList(
      "SELECT " + bucket + " AS \"time\"," +
        "AVG(\"value\") AS \"value\",MIN(\"value\") AS \"min\",MAX(\"value\") AS \"max\"," +
        "COUNT(*) AS \"samples\" FROM (" +
        historyUnion() +
        ") r " +
        "GROUP BY " + bucket + " ORDER BY \"time\"",
      Identity.tenant(),
      id,
      metric,
      start,
      end,
      Identity.tenant(),
      id,
      metric,
      start,
      end,
      Identity.tenant(),
      id
    );
    points.forEach(p -> p.put("time", DatabaseTime.utc(p.get("time"))));
    var sources = db.queryForList(
      "SELECT \"source\",COUNT(*) AS \"count\" FROM (" +
        historyUnion() +
        ") r GROUP BY \"source\"",
      Identity.tenant(),
      id,
      metric,
      start,
      end,
      Identity.tenant(),
      id,
      metric,
      start,
      end,
      Identity.tenant(),
      id
    );
    return Map.of(
      "metric",
      MetricCatalog.get(metric),
      "hours",
      hours,
      "aggregation",
      hours <= 24 ? "MINUTE" : "HOUR",
      "points",
      points,
      "sources",
      sources,
      "samplePolicy",
      "当前接入协议；同一观测时刻保留最后接收修正；未来观测不纳入。此设备诊断图保留物理范围异常值，农场与 AI 汇总排除异常值；原始记录保留用于追溯。"
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
      if (input.locationMode() == null && "WGS84".equals(old.get("locationMode"))) {
        throw new ApiException(409, "该设备已有 WGS84 安装位置，请明确选择点位坐标系后保存");
      }
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
      if (!old.get("protocol").equals(input.protocol()) || !old.get("deviceType").equals(input.deviceType())
          || !"ACTIVE".equals(input.lifecycle()) || Boolean.FALSE.equals(input.controlEnabled())) {
        DeviceCommandService.cancelPending(db, Identity.tenant(), id, "设备配置变更，原指令失效");
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
      model=?,notes=?,interval_seconds=?,location_mode=?,latitude=?,longitude=?,
      control_enabled=COALESCE(?,control_enabled),revision=revision+1 WHERE tenant_id=? AND device_id=?
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
      AssetLocation.mode(input.locationMode()),
      input.latitude(),
      input.longitude(),
      Set.of("GATE", "PUMP").contains(input.deviceType()) && !"MANUAL".equals(input.protocol()) ? input.controlEnabled() : Boolean.FALSE,
      Identity.tenant(),
      id
    );
    for (var channel : input.channels())
      db.update(
        store.sql().upsert("device_channels", "tenant_id,device_id,metric", "tenant_id,device_id,metric,lower_limit,upper_limit"),
        Identity.tenant(),
        id,
        channel.metric(),
        channel.lowerLimit(),
        channel.upperLimit()
      );
    cameras.save(Identity.tenant(), id, input.deviceType(), input.camera());
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
    cameras.validate(input.deviceType(), input.camera());
    if (
      !MetricCatalog.TYPES.containsKey(input.deviceType()) ||
      !MetricCatalog.PROTOCOLS.containsKey(input.protocol())
    ) {
      throw new ApiException(400, "不支持的设备类型或接入方式");
    }
    AssetLocation.validate(input.locationMode(), input.planX(), input.planY(), input.latitude(), input.longitude());
    if (Boolean.TRUE.equals(input.controlEnabled()) &&
        (!Set.of("GATE", "PUMP").contains(input.deviceType()) || "MANUAL".equals(input.protocol()))) {
      throw new ApiException(400, "仅模拟或 HTTP 接入的闸门、泵房可以启用控制");
    }
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
    if (Boolean.TRUE.equals(input.controlEnabled())) {
      var required = input.deviceType().equals("GATE")
        ? Set.of("GATE_OPENING", "REMOTE_ENABLED", "FAULT")
        : Set.of("PUMP_RUNNING", "STANDBY_RUNNING", "PUMP_FREQUENCY", "REMOTE_ENABLED", "FAULT");
      if (!seen.containsAll(required)) throw new ApiException(400, "启用控制前需配置实际反馈、远程模式及故障指标，请应用设备指标模板");
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
