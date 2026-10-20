package app.zhinong;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

/** Run against an EMPTY isolated schema, never the application's configured database. */
@EnabledIfEnvironmentVariable(named="FARM_MYSQL_TEST_URL",matches="jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/zhinong_test_[A-Za-z0-9_]+.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "spring.sql.init.schema-locations=classpath:schema-mysql.sql",
  "spring.datasource.hikari.connection-init-sql=SET SESSION sql_mode = CONCAT(@@sql_mode, ',ANSI_QUOTES')",
  "farm.demo=true","farm.demo-rich=true","farm.research-history=true","farm.demo-live=false","farm.demo-portfolio=true",
  "farm.alerts.automation-enabled=false",
  "farm.weather.enabled=false",
  "farm.simulation.use-primary=true","farm.bootstrap-password=Test-Only-Password-429!"
})
class MySqlIntegrationTest extends ResearchScenarioContract {
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url",()->System.getenv("FARM_MYSQL_TEST_URL"));
    properties.add("spring.datasource.username",()->System.getenv("FARM_MYSQL_TEST_USER"));
    properties.add("spring.datasource.password",()->System.getenv("FARM_MYSQL_TEST_PASSWORD"));
  }

  @Test void mysqlRiskOverviewPrioritisesUrgentAlertsAndMergesLinkedFieldIssuesWithoutDoubleCounting() throws Exception {
    var f=isolatedFeatureFarm();var clock=context.getBean(app.zhinong.business.FarmClock.class);
    String device=call(f.admin(),"POST","/devices",Map.of("farmId",f.farm(),"name","虚构风险测点","metric","TEMPERATURE","adapter","SIMULATED"),200).path("ID").asText();
    String urgent=UUID.randomUUID().toString();
    String insert="INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at) VALUES(?,?,?,?,?,?,?,?)";
    db.update(insert,urgent,f.tenant(),device,"OFFLINE",2700,"虚构旧离线告警",clock.now().minusDays(3),clock.now());
    var ordinary=new ArrayList<Object[]>();
    for(int i=0;i<205;i++)ordinary.add(new Object[]{UUID.randomUUID().toString(),f.tenant(),device,"TEMPERATURE",45,"虚构近期温度告警",clock.now(),clock.now()});
    db.batchUpdate(insert,ordinary);
    String neighbor=call(f.admin(),"POST","/farms",Map.of("name","MySQL风险范围邻场","description","虚构范围验证"),200).path("ID").asText();
    String neighborDevice=call(f.admin(),"POST","/devices",Map.of("farmId",neighbor,"name","虚构邻场设备","metric","TEMPERATURE","adapter","SIMULATED"),200).path("ID").asText();
    db.update(insert,UUID.randomUUID().toString(),f.tenant(),neighborDevice,"OFFLINE",2700,"不属于当前农场的高优先级告警",clock.now(),clock.now());
    String path="/farms/"+f.farm()+"/risk-overview";
    var initial=call(f.operator(),"GET",path,null,200);
    assertEquals("DISABLED",initial.path("forecastStatus").asText());assertEquals(0,initial.at("/summary/weather").asInt());
    assertEquals(206,initial.at("/summary/device").asInt(),"the full total excludes the neighboring farm and is not capped at 200");
    assertEquals(1,initial.at("/summary/high").asInt());assertEquals(80,initial.path("items").size());assertTrue(initial.path("truncated").asBoolean());
    assertEquals("device:"+urgent,initial.at("/items/0/id").asText(),"an old urgent alert must survive both the 200-detail and 80-display limits");
    String issue=UUID.randomUUID().toString();
    db.update("INSERT INTO field_issues(id,tenant_id,plot_id,category,severity,description,reporter_id,status,created_at) VALUES(?,?,?,'EQUIPMENT','NORMAL','虚构设备异常转现场核查',?,'OPEN',?)",
      issue,f.tenant(),f.plot(),f.worker(),clock.now());
    db.update("INSERT INTO alert_field_issues(tenant_id,alert_id,issue_id) VALUES(?,?,?)",f.tenant(),urgent,issue);
    var merged=call(f.operator(),"GET",path,null,200);
    assertEquals(205,merged.at("/summary/device").asInt());assertEquals(1,merged.at("/summary/field").asInt());assertEquals(1,merged.at("/summary/high").asInt());
    assertEquals(initial.at("/summary/total"),merged.at("/summary/total"));assertEquals("field:"+issue,merged.at("/items/0/id").asText());
    assertEquals("HIGH",merged.at("/items/0/severity").asText());assertEquals(1,merged.at("/items/0/linkedDeviceAlerts").asInt());
    for(var item:merged.path("items"))assertNotEquals("device:"+urgent,item.path("id").asText());
    db.update("UPDATE field_issues SET status='RESOLVED',review_note='虚构现场复核完成',reviewed_by=?,resolved_at=? WHERE tenant_id=? AND id=?",f.worker(),clock.now(),f.tenant(),issue);
    var restored=call(f.admin(),"GET",path,null,200);
    assertEquals(206,restored.at("/summary/device").asInt());assertEquals(0,restored.at("/summary/field").asInt());
    assertEquals("device:"+urgent,restored.at("/items/0/id").asText(),"an unresolved original alert remains independently actionable after the linked issue closes");
    assertEquals(initial.at("/summary/total"),restored.at("/summary/total"));
    call(f.other(),"GET",path,null,404);call(f.platform(),"GET",path,null,403);
    assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=?",Long.class,f.tenant()));
    assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=?",Long.class,f.tenant()));
  }

  @Test void mysqlLegacyAndModernObservationsShareUtcTimeCorrectionAndAggregateSemantics() throws Exception {
    var f=isolatedFeatureFarm();var clock=context.getBean(app.zhinong.business.FarmClock.class);
    var input=new LinkedHashMap<String,Object>();input.put("farmId",f.farm());input.put("plotId",f.plot());
    input.put("name","MySQL观测核对");input.put("code","F-"+UUID.randomUUID());input.put("deviceType","SOIL");
    input.put("protocol","MANUAL");input.put("lifecycle","ACTIVE");input.put("intervalSeconds",900);input.put("revision",0);
    input.put("model","");input.put("notes","");input.put("channels",List.of(Map.of("metric","SOIL_MOISTURE","lowerLimit",20,"upperLimit",80)));
    String id=call(f.admin(),"POST","/assets",input,200).path("id").asText();
    var at=clock.now().minusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var created=call(f.operator(),"POST","/devices/"+id+"/observations",Map.of("value",18.001,"measuredAt",at.toLocalDateTime().toString()),200);
    assertEquals(at.toInstant(),java.time.OffsetDateTime.parse(created.path("MEASURED_AT").asText()).toInstant());
    var raw=call(f.admin(),"GET","/observations",null,200);
    assertEquals(at.toInstant(),java.time.OffsetDateTime.parse(raw.get(0).path("MEASURED_AT").asText()).toInstant());
    var asset=call(f.admin(),"GET","/assets/"+id,null,200);
    assertEquals(at.toInstant(),java.time.Instant.parse(asset.at("/channels/0/latest/time").asText()));
    assertTrue(asset.at("/channels/0/latest/receivedAtKnown").asBoolean());
    assertEquals("FRESH",asset.at("/channels/0/freshness").asText());
    assertEquals(18.0,call(f.admin(),"GET","/farms/"+f.farm()+"/operations?hours=24",null,200).at("/slices/99/moisture").asDouble(),0.001);
    call(f.operator(),"POST","/assets/"+id+"/readings",Map.of("measuredAt",at.toInstant().toString(),"readings",List.of(Map.of("metric","SOIL_MOISTURE","value",45))),200);
    assertEquals(45,call(f.admin(),"GET","/assets/"+id,null,200).at("/channels/0/latest/value").asInt());
    call(f.operator(),"POST","/devices/"+id+"/observations",Map.of("value",55,"measuredAt",at.toLocalDateTime().toString()),200);
    assertEquals(55,call(f.admin(),"GET","/assets/"+id,null,200).at("/channels/0/latest/value").asInt());
    var history=call(f.admin(),"GET","/assets/"+id+"/history?metric=SOIL_MOISTURE&hours=24",null,200);
    assertEquals(1,history.path("points").size());assertEquals(1,history.at("/points/0/samples").asInt());
    assertEquals(55,history.at("/points/0/value").asInt());
    assertEquals(55,call(f.admin(),"GET","/farms/"+f.farm()+"/operations?hours=24",null,200).at("/slices/99/moisture").asInt());
    var analysis=call(f.admin(),"GET","/ai/analysis?farmId="+f.farm(),null,200);
    assertEquals(55,analysis.at("/sensors/0/value").asInt());assertTrue(analysis.at("/sensors/0/fresh").asBoolean());
    assertEquals(2L,db.queryForObject("SELECT COUNT(*) FROM observations WHERE tenant_id=? AND device_id=?",Long.class,f.tenant(),id));
    assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=?",Long.class,f.tenant(),id));
  }

  @Test void mysqlDataQualityReportScopesFindingsAndLeavesFutureHarvestEvidenceUntouched() throws Exception {
    var f = isolatedFeatureFarm(); var clock = context.getBean(app.zhinong.business.FarmClock.class);
    String path = "/farms/" + f.farm() + "/data-quality";
    var clean = call(f.admin(), "GET", path, null, 200);
    assertEquals(clean.path("checks").size(), clean.at("/summary/checkedRules").asInt());
    assertEquals(0, clean.at("/summary/errorCount").asInt());
    call(f.operator(), "GET", path, null, 403);
    call(f.other(), "GET", path, null, 404);
    call(f.platform(), "GET", path, null, 403);

    String insert = "INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)";
    for (int i = 0; i < 2; i++) db.update(insert, UUID.randomUUID().toString(), f.tenant(), f.plot(),
      clock.today().plusDays(1), 20, "虚构MySQL待核对采收");
    String neighboringFarm = call(f.admin(), "POST", "/farms", Map.of("name", "MySQL核对范围邻场", "description", "虚构范围回归"), 200).path("ID").asText();
    String neighboringPlot = call(f.admin(), "POST", "/plots", Map.of("farmId", neighboringFarm, "name", "范围邻田", "areaMu", 10, "crop", "蔬菜"), 200).path("ID").asText();
    db.update(insert, UUID.randomUUID().toString(), f.tenant(), neighboringPlot, clock.today().plusDays(1), 99, "虚构其他农场记录");
    var before = db.queryForList("SELECT * FROM production WHERE tenant_id=? AND plot_id=? ORDER BY id", f.tenant(), f.plot());

    var report = call(f.admin(), "GET", path, null, 200);
    assertEquals(2, qualityRule(report, "FUTURE_PRODUCTION").path("count").asInt());
    assertEquals(2, qualityRule(report, "FUTURE_PRODUCTION").path("samples").size());
    assertEquals(1, qualityRule(report, "SUSPECTED_PRODUCTION_DUPLICATES").path("count").asInt());
    assertEquals(2, qualityRule(report, "UNLINKED_PRODUCTION").path("count").asInt());
    assertEquals(2, report.at("/summary/errorCount").asInt());
    assertEquals(1, report.at("/summary/warningCount").asInt());
    assertEquals(before, db.queryForList("SELECT * FROM production WHERE tenant_id=? AND plot_id=? ORDER BY id", f.tenant(), f.plot()));

    db.update("INSERT INTO production_tracking(tenant_id,production_id,status,updated_at) VALUES(?,?,'VOID',?)",
      f.tenant(), before.getFirst().get("ID"), clock.now());
    var afterVoid = call(f.admin(), "GET", path, null, 200);
    assertEquals(1, qualityRule(afterVoid, "FUTURE_PRODUCTION").path("count").asInt());
    assertEquals(0, qualityRule(afterVoid, "SUSPECTED_PRODUCTION_DUPLICATES").path("count").asInt());
    assertEquals(1, qualityRule(afterVoid, "UNLINKED_PRODUCTION").path("count").asInt());
    assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM production WHERE tenant_id=? AND plot_id=?", Long.class, f.tenant(), f.plot()));
  }

  private tools.jackson.databind.JsonNode qualityRule(tools.jackson.databind.JsonNode report, String code) {
    for (var row : report.path("checks")) if (code.equals(row.path("code").asText())) return row;
    throw new AssertionError("Missing quality rule: " + code);
  }

  @Test void mysqlHarvestRequestsCorrectionsAndVoidsKeepHistoryAndExcludeAggregates() throws Exception {
    var f = isolatedFeatureFarm();
    var clock = context.getBean(app.zhinong.business.FarmClock.class);
    String planting = call(f.admin(), "POST", "/plantings", Map.of("plotId", f.plot(), "crop", "蔬菜", "variety", "虚构品种", "areaMu", 10,
      "startDate", clock.today().minusDays(10).toString(), "endDate", clock.today().plusDays(10).toString()), 200).path("ID").asText();
    String harvest = call(f.admin(), "POST", "/tasks", Map.of("plotId", f.plot(), "title", "MySQL收获回归", "taskType", "HARVEST",
      "dueDate", clock.today().toString(), "note", "虚构测试数据"), 200).path("ID").asText();
    call(f.operator(), "PATCH", "/tasks/" + harvest + "/status", Map.of("status", "RUNNING"), 200);
    call(f.operator(), "PATCH", "/tasks/" + harvest + "/status", Map.of("status", "COMPLETED"), 200);
    var input = new LinkedHashMap<String,Object>();
    input.put("plotId", f.plot()); input.put("recordDate", clock.today().toString()); input.put("yieldKg", 125.5);
    input.put("note", "虚构收获回归"); input.put("plantingId", planting); input.put("taskId", harvest); input.put("requestId", UUID.randomUUID().toString());
    var created = call(f.operator(), "POST", "/production", input, 200); String id = created.path("ID").asText();
    assertEquals(planting, created.path("PLANTING_ID").asText()); assertEquals(harvest, created.path("TASK_ID").asText());
    assertEquals(id, call(f.operator(), "POST", "/production", input, 200).path("ID").asText());
    var conflict = new LinkedHashMap<>(input); conflict.put("yieldKg", 126);
    call(f.admin(), "POST", "/production", conflict, 409);
    assertEquals(1, call(f.admin(), "GET", "/production?farmId=" + f.farm(), null, 200).size());
    call(f.other(), "GET", "/production/" + id + "/history", null, 404);
    call(f.platform(), "GET", "/production", null, 403);

    var change = new LinkedHashMap<>(input); change.remove("requestId"); change.put("yieldKg", 100); change.put("expectedRevision", 0); change.put("reason", "");
    call(f.admin(), "PUT", "/production/" + id, change, 400);
    change.put("reason", "核对称重单后更正");
    call(f.other(), "PUT", "/production/" + id, change, 404);
    call(f.operator(), "PUT", "/production/" + id, change, 403);
    assertEquals(1, call(f.admin(), "PUT", "/production/" + id, change, 200).path("REVISION").asInt());
    call(f.admin(), "PUT", "/production/" + id, change, 409);
    assertEquals(100, call(f.admin(), "GET", "/dashboard", null, 200).path("yieldKg").asDouble(), 0.01);
    assertEquals(100, call(f.admin(), "GET", "/farms/" + f.farm() + "/analytics?days=30", null, 200).at("/summary/yieldKg").asDouble(), 0.01);

    call(f.admin(), "POST", "/production/" + id + "/void", Map.of("reason", "", "expectedRevision", 1), 400);
    var cancelled = call(f.admin(), "POST", "/production/" + id + "/void", Map.of("reason", "重复登记，保留原记录供核查", "expectedRevision", 1), 200);
    assertEquals("VOID", cancelled.path("STATUS").asText()); assertEquals(2, cancelled.path("REVISION").asInt());
    assertEquals(0, call(f.admin(), "GET", "/dashboard", null, 200).path("yieldKg").asDouble(), 0.01);
    assertEquals(0, call(f.admin(), "GET", "/farms/" + f.farm() + "/analytics?days=30", null, 200).at("/summary/yieldKg").asDouble(), 0.01);
    assertEquals(0, call(f.admin(), "GET", "/farms/" + f.farm() + "/operations?hours=24", null, 200).at("/pipeline/harvest/records").asInt());
    var history = call(f.admin(), "GET", "/production/" + id + "/history", null, 200);
    assertEquals(3, history.size()); assertEquals("CREATE", history.get(0).path("ACTION").asText());
    assertEquals(125.5, history.get(0).at("/snapshot/YIELD_KG").asDouble(), 0.01);
    assertEquals("核对称重单后更正", history.get(1).path("REASON").asText());
    assertEquals("VOID", history.get(2).at("/snapshot/STATUS").asText());
    assertEquals("VOID", call(f.operator(), "POST", "/production", input, 200).path("STATUS").asText());
  }

  @Test void mysqlDecisionEvidenceSurvivesApprovalCompletionAndAuditableRemeasurement() throws Exception {
    var f = isolatedFeatureFarm(); var clock = context.getBean(app.zhinong.business.FarmClock.class);
    String base = "/ai/decisions";
    var input = Map.of("farmId", f.farm(), "plotId", f.plot(), "question", "请核验缺少测点的地块，先人工巡查复测", "requestId", UUID.randomUUID().toString());
    var draft = call(f.operator(), "POST", base, input, 200); String id = draft.path("id").asText();
    assertEquals("MISSING", draft.at("/evidence/quality").asText());
    assertEquals(id, call(f.operator(), "POST", base, input, 200).path("id").asText());
    var changed = new LinkedHashMap<>(input); changed.put("question", "同键不同问题");
    call(f.operator(), "POST", base, changed, 409);
    call(f.other(), "GET", base + "/" + id, null, 404); call(f.platform(), "GET", base + "/" + id, null, 403);
    String original = db.queryForObject("SELECT evidence_json FROM ai_decisions WHERE tenant_id=? AND id=?", String.class, f.tenant(), id);
    var approval = Map.of("title", "MySQL证据巡查", "assigneeId", f.worker(), "dueDate", clock.today().toString(), "note", "核对测点并记录现场复测", "expectedRevision", 0);
    call(f.operator(), "POST", base + "/" + id + "/approve", approval, 403);
    call(f.other(), "POST", base + "/" + id + "/approve", approval, 404);
    var approved = call(f.admin(), "POST", base + "/" + id + "/approve", approval, 200); String task = approved.path("taskId").asText();
    assertEquals(task, call(f.admin(), "POST", base + "/" + id + "/approve", approval, 200).path("taskId").asText());
    assertEquals("INSPECTION", db.queryForObject("SELECT task_type FROM farm_tasks WHERE tenant_id=? AND id=?", String.class, f.tenant(), task));
    assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=? AND plot_id=?", Long.class, f.tenant(), f.plot()));
    var earlyReview = Map.of("note", "缺少任务完成回执", "requestId", UUID.randomUUID().toString(), "expectedRevision", 1);
    call(f.admin(), "POST", base + "/" + id + "/reviews", earlyReview, 409);
    String progress = "/field-work/tasks/" + task + "/progress";
    call(f.operator(), "PATCH", progress, Map.of("status", "RUNNING", "method", "MANUAL", "note", "开始现场巡查", "actualAreaMu", 0), 200);
    call(f.operator(), "PATCH", progress, Map.of("status", "COMPLETED", "method", "MANUAL", "note", "完成巡查，复测另行留痕", "actualAreaMu", 10), 200);
    // Separate this workflow fixture from database/current-clock precision boundaries.
    db.update("UPDATE task_fieldwork SET completed_at=? WHERE tenant_id=? AND task_id=?",
      clock.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS), f.tenant(), task);
    var insufficient = call(f.admin(), "POST", base + "/" + id + "/reviews", Map.of("note", "尚无新观测，不能判断已解决", "requestId", UUID.randomUUID().toString(), "expectedRevision", 1), 200);
    assertEquals("INSUFFICIENT", insufficient.at("/reviews/0/outcome").asText());
    assertEquals("完成巡查，复测另行留痕", insufficient.at("/task/completionNote").asText());
    var review = Map.of("note", "现场人工复测并记录方法", "manualValue", 35.5, "observedAt", clock.now().toString(), "requestId", UUID.randomUUID().toString(), "expectedRevision", 2);
    call(f.other(), "POST", base + "/" + id + "/reviews", review, 404);
    var measured = call(f.admin(), "POST", base + "/" + id + "/reviews", review, 200);
    assertEquals("EVIDENCE_RECORDED", measured.at("/reviews/0/outcome").asText());
    assertEquals(35.5, measured.at("/reviews/0/evidence/manual/value").asDouble(), 0.001);
    assertFalse(measured.at("/reviews/0/evidence/manual/recorder").asText().isBlank());
    assertEquals(3, measured.path("revision").asInt());
    assertEquals(measured.at("/reviews/0/id"), call(f.admin(), "POST", base + "/" + id + "/reviews", review, 200).at("/reviews/0/id"));
    assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM ai_decision_reviews WHERE tenant_id=? AND decision_id=?", Long.class, f.tenant(), id));
    assertEquals(original, db.queryForObject("SELECT evidence_json FROM ai_decisions WHERE tenant_id=? AND id=?", String.class, f.tenant(), id));
    assertEquals(draft.path("evidence"), measured.path("evidence"));
    assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=?", Long.class, f.tenant()));
  }

  private record FeatureFarm(String admin, String operator, String other, String platform, String tenant, String farm, String plot, String worker) {}

  /** Keep feature fixtures outside demo-a so inherited portfolio/count assertions remain independent. */
  private FeatureFarm isolatedFeatureFarm() throws Exception {
    String platform = login("platform", "platform"), code = "mysql-" + UUID.randomUUID().toString().substring(0, 8);
    String tenant = call(platform, "POST", "/platform/tenants", Map.of("code", code, "name", "MySQL独立功能回归", "adminPassword", "Test-Only-Password-429!"), 200).path("id").asText();
    String admin = login(code, "admin");
    String worker = call(admin, "POST", "/members", Map.of("username", "worker", "displayName", "回归操作员", "role", "OPERATOR", "password", "Test-Only-Password-429!"), 200).path("id").asText();
    String operator = login(code, "worker");
    String farm = call(admin, "POST", "/farms", Map.of("name", "MySQL新功能测试农场", "description", "虚构回归数据"), 200).path("ID").asText();
    String plot = call(admin, "POST", "/plots", Map.of("farmId", farm, "name", "MySQL测试田", "areaMu", 10, "crop", "蔬菜"), 200).path("ID").asText();
    return new FeatureFarm(admin, operator, login("demo-b", "admin"), platform, tenant, farm, plot, worker);
  }
}
