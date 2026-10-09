package app.zhinong.bootstrap;

import app.zhinong.workspace.TelemetryService;
import app.zhinong.workspace.MetricCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Additive, fictional business history for the explicitly selected demo-a control farm. */
@Service
public class OperatingDemoData {
  private static final String NOTE = "虚构经营演示；用于流程展示，不代表实际生产或农艺处方。";
  private final JdbcTemplate db;
  private final TelemetryService telemetry;
  private final boolean enabled;

  public OperatingDemoData(JdbcTemplate db, TelemetryService telemetry,
      @Value("${farm.demo:false}") boolean demo, @Value("${farm.demo-rich:true}") boolean rich) {
    this.db = db;
    this.telemetry = telemetry;
    this.enabled = demo && rich;
  }

  @Transactional
  public void initialize() {
    if (!enabled) return;
    for (var farm : db.queryForList("""
      SELECT f.tenant_id,f.id FROM farms f
      JOIN tenants t ON t.id=f.tenant_id
      JOIN farm_profiles p ON p.tenant_id=f.tenant_id AND p.farm_id=f.id
      WHERE t.code='demo-a' AND t.enabled=TRUE AND p.demo=TRUE AND f.name='青禾设备联动演示场'
      """)) {
      String tenant = farm.get("TENANT_ID").toString(), id = farm.get("ID").toString();
      // Serializes startup/day seeding without changing a user's saved records.
      db.queryForList("SELECT id FROM farms WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id);
      if (db.queryForObject("SELECT COUNT(*) FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?",
          Integer.class, tenant, id) > 0) continue;
      // Existing vendor/demo or user records must not receive a second synthetic history.
      boolean occupied = false;
      for (String table : List.of("plantings", "farm_tasks", "production", "field_issues")) {
        if (db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND plot_id IN (SELECT id FROM plots WHERE tenant_id=? AND farm_id=?)",
            Integer.class, tenant, tenant, id) > 0) { occupied = true; break; }
      }
      if (occupied) continue;
      var plots = plots(tenant, id);
      var crew = crew(tenant);
      if (plots.size() < 3 || crew.isEmpty()) continue;
      LocalDate today = LocalDate.now();
      seedHistory(tenant, plots, crew, today);
      seedCurrent(tenant, plots, crew, today);
      seedMonitoringHistory(tenant, id);
      db.update("INSERT INTO demo_operating_farms(tenant_id,farm_id,seeded_on,last_daily_date) VALUES(?,?,?,?)",
        tenant, id, today, today);
      db.update("INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,'DEMO_SCENARIO','DEMO_OPERATING_SEEDED',?,?)",
        id(), tenant, id, LocalDateTime.now());
    }
  }

  /** Called only by the opt-in demo runner; no clock shifting, completion, or cloud calls. */
  @Transactional
  public void tick() {
    if (!enabled) return;
    for (var farm : db.queryForList("""
      SELECT r.tenant_id,r.farm_id FROM demo_operating_farms r
      JOIN tenants t ON t.id=r.tenant_id AND t.enabled=TRUE
      JOIN farm_profiles p ON p.tenant_id=r.tenant_id AND p.farm_id=r.farm_id AND p.demo=TRUE
      WHERE t.code='demo-a'
      """)) {
      String tenant = farm.get("TENANT_ID").toString(), id = farm.get("FARM_ID").toString();
      var registry = db.queryForMap("SELECT last_daily_date FROM demo_operating_farms WHERE tenant_id=? AND farm_id=? FOR UPDATE", tenant, id);
      LocalDate today = LocalDate.now();
      LocalDate last = ((java.sql.Date) registry.get("LAST_DAILY_DATE")).toLocalDate();
      var crew = crew(tenant);
      var plots = plots(tenant, id);
      if (last.isBefore(today) && !crew.isEmpty() && !plots.isEmpty()) {
        daily(tenant, plots, crew, today);
        db.update("UPDATE demo_operating_farms SET last_daily_date=? WHERE tenant_id=? AND farm_id=?", today, tenant, id);
      }
      telemetry.collectOperatingDemo(tenant, id);
    }
  }

