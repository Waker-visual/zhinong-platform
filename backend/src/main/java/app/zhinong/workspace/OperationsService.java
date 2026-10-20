package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.FarmClock;
import app.zhinong.business.Store;
import app.zhinong.database.DatabaseTime;
import app.zhinong.security.Identity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 农场运行概览：设备上报健康度时间片、土壤水分均值、地块逐日墒情与作业流水线。
 * 只读；每条查询与关联都带当前租户条件，农场先经租户校验。
 */
@Service
@Transactional(readOnly = true)
public class OperationsService {

  static final int SLICES = 100;
  static final int MATRIX_DAYS = 28;
  static final String MOISTURE = "SOIL_MOISTURE";

  private final Store store;
  private final FarmClock clock;
  private final AssetService assets;

  public OperationsService(Store store, FarmClock clock, AssetService assets) {
    this.store = store;
    this.clock = clock;
    this.assets = assets;
  }

  /** 与设备台账“正常上报”同一口径：最近一次上报在间隔的 3 倍以内，至少 3 分钟。 */
  static long tolerance(Number interval) {
    return Math.max(180, interval.longValue() * 3);
  }

  public Map<String, Object> overview(String farmId, int hours) {
    if (hours < 24 || hours > 720) throw new ApiException(400, "统计范围为 24–720 小时");
    store.get("farms", farmId);
    String tenant = Identity.tenant();
    var db = store.db();
    // Both supported databases retain microseconds; align bucket boundaries to that precision.
    var end = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var start = end.minusHours(hours);
    // H2 TIMESTAMPDIFF compares wall-clock fields even for offset timestamps. Both
    // operands must use UTC, including legacy rows retaining the server's offset.
    var elapsedStart=start.withOffsetSameInstant(ZoneOffset.UTC);
    String elapsedReading=store.sql().mysql()?"r.measured_at":"(r.measured_at AT TIME ZONE 'UTC')";
    long sliceSeconds = hours * 3600L / SLICES;

    var devices = db.queryForList(
      """
      SELECT d.id AS "id",a.interval_seconds AS "interval"
      FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
      WHERE d.tenant_id=? AND d.farm_id=? AND a.lifecycle='ACTIVE'
      """,
      tenant,
      farmId
    );
    var reports = new HashMap<String, List<Instant>>();
    var metricCodes=new HashSet<String>();MetricCatalog.METRICS.forEach(m -> metricCodes.add(m.code()));
    // Select the canonical last correction before filtering its protocol, just as inside the window.
    for(var reading:assets.farmReadings(tenant,farmId,metricCodes,start)) {
      @SuppressWarnings("unchecked") var latest=(Map<String,Object>)reading.get("latest");
      if(latest==null || !"ACTIVE".equals(reading.get("lifecycle")) || !Objects.equals(reading.get("protocol"),latest.get("source")))continue;
      try {MetricCatalog.validate(reading.get("metric").toString(),new BigDecimal(latest.get("value").toString()));}
      catch(ApiException | NumberFormatException invalid) {continue;}
      var samples=reports.computeIfAbsent(reading.get("deviceId").toString(),k -> new ArrayList<>());
      Instant at=DatabaseTime.instant(latest.get("time"));
      if(samples.isEmpty())samples.add(at);else if(at.isAfter(samples.getFirst()))samples.set(0,at);
    }
    // For health at a slice's closing boundary only its last sample matters. Keep
    // at most 100 timestamps per device instead of every channel's historical rows.
    // Anchor tenant + farm on devices below so H2 can start with that small indexed
    // set; the tenant-equal joins then constrain every telemetry/asset read.
    var windowHistory=assets.farmHistory(tenant,farmId,Set.of(),start,end);
    var reportArgs=new ArrayList<Object>();reportArgs.add(elapsedStart);reportArgs.add(sliceSeconds*1_000_000L);reportArgs.addAll(windowHistory.arguments());
    db.query(("""
      SELECT r.device_id,MAX(r.measured_at),
        GREATEST(0,CEILING(TIMESTAMPDIFF(MICROSECOND,?,%s)/CAST(? AS DECIMAL(20,3)))-1) AS bucket
      FROM (%s) r WHERE r.lifecycle='ACTIVE' AND r.source=r.protocol
      GROUP BY r.device_id,bucket ORDER BY MAX(r.measured_at)
      """).formatted(elapsedReading,windowHistory.sql()),
      (RowCallbackHandler) rs ->
        reports
          .computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
          .add(DatabaseTime.instant(rs.getObject(2, java.time.OffsetDateTime.class))),
      reportArgs.toArray()
    );

    double[] moistureSum = new double[SLICES];
    long[] moistureCount = new long[SLICES];
    // Return at most 100 aggregates, rather than shipping every retained sample to Java.
    var moistureHistory=assets.farmHistory(tenant,farmId,Set.of(MOISTURE),start,end);
    var moistureArgs=new ArrayList<Object>();moistureArgs.add(elapsedStart);moistureArgs.add(sliceSeconds);moistureArgs.addAll(moistureHistory.arguments());
    db.query(("""
      SELECT FLOOR(TIMESTAMPDIFF(SECOND,?,%s)/?) AS bucket,SUM(r.measured_value),COUNT(*) FROM (%s) r
      WHERE r.source=r.protocol
      GROUP BY bucket
      """).formatted(elapsedReading,moistureHistory.sql()),
      (RowCallbackHandler) rs -> {
        int index = Math.min(SLICES - 1, Math.max(0, rs.getInt(1)));
        moistureSum[index] += rs.getBigDecimal(2).doubleValue();
        moistureCount[index] += rs.getLong(3);
      },
      moistureArgs.toArray()
    );

    var slices = new ArrayList<Map<String, Object>>();
    var cursor = new HashMap<String, Integer>();
    long healthSum = 0;
    int healthSlices = 0;
    for (int k = 0; k < SLICES; k++) {
      var sliceStart = start.plusSeconds(sliceSeconds * k);
      var sliceEnd = k == SLICES - 1 ? end : start.plusSeconds(sliceSeconds * (k + 1));
      int observed = 0, onTime = 0;
      for (var d : devices) {
        String id = (String) d.get("id");
        var times = reports.getOrDefault(id, List.of());
        int i = cursor.getOrDefault(id, -1);
        while (i + 1 < times.size() && !times.get(i + 1).isAfter(sliceEnd.toInstant())) i++;
        cursor.put(id, i);
        if (i < 0) continue;
        observed++;
        if (times.get(i).isAfter(sliceEnd.toInstant().minusSeconds(tolerance((Number) d.get("interval"))))) onTime++;
      }
      Integer health = observed == 0 ? null : Math.round(onTime * 100f / observed);
      if (health != null) {
        healthSum += health;
        healthSlices++;
      }
      var slice = new LinkedHashMap<String, Object>();
      slice.put("start", sliceStart.toString());
      slice.put("end", sliceEnd.toString());
      slice.put("health", health);
      slice.put("onTime", onTime);
      slice.put("observed", observed);
      slice.put(
        "moisture",
        moistureCount[k] == 0
          ? null
          : BigDecimal.valueOf(moistureSum[k] / moistureCount[k]).setScale(1, java.math.RoundingMode.HALF_UP)
      );
      slices.add(slice);
    }

    var freshIds=new HashSet<String>();var observedIds=new HashSet<String>();
    for(var reading:assets.farmReadings(tenant,farmId,metricCodes)) {
      if(!"ACTIVE".equals(reading.get("lifecycle")))continue;
      @SuppressWarnings("unchecked") var latest=(Map<String,Object>)reading.get("latest");
      String id=reading.get("deviceId").toString();if(latest!=null)observedIds.add(id);
      if(AssetService.quality(latest,reading.get("metric").toString(),reading.get("protocol").toString(),"ACTIVE",((Number)reading.get("intervalSeconds")).intValue(),end.toInstant()).fresh())freshIds.add(id);
    }
    int fresh=freshIds.size(),silent=devices.size()-observedIds.size();
    LocalDate today = clock.today();
    var tasks = db.queryForMap(
      """
      SELECT COUNT(*) AS "open",
        COUNT(CASE WHEN t.due_date<? THEN 1 END) AS "overdue",
        COUNT(CASE WHEN t.due_date>=? AND w.blocked_reason<>'' THEN 1 END) AS "blocked"
      FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
      LEFT JOIN task_fieldwork w ON w.tenant_id=t.tenant_id AND w.task_id=t.id
      WHERE t.tenant_id=? AND p.farm_id=? AND t.status IN ('PENDING','RUNNING')
      """,
      today,
      today,
      tenant,
      farmId
    );
    var issues = db.queryForMap(
      """
      SELECT COUNT(CASE WHEN i.status<>'RESOLVED' THEN 1 END) AS "open",MAX(i.created_at) AS "latest"
      FROM field_issues i JOIN plots p ON p.tenant_id=i.tenant_id AND p.id=i.plot_id
      WHERE i.tenant_id=? AND p.farm_id=?
      """,
      tenant,
      farmId
    );

    var summary = new LinkedHashMap<String, Object>();
    summary.put("health", healthSlices == 0 ? null : Math.round((float) healthSum / healthSlices));
    summary.put("currentHealth",devices.isEmpty()?null:Math.round(fresh*100f/devices.size()));
    summary.put("activeDevices", devices.size());
    summary.put("freshDevices", fresh);
    summary.put("silentDevices", silent);
    summary.put("openTasks", tasks.get("open"));
    summary.put("overdueTasks", tasks.get("overdue"));
    summary.put("blockedTasks", tasks.get("blocked"));
    summary.put("openIssues", issues.get("open"));
    summary.put("today", today.toString());

    var pipeline = new LinkedHashMap<String, Object>();
    pipeline.put("patrol", map("open", issues.get("open"), "latest", text(issues.get("latest"))));
    pipeline.put("irrigation", work(tenant, farmId, "IRRIGATION"));
    pipeline.put("protection", work(tenant, farmId, "PROTECTION"));
    var harvest = db.queryForMap(
      """
      SELECT COUNT(*) AS "records",MAX(r.record_date) AS "latest",COALESCE(SUM(r.yield_kg),0) AS "yieldKg"
      FROM production r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
      WHERE r.tenant_id=? AND p.farm_id=? AND r.record_date>=? AND r.record_date<=?
        AND NOT EXISTS(SELECT 1 FROM production_tracking x WHERE x.tenant_id=r.tenant_id AND x.production_id=r.id AND x.status='VOID')
      """,
      tenant,
      farmId,
      today.minusDays(29),
      today
    );
    var harvestWork = work(tenant, farmId, "HARVEST");
    pipeline.put(
      "harvest",
      map(
        "records",
        harvest.get("records"),
        "latest",
        text(harvest.get("latest")),
        "yieldKg",
        harvest.get("yieldKg"),
        "fromDate",
        today.minusDays(29),
        "toDate",
        today,
        "open",
        harvestWork.get("open")
      )
    );

    var result = new LinkedHashMap<String, Object>();
    result.put(
      "window",
      Map.of("hours", hours, "start", start.toString(), "end", end.toString(), "sliceMinutes", sliceSeconds / 60.0)
    );
    result.put("summary", summary);
    result.put("slices", slices);
    result.put("matrix", matrix(tenant, farmId, today));
    result.put("pipeline", pipeline);
    return result;
  }