  List<Map<String, Object>> plots(String tenant, String farm) {
    return db.queryForList("SELECT id,name,area_mu,crop FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY name,id", tenant, farm);
  }

  List<String> crew(String tenant) {
    return db.queryForList("""
      SELECT id FROM members WHERE tenant_id=? AND enabled=TRUE AND role IN ('ADMIN','OPERATOR')
        AND EXISTS(SELECT 1 FROM members manager WHERE manager.tenant_id=? AND manager.enabled=TRUE AND manager.role='ADMIN')
      ORDER BY role DESC,username
      """, String.class, tenant, tenant);
  }

  private void seedHistory(String tenant, List<Map<String, Object>> plots, List<String> crew, LocalDate today) {
    for (int p = 0; p < 3; p++) {
      var plot = plots.get(p);
      String plotId = plot.get("ID").toString();
      String actor = crew.get(p % crew.size());
      if (p < 2) {
        planting(tenant, plot, today.minusDays(350), today.minusDays(205), "FINISHED", "往季水稻演示品种");
        planting(tenant, plot, today.minusDays(140), today.plusDays(20), "ACTIVE", "当季水稻演示品种");
        harvest(tenant, plot, actor, today.minusDays(205), 515 + p * 12, "往季水稻收获入库");
      } else {
        for (int cycle = 6; cycle >= 1; cycle--) {
          LocalDate start = today.minusDays(cycle * 36L + 10), end = start.plusDays(35);
          planting(tenant, plot, start, end, "FINISHED", "叶菜轮作演示品种");
          for (int batch = 0; batch < 3; batch++) {
            harvest(tenant, plot, actor, end.minusDays(4 - batch * 2L), 95 + cycle * 3 + batch * 8,
              "叶菜第" + (7 - cycle) + "茬第" + (batch + 1) + "批分批采收");
          }
        }
        planting(tenant, plot, today.minusDays(10), today.plusDays(25), "ACTIVE", "当期叶菜演示品种");
      }
      String[] types = {"INSPECTION", "IRRIGATION", "FERTILIZING", "PROTECTION"};
      String[] titles = {"巡田与长势记录", "灌溉与水位复核", "追肥与田间记录", "虫情巡查及防护作业"};
      for (int week = 12; week >= 1; week--) {
        int kind = week % types.length;
        LocalDate day = today.minusDays(week * 10L + p);
        String task = task(tenant, plot, actor, titles[kind], types[kind], day, "COMPLETED", "MANUAL", "",
          "已完成当期作业，记录面积、现场情况和复查结果。", day.atTime(16, 0));
        if (week == 2) issue(tenant, plotId, actor, task, "WATER", "NORMAL", "历史巡田发现局部缺水，已补灌复查。",
          "RESOLVED", day.minusDays(2).atTime(9, 0), crew.getLast(), day.plusDays(1).atTime(10, 0));
      }
    }
  }

  private void planting(String tenant, Map<String, Object> plot, LocalDate start, LocalDate end, String status, String variety) {
    // Existing user-entered planting periods take precedence over illustrative fixtures.
    if (db.queryForObject("SELECT COUNT(*) FROM plantings WHERE tenant_id=? AND plot_id=? AND start_date<=? AND end_date>=?",
        Integer.class, tenant, plot.get("ID"), end, start) > 0) return;
    db.update("INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
      id(), tenant, plot.get("ID"), plot.get("CROP"), variety, plot.get("AREA_MU"), start, end, status);
  }

  private void harvest(String tenant, Map<String, Object> plot, String actor, LocalDate day, double kgPerMu, String title) {
    BigDecimal yield = ((BigDecimal) plot.get("AREA_MU")).multiply(BigDecimal.valueOf(kgPerMu));
    task(tenant, plot, actor, title, "HARVEST", day, "COMPLETED", "MANUAL", "",
      "已称重入库 " + yield.toPlainString() + " kg；对应同日生产记录。", day.atTime(16, 0));
    db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",
      id(), tenant, plot.get("ID"), day, yield, title + "。" + NOTE);
  }

  void seedCurrent(String tenant, List<Map<String, Object>> plots, List<String> crew, LocalDate today) {
    var rice = plots.get(0); var second = plots.get(1); var vegetables = plots.get(2);
    String operator = crew.getFirst(), manager = crew.getLast();
    task(tenant, rice, operator, "复核一分区灌溉水位", "IRRIGATION", today.minusDays(2), "PENDING", "MANUAL", "", "", null);
    String running = task(tenant, second, operator, "二分区虫情巡检与人工处理", "PROTECTION", today, "RUNNING", "MANUAL", "", "", null);
    String blocked = task(tenant, vegetables, operator, "叶菜灌溉支管检查与补灌", "IRRIGATION", today, "RUNNING", "MANUAL",
      "演示：支管接头渗漏，等待备件到位，需管理员协调。", "", null);
    task(tenant, vegetables, manager, "安排下一批叶菜采收与周转筐", "HARVEST", today.plusDays(20), "PENDING", "UNCONFIRMED", "", "", null);
    task(tenant, rice, manager, "水稻收获前成熟度抽样", "INSPECTION", today.plusDays(7), "PENDING", "MANUAL", "", "", null);
    task(tenant, second, manager, "协调后续机械收获服务", "HARVEST", today.plusDays(20), "PENDING", "SERVICE", "", "", null);
    String completed = task(tenant, rice, operator, "清理沟渠并完成水位复测", "IRRIGATION", today.minusDays(1), "COMPLETED", "MANUAL", "",
      "沟渠已清理、完成复测，等待农场主现场复核。", today.minusDays(1).atTime(16, 0));
    task(tenant, second, manager, "取消重复巡查安排", "INSPECTION", today.minusDays(4), "CANCELLED", "MANUAL", "",
      "演示：与当日巡查合并，保留取消记录。", null);
    issue(tenant, second.get("ID").toString(), operator, running, "PEST", "HIGH", "局部叶片虫情需跟进，已安排巡检处理。", "ASSIGNED", today.minusDays(1).atTime(9, 0), null, null);
    issue(tenant, vegetables.get("ID").toString(), operator, blocked, "EQUIPMENT", "HIGH", "灌溉支管接头渗漏，需备件协调。", "ASSIGNED", today.minusDays(1).atTime(10, 0), null, null);
    issue(tenant, rice.get("ID").toString(), operator, completed, "WATER", "NORMAL", "沟渠排水缓慢，作业已完成，待复核关闭。", "ASSIGNED", today.minusDays(3).atTime(9, 0), null, null);
    issue(tenant, vegetables.get("ID").toString(), operator, null, "OTHER", "NORMAL", "下一批周转筐需要补充，等待安排。", "OPEN", today.minusDays(1).atTime(11, 0), null, null);
    daily(tenant, plots, crew, today);
  }

  private void daily(String tenant, List<Map<String, Object>> plots, List<String> crew, LocalDate today) {
    task(tenant, plots.getFirst(), crew.getFirst(), "每日水位与长势巡查 · " + today, "INSPECTION", today, "PENDING", "MANUAL", "", "", null);
    if (plots.size() > 2) task(tenant, plots.get(2), crew.getFirst(), "每日叶菜墒情巡查 · " + today, "INSPECTION", today, "PENDING", "MANUAL", "", "", null);
  }

  private void seedMonitoringHistory(String tenant, String farm) {
    var channels = db.queryForList("""
      SELECT d.id,c.metric FROM devices d
      JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id
      JOIN device_channels c ON c.tenant_id=d.tenant_id AND c.device_id=d.id
      WHERE d.tenant_id=? AND d.farm_id=? AND p.protocol='SIMULATED' AND p.code LIKE 'DEMO-CTRL-%'
        AND p.device_type IN ('WEATHER','SOIL','WATER','PEST')
      """, tenant, farm);
    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0);
    for (var channel : channels) {
      String device = channel.get("ID").toString(), metric = channel.get("METRIC").toString();
      var spec = MetricCatalog.get(metric);
      var rows = new ArrayList<Object[]>();
      for (int hour = 14 * 24; hour >= 1; hour--) {
        double value = spec.normal() + Math.sin(hour / 5.0) * Math.max(0.2, Math.abs(spec.normal()) * 0.08);
        value = Math.max(spec.min(), Math.min(spec.max(), value));
        if (MetricCatalog.discrete(metric)) value = Math.round(value);
        var at = end.minusHours(hour);
        rows.add(new Object[]{id(), tenant, device, metric, BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP),
          at, at, tenant, device, metric, at});
      }
      db.batchUpdate("""
        INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source)
        SELECT ?,?,?,?,?,?,?,'SIMULATED' WHERE NOT EXISTS
          (SELECT 1 FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=? AND measured_at=?)
        """, rows);
    }
  }

  String task(String tenant, Map<String, Object> plot, String actor, String title, String type,
      LocalDate day, String status, String method, String blocked, String completion, LocalDateTime completed) {
    String id = id();
    BigDecimal area = status.equals("COMPLETED") ? (BigDecimal) plot.get("AREA_MU") : BigDecimal.ZERO;
    db.update("INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,?,?,?,?,?)",
      id, tenant, plot.get("ID"), title, type, day, status, NOTE);
    db.update("INSERT INTO task_fieldwork(tenant_id,task_id,assignee_id,method,blocked_reason,completion_note,actual_area_mu,completed_at) VALUES(?,?,?,?,?,?,?,?)",
      tenant, id, actor, method, blocked, completion, area, completed);
    LocalDate plannedDay = day.minusDays(3);
    if (plannedDay.isAfter(LocalDate.now().minusDays(1))) plannedDay = LocalDate.now().minusDays(1);
    LocalDateTime planned = plannedDay.atTime(8, 0);
    String planner = db.queryForObject("SELECT id FROM members WHERE tenant_id=? AND enabled=TRUE AND role='ADMIN' ORDER BY username DESC LIMIT 1", String.class, tenant);
    log(tenant, id, planner, "PLANNED", NOTE, method, BigDecimal.ZERO, planned);
    if (status.equals("COMPLETED") || status.equals("RUNNING")) {
      log(tenant, id, actor, "RUNNING", "按计划开始演示作业。", method, BigDecimal.ZERO,
        (status.equals("COMPLETED") ? day : day.minusDays(1)).atTime(8, 0));
    }
    if (completed != null) log(tenant, id, actor, "COMPLETED", completion, method, area, completed);
    if (!blocked.isBlank()) log(tenant, id, actor, "BLOCKED", blocked, method, BigDecimal.ZERO, day.minusDays(1).atTime(15, 0));
    if (status.equals("CANCELLED")) log(tenant, id, actor, "CANCELLED", completion, method, BigDecimal.ZERO, day.atTime(9, 0));
    return id;
  }

  private void log(String tenant, String task, String actor, String action, String note, String method, BigDecimal area, LocalDateTime at) {
    db.update("INSERT INTO field_work_logs(id,tenant_id,task_id,actor_id,action,note,method,actual_area_mu,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
      id(), tenant, task, actor, action, note, method, area, at);
  }

  void issue(String tenant, String plot, String actor, String task, String category, String severity,
      String description, String status, LocalDateTime created, String reviewer, LocalDateTime resolved) {
    db.update("INSERT INTO field_issues(id,tenant_id,plot_id,category,severity,description,reporter_id,status,task_id,created_at,review_note,reviewed_by,resolved_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
      id(), tenant, plot, category, severity, description + "（虚构演示）", actor, status, task, created,
      resolved == null ? null : "虚构复核：已完成现场复查，问题关闭。", reviewer, resolved);
  }

  private static String id() { return UUID.randomUUID().toString(); }
}