  private Map<String, Object> work(String tenant, String farmId, String type) {
    var row = store
      .db()
      .queryForMap(
        """
        SELECT COUNT(CASE WHEN t.status IN ('PENDING','RUNNING') THEN 1 END) AS "open",
          COUNT(CASE WHEN t.status IN ('PENDING','RUNNING') AND w.blocked_reason<>'' THEN 1 END) AS "blocked",
          MAX(w.completed_at) AS "lastCompleted"
        FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
        LEFT JOIN task_fieldwork w ON w.tenant_id=t.tenant_id AND w.task_id=t.id
        WHERE t.tenant_id=? AND p.farm_id=? AND t.task_type=?
        """,
        tenant,
        farmId,
        type
      );
    return map(
      "open",
      row.get("open"),
      "blocked",
      row.get("blocked"),
      "lastCompleted",
      text(row.get("lastCompleted"))
    );
  }

  /**
   * 地块 × 日期：按每台设备配置的土壤水分上下限判定，当日任一读数越限即标出，与阈值告警的触发方式一致；
   * 一块地有多台设备时取最需要关注的结果。
   */
  private Map<String, Object> matrix(String tenant, String farmId, LocalDate today) {
    var db = store.db();
    ZoneId zone = clock.zone();
    LocalDate first = today.minusDays(MATRIX_DAYS - 1);
    // plot -> date -> device -> [min, max, lower, upper]
    var readings = new HashMap<String, Map<LocalDate, Map<String, double[]>>>();
    // Day cells follow the farm calendar on both engines, whatever the server or RDS session zone.
    String dayBucket = store.sql().calendarDate("r.measured_at", zone);
    var history=assets.farmHistory(tenant,farmId,Set.of(MOISTURE),first.atStartOfDay(zone).toOffsetDateTime(),clock.now());
    db.query(("""
      SELECT a.plot_id,r.device_id,%s,MIN(r.measured_value),MAX(r.measured_value),c.lower_limit,c.upper_limit
      FROM (%s) r
      JOIN devices d ON d.tenant_id=r.tenant_id AND d.id=r.device_id
      JOIN asset_profiles a ON a.tenant_id=r.tenant_id AND a.device_id=r.device_id
      LEFT JOIN device_channels c ON c.tenant_id=r.tenant_id AND c.device_id=r.device_id AND c.metric=r.metric
      WHERE a.plot_id IS NOT NULL AND r.source=r.protocol
      GROUP BY a.plot_id,r.device_id,%s,c.lower_limit,c.upper_limit
      """).formatted(dayBucket, history.sql(),dayBucket),
      (RowCallbackHandler) rs -> {
        LocalDate day = rs.getObject(3, LocalDate.class);
        var lower = rs.getBigDecimal(6);
        var upper = rs.getBigDecimal(7);
        readings
          .computeIfAbsent(rs.getString(1), k -> new HashMap<>())
          .computeIfAbsent(day, k -> new HashMap<>())
          .put(
            rs.getString(2),
              new double[] {
                rs.getBigDecimal(4).doubleValue(),
                rs.getBigDecimal(5).doubleValue(),
                lower == null ? Double.NaN : lower.doubleValue(),
                upper == null ? Double.NaN : upper.doubleValue(),
              }
          );
      },
      history.arguments().toArray()
    );
    // 地块上配置了土壤水分指标的设备数，用来区分“没布设监测”和“有设备但没读数”
    var sensors = new HashMap<String, Integer>();
    db.query(
      """
      SELECT a.plot_id,COUNT(*) FROM device_channels c
      JOIN devices d ON d.tenant_id=c.tenant_id AND d.id=c.device_id
      JOIN asset_profiles a ON a.tenant_id=c.tenant_id AND a.device_id=c.device_id
      WHERE c.tenant_id=? AND d.farm_id=? AND c.metric=? AND a.plot_id IS NOT NULL
      GROUP BY a.plot_id
      """,
      (RowCallbackHandler) rs -> sensors.put(rs.getString(1), rs.getInt(2)),
      tenant,
      farmId,
      MOISTURE
    );
    var days = new ArrayList<String>();
    for (int i = 0; i < MATRIX_DAYS; i++) days.add(first.plusDays(i).toString());
    var plots = new ArrayList<Map<String, Object>>();
    for (var plot : db.queryForList(
      """
      SELECT id AS "id",name AS "name",crop AS "crop",area_mu AS "areaMu"
      FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY name
      """,
      tenant,
      farmId
    )) {
      var byDay = readings.getOrDefault((String) plot.get("id"), Map.of());
      var cells = new ArrayList<String>();
      int low = 0, high = 0;
      for (int i = 0; i < MATRIX_DAYS; i++) {
        String state = "NONE";
        for (double[] v : byDay.getOrDefault(first.plusDays(i), Map.of()).values()) {
          String s = !Double.isNaN(v[2]) && v[0] < v[2]
            ? "LOW"
            : !Double.isNaN(v[3]) && v[1] > v[3]
              ? "HIGH"
              : Double.isNaN(v[2]) && Double.isNaN(v[3]) ? "UNSET" : "OK";
          state = worse(state, s);
        }
        if (state.equals("LOW")) low++;
        if (state.equals("HIGH")) high++;
        cells.add(state);
      }
      var row = new LinkedHashMap<String, Object>(plot);
      row.put("cells", cells);
      row.put("lowDays", low);
      row.put("highDays", high);
      row.put("sensors", sensors.getOrDefault((String) plot.get("id"), 0));
      plots.add(row);
    }
    return Map.of("days", days, "plots", plots);
  }

  private static final List<String> SEVERITY = List.of("NONE", "UNSET", "OK", "HIGH", "LOW");

  private static String worse(String a, String b) {
    return SEVERITY.indexOf(a) >= SEVERITY.indexOf(b) ? a : b;
  }

  private static String text(Object value) {
    if (value instanceof java.sql.Timestamp || value instanceof java.time.LocalDateTime) return DatabaseTime.utc(value).toString();
    return value == null ? null : value.toString();
  }

  /** 允许空值的有序映射（Map.of 不接受 null） */
  private static Map<String, Object> map(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }
}
