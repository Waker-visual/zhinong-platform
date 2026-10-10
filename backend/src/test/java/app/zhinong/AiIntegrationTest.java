package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.ai.AgronomyAnalysis;
import app.zhinong.ai.AiStreamTools;
import app.zhinong.ai.LlmGateway;
import app.zhinong.fieldwork.FieldWorkService;
import app.zhinong.workspace.FarmWorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-tests;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=false",
    "farm.ai.automation-enabled=false",
    "farm.map.automation-enabled=false",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
@Import(AiIntegrationTest.ModelConfig.class)
class AiIntegrationTest {

  @LocalServerPort
  int port;

  @Autowired
  ObjectMapper json;

  // No external requests or writes to the user's model configuration during tests.
  @Autowired
  TestLlmGateway llm;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @Autowired app.zhinong.ai.IrrigationTicker ticker;
  @Autowired app.zhinong.ai.AiConversations aiConversations;
  @Autowired app.zhinong.ai.IrrigationService irrigationService;
  @Autowired app.zhinong.workspace.FieldMapService fieldMaps;
  // Task 6: test-only AiStreamTools override, mirrors the TestLlmGateway pattern so the tool-failure
  // path (a whitelisted tool whose execution throws) can be exercised without touching real data.
  @Autowired TestAiStreamTools toolsFake;

  final HttpClient client = HttpClient.newHttpClient();
  String admin, otherAdmin, operator, viewer, platform, farmId;

  @BeforeEach
  void identities() throws Exception {
    admin = login("demo-a", "admin");
    otherAdmin = login("demo-b", "admin");
    operator = login("demo-a", "operator");
    viewer = login("demo-a", "viewer");
    platform = login("platform", "platform");
    farmId = call(admin, "POST", "/farms", Map.of("name", "AI验收农场-" + UUID.randomUUID(), "description", "虚构测试数据"), 200)
      .path("ID").asText();
    llm.answer = Optional.empty();
    llm.calls.set(0);
    llm.saved = null;
    llm.streamFailure = null;
    llm.streamPartialBeforeFailure = null;
    llm.scriptedStream = null;
    llm.titleAnswer = Optional.empty();
    llm.titleCalls.set(0);
    toolsFake.forcedFailureTool = null;
  }

  @AfterEach void finishTestRuns() {
    db.update("UPDATE ai_irrigation_policies SET mode='MANUAL'");
    db.update("UPDATE ai_irrigation_runs SET stop_at=? WHERE status='RUNNING'",java.time.OffsetDateTime.now().minusSeconds(1));ticker.tick();
  }

  @Test void conversationPrivacyHistoryAndRequestIdempotency() throws Exception {
    String id=call(admin,"POST","/ai/conversations",Map.of("farmId",farmId),200).path("id").asText();
    llm.answer=Optional.of(new LlmGateway.LlmResponse("基于当前农场记录的测试回答。",List.of()));
    var question=Map.of("question","请分析当前农场","requestId","first-turn");
    for(String token:List.of(otherAdmin,operator,viewer)) call(token,"GET","/ai/conversations/"+id+"/messages",null,404);
    call(platform,"GET","/ai/conversations/"+id+"/messages",null,403);
    call(otherAdmin,"POST","/ai/conversations",Map.of("farmId",farmId),404);
    call(admin,"POST","/ai/conversations/"+id+"/messages",question,200);
    assertTrue(llm.lastMessages.getFirst().get("content").toString().contains("雨量累计器已排除"));
    call(admin,"POST","/ai/conversations/"+id+"/messages",question,200);
    assertEquals(1,llm.calls.get());
    call(admin,"POST","/ai/conversations/"+id+"/messages",Map.of("question","另一个问题","requestId","first-turn"),409);
    call(admin,"POST","/ai/conversations/"+id+"/messages",Map.of("question","继续分析","requestId","next-turn"),200);
    assertTrue(llm.lastMessages.stream().anyMatch(m -> "基于当前农场记录的测试回答。".equals(m.get("content"))));
    assertEquals(4,call(admin,"GET","/ai/conversations/"+id+"/messages",null,200).size());
    call(operator,"DELETE","/ai/conversations/"+id,null,404);
    call(admin,"DELETE","/ai/conversations/"+id,null,200);
    call(admin,"GET","/ai/conversations/"+id+"/messages",null,404);
  }

  @Test void activitySummariesPersistOrderedStayTenantIsolatedAndSurviveDeletion() throws Exception {
    String id=call(admin,"POST","/ai/conversations",Map.of("farmId",farmId),200).path("id").asText();
    llm.answer=Optional.of(new LlmGateway.LlmResponse("测试回答。",List.of()));
    call(admin,"POST","/ai/conversations/"+id+"/messages",Map.of("question","请检查墒情","requestId","turn-1"),200);
    var before=call(admin,"GET","/ai/conversations/"+id+"/messages",null,200);
    assertEquals(2,before.size());
    for(var m:before) assertTrue(m.path("activities").isArray()&&m.path("activities").isEmpty());
    String assistantMessageId=before.get(1).path("ID").asText();

    var activities=List.of(
      new app.zhinong.ai.AiConversations.ActivitySummary("act-2",2,"tool","读取墒情数据","completed","查询最新土壤水分","已找到3条记录",java.time.OffsetDateTime.now(),java.time.OffsetDateTime.now()),
      new app.zhinong.ai.AiConversations.ActivitySummary("act-1",1,"context","读取农场上下文","completed",null,"已汇总当前农场资料",java.time.OffsetDateTime.now(),java.time.OffsetDateTime.now())
    );
    aiConversations.saveActivities(tenant(),id,assistantMessageId,activities);

    var after=call(admin,"GET","/ai/conversations/"+id+"/messages",null,200);
    var assistantAfter=after.get(1);
    assertEquals(2,assistantAfter.path("activities").size());
    assertEquals("act-1",assistantAfter.path("activities").get(0).path("ACTIVITY_ID").asText());
    assertEquals("context",assistantAfter.path("activities").get(0).path("KIND").asText());
    assertEquals("completed",assistantAfter.path("activities").get(0).path("STATUS").asText());
    assertEquals("已汇总当前农场资料",assistantAfter.path("activities").get(0).path("RESULT_SUMMARY").asText());
    assertTrue(assistantAfter.path("activities").get(0).path("DETAIL").isNull());
    assertEquals("act-2",assistantAfter.path("activities").get(1).path("ACTIVITY_ID").asText());
    assertEquals("查询最新土壤水分",assistantAfter.path("activities").get(1).path("DETAIL").asText());
    assertTrue(after.get(0).path("activities").isEmpty());

    call(otherAdmin,"GET","/ai/conversations/"+id+"/messages",null,404);

    call(admin,"DELETE","/ai/conversations/"+id,null,200);
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_message_activities WHERE tenant_id=? AND message_id=?",Integer.class,tenant(),assistantMessageId));
  }

  // 阶段 E：从消息“分支”出一个新对话——同租户、同账号、同农场，复制截至该消息（含）的消息
  // 与活动摘要，标题加“（分支）”；跨租户/他人对话/未知消息都是 404；分支出来的消息必须有自己
  // 全新的 request_id，不会撞上 (tenant_id,conversation_id,request_id,role) 的幂等唯一约束。
  @Test void branchConversationCopiesMessagesAndActivitiesUpToTheGivenMessage() throws Exception {
    String id=call(admin,"POST","/ai/conversations",Map.of("farmId",farmId),200).path("id").asText();
    llm.answer=Optional.of(new LlmGateway.LlmResponse("第一轮回答。",List.of()));
    call(admin,"POST","/ai/conversations/"+id+"/messages",Map.of("question","第一个问题","requestId","branch-turn-1"),200);
    var afterTurn1=call(admin,"GET","/ai/conversations/"+id+"/messages",null,200);
    String firstAssistantId=afterTurn1.get(1).path("ID").asText();
    aiConversations.saveActivities(tenant(),id,firstAssistantId,List.of(
      new app.zhinong.ai.AiConversations.ActivitySummary("ctx",1,"context","读取农场上下文","completed",null,"已汇总",java.time.OffsetDateTime.now(),java.time.OffsetDateTime.now())
    ));
    llm.answer=Optional.of(new LlmGateway.LlmResponse("第二轮回答。",List.of()));
    call(admin,"POST","/ai/conversations/"+id+"/messages",Map.of("question","第二个问题","requestId","branch-turn-2"),200);

    call(otherAdmin,"POST","/ai/conversations/"+id+"/branch",Map.of("messageId",firstAssistantId),404);
    call(admin,"POST","/ai/conversations/"+id+"/branch",Map.of("messageId","no-such-message"),404);
    String otherConvId=call(admin,"POST","/ai/conversations",Map.of("farmId",farmId),200).path("id").asText();
    call(admin,"POST","/ai/conversations/"+otherConvId+"/messages",Map.of("question","另一个对话的问题","requestId","branch-other-1"),200);
    String otherConvMessageId=call(admin,"GET","/ai/conversations/"+otherConvId+"/messages",null,200).get(0).path("ID").asText();
    call(admin,"POST","/ai/conversations/"+id+"/branch",Map.of("messageId",otherConvMessageId),404);

    var branched=call(admin,"POST","/ai/conversations/"+id+"/branch",Map.of("messageId",firstAssistantId),200);
    String branchedId=branched.path("id").asText();
    assertNotEquals(id,branchedId);
    assertTrue(branched.path("title").asText().endsWith("（分支）"));

    var branchedMessages=call(admin,"GET","/ai/conversations/"+branchedId+"/messages",null,200);
    assertEquals(2,branchedMessages.size(),"only the user question and assistant answer up to and including the branch point are copied");
    assertEquals("第一个问题",branchedMessages.get(0).path("CONTENT").asText());
    assertEquals("第一轮回答。",branchedMessages.get(1).path("CONTENT").asText());
    var branchedActivities=branchedMessages.get(1).path("activities");
    assertEquals(1,branchedActivities.size());
    assertEquals("ctx",branchedActivities.get(0).path("ACTIVITY_ID").asText());

    assertTrue(call(admin,"GET","/ai/conversations?farmId="+farmId,null,200).size()>=3,"original, the unrelated conversation and the branch must all be listed");
    call(otherAdmin,"GET","/ai/conversations/"+branchedId+"/messages",null,404);

    // 原对话不受影响，仍是两轮四条消息。
    assertEquals(4,call(admin,"GET","/ai/conversations/"+id+"/messages",null,200).size());

    // 再用相同 messageId 分支一次：必须成功（又会新建一个对话），不会撞上任何幂等/唯一约束。
    var branchedAgain=call(admin,"POST","/ai/conversations/"+id+"/branch",Map.of("messageId",firstAssistantId),200);
    assertNotEquals(branchedId,branchedAgain.path("id").asText());
  }

  @Test void platformAccountCannotBranchConversations() throws Exception {
    String id=call(admin,"POST","/ai/conversations",Map.of("farmId",farmId),200).path("id").asText();
    llm.answer=Optional.of(new LlmGateway.LlmResponse("回答。",List.of()));
    call(admin,"POST","/ai/conversations/"+id+"/messages",Map.of("question","问题","requestId","branch-platform-1"),200);
    String assistantId=call(admin,"GET","/ai/conversations/"+id+"/messages",null,200).get(1).path("ID").asText();
    call(platform,"POST","/ai/conversations/"+id+"/branch",Map.of("messageId",assistantId),403);
  }

  @Test void emptyAnalysisStatesMissingEvidenceAndChatFallsBackHonestly() throws Exception {
    var report=call(admin,"GET","/ai/analysis?farmId="+farmId,null,200);
    assertEquals(4,report.path("conditions").size()); assertEquals(0,report.path("freshMeasurements").asInt());
    assertTrue(report.path("weatherNotice").asText().contains("不是未来"));
    call(otherAdmin,"GET","/ai/analysis?farmId="+farmId,null,404);
    call(platform,"GET","/ai/irrigation?farmId="+farmId,null,403);
    String id=call(viewer,"POST","/ai/conversations",Map.of("farmId",farmId),200).path("id").asText();
    var response=call(viewer,"POST","/ai/conversations/"+id+"/messages",Map.of("question","现在启动灌溉","requestId","request-1"),200);
    assertEquals("rule",response.path("mode").asText());assertTrue(response.path("answer").asText().contains("没有下发"));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_irrigation_runs WHERE farm_id=?",Integer.class,farmId));
  }

  // Regression/benchmark test for the bounded latest-reading rewrite in AgronomyAnalysis.report():
  // seeds tens of thousands of telemetry rows across several devices/metrics (plus a second
  // tenant's farm) and checks that the latest value per (device,metric) and its freshness flag
  // are still computed correctly and tenant/farm-isolated, while logging how long the old
  // per-row correlated NOT EXISTS query took next to the new windowed query on identical data.
  @Test void latestSensorReadingIsCorrectTenantIsolatedAndFastAtScale() throws Exception {
    String plot = call(admin, "POST", "/plots", Map.of("farmId", farmId, "name", "规模测试地", "crop", "蔬菜", "areaMu", 5), 200).path("ID").asText();
    String weather = createDevice(plot, "WEATHER");
    String soil = createDevice(plot, "SOIL");
    String pest = createDevice(plot, "PEST");
    var devices = List.of(weather, soil, pest);
    var metrics = List.of("TEMPERATURE", "HUMIDITY", "WIND_SPEED", "RAINFALL", "SOIL_MOISTURE", "PEST_COUNT");
    String tenantId = tenant();
    var now = java.time.OffsetDateTime.now();
    var rnd = new Random(42);

    // expected.get("<deviceId>|<metric>") = {latestValue, freshFlag(1/0)}
    var expected = new LinkedHashMap<String, double[]>();
    var batch = new ArrayList<Object[]>();
    // 3 devices * 6 metrics * 400 = 7,200 historical rows: enough to exercise the windowed query and the
    // equivalence check, small enough that the slow legacy NOT EXISTS query cannot hit H2's statement
    // timeout on a loaded CI machine (at 27,000 rows it intermittently did).
    int rowsPerPair = 400;
    for (String device : devices) {
      for (String metric : metrics) {
        for (int i = 0; i < rowsPerPair; i++) {
          long secondsAgo = 2000 + rnd.nextInt(30 * 24 * 3600); // spread across the 30-day demo window, always older than the sentinels below
          double value = rnd.nextDouble() * 50;
          var t = now.minusSeconds(secondsAgo);
          batch.add(new Object[]{UUID.randomUUID().toString(), tenantId, device, metric, value, t, t});
        }
      }
    }
    // One designated latest reading per (device,metric): fresh (<15min) for weather/soil, stale for pest,
    // so both freshness branches are exercised with a known expected value.
    for (String device : devices) {
      for (String metric : metrics) {
        boolean fresh = !device.equals(pest);
        long secondsAgo = fresh ? 30 : 1000; // stale sentinel is still newer than every noise row (>=2000s) but older than the 900s fresh cutoff
        double value = 900.0 + metrics.indexOf(metric);
        var t = now.minusSeconds(secondsAgo);
        batch.add(new Object[]{UUID.randomUUID().toString(), tenantId, device, metric, value, t, t});
        expected.put(device + "|" + metric, new double[]{value, fresh ? 1 : 0});
      }
    }
    db.batchUpdate("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')", batch);

    // A second tenant/farm with its own telemetry; it must never leak into this farm's report.
    String otherFarmId = call(otherAdmin, "POST", "/farms", Map.of("name", "二租户规模测试场-" + UUID.randomUUID(), "description", "虚构测试数据"), 200).path("ID").asText();
    String otherPlot = call(otherAdmin, "POST", "/plots", Map.of("farmId", otherFarmId, "name", "二租户地块", "crop", "蔬菜", "areaMu", 2), 200).path("ID").asText();
    var otherDeviceInput = new LinkedHashMap<String, Object>();
    otherDeviceInput.put("farmId", otherFarmId); otherDeviceInput.put("plotId", otherPlot);
    otherDeviceInput.put("name", "虚构WEATHER"); otherDeviceInput.put("code", "TEST-" + UUID.randomUUID());
    otherDeviceInput.put("deviceType", "WEATHER"); otherDeviceInput.put("protocol", "SIMULATED");
    otherDeviceInput.put("lifecycle", "ACTIVE"); otherDeviceInput.put("model", "测试"); otherDeviceInput.put("notes", "虚构");
    otherDeviceInput.put("intervalSeconds", 60); otherDeviceInput.put("revision", 0); otherDeviceInput.put("controlEnabled", false);
    otherDeviceInput.put("channels", app.zhinong.workspace.MetricCatalog.PRESETS.get("WEATHER").stream().map(m -> Map.of("metric", m)).toList());
    String otherDevice = call(otherAdmin, "POST", "/assets", otherDeviceInput, 200).path("id").asText();
    String otherTenantId = db.queryForObject("SELECT id FROM tenants WHERE code='demo-b'", String.class);
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')",
      UUID.randomUUID().toString(), otherTenantId, otherDevice, "TEMPERATURE", 12345, now.minusSeconds(10), now.minusSeconds(10));

    long httpStart = System.nanoTime();
    var report = call(admin, "GET", "/ai/analysis?farmId=" + farmId, null, 200);
    long httpElapsedMs = (System.nanoTime() - httpStart) / 1_000_000;
    System.out.println("[perf] GET /ai/analysis over " + batch.size() + " seeded telemetry rows took " + httpElapsedMs + "ms");

    var sensors = report.path("sensors");
    assertEquals(devices.size() * metrics.size(), sensors.size(), "expected exactly one latest row per (device,metric)");
    var byKey = new HashMap<String, JsonNode>();
    for (var s : sensors) byKey.put(s.path("deviceId").asText() + "|" + s.path("METRIC").asText(), s);
    for (var e : expected.entrySet()) {
      var row = byKey.get(e.getKey());
      assertNotNull(row, "missing sensor row for " + e.getKey());
      assertEquals(e.getValue()[0], row.path("value").asDouble(), 0.0001, "wrong latest value for " + e.getKey());
      assertEquals(e.getValue()[1] == 1, row.path("fresh").asBoolean(), "wrong freshness for " + e.getKey());
    }
    // Tenant/farm isolation: the other tenant's device must never surface in this farm's report.
    for (var s : sensors) assertNotEquals(otherDevice, s.path("deviceId").asText());
    var otherReport = call(otherAdmin, "GET", "/ai/analysis?farmId=" + otherFarmId, null, 200);
    boolean otherDeviceSeen = false;
    for (var s : otherReport.path("sensors")) if (otherDevice.equals(s.path("deviceId").asText())) otherDeviceSeen = true;
    assertTrue(otherDeviceSeen, "the other tenant's own farm report should still see its own device");

    // Prove semantic equivalence: the original per-row correlated NOT EXISTS query (kept here only
    // as a literal comparison baseline, not production code) must pick the exact same latest
    // (device,metric,value) rows on this identical dataset, and we log its elapsed time alongside
    // the new windowed query run directly against the DB (no HTTP/JSON overhead) for comparison.
    var oldSql = """
      SELECT d.id AS "deviceId",t.metric,t.measured_value AS "value"
      FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
      JOIN telemetry_readings t ON t.tenant_id=d.tenant_id AND t.device_id=d.id
      LEFT JOIN plots p ON p.tenant_id=a.tenant_id AND p.id=a.plot_id
      WHERE d.tenant_id=? AND d.farm_id=? AND a.lifecycle='ACTIVE'
        AND t.metric IN ('SOIL_MOISTURE','TEMPERATURE','HUMIDITY','WIND_SPEED','RAINFALL','PEST_COUNT')
        AND NOT EXISTS(SELECT 1 FROM telemetry_readings n WHERE n.tenant_id=t.tenant_id AND n.device_id=t.device_id
          AND n.metric=t.metric AND (n.measured_at>t.measured_at OR (n.measured_at=t.measured_at AND n.id>t.id)))
      ORDER BY d.name,t.metric
      """;
    var newSql = """
      SELECT "deviceId",metric,"value" FROM (
        SELECT d.id AS "deviceId",t.metric,t.measured_value AS "value",
          ROW_NUMBER() OVER (PARTITION BY t.device_id,t.metric ORDER BY t.measured_at DESC,t.id DESC) AS rn
        FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
        JOIN telemetry_readings t ON t.tenant_id=d.tenant_id AND t.device_id=d.id
        WHERE d.tenant_id=? AND d.farm_id=? AND a.lifecycle='ACTIVE'
          AND t.metric IN ('SOIL_MOISTURE','TEMPERATURE','HUMIDITY','WIND_SPEED','RAINFALL','PEST_COUNT')
      ) ranked WHERE rn=1
      ORDER BY "deviceId",metric
      """;

    long oldStart = System.nanoTime();
    var oldRows = db.queryForList(oldSql, tenantId, farmId);
    long oldElapsedMs = (System.nanoTime() - oldStart) / 1_000_000;
    long newStart = System.nanoTime();
    var newRows = db.queryForList(newSql, tenantId, farmId);
    long newElapsedMs = (System.nanoTime() - newStart) / 1_000_000;
    System.out.println("[perf] direct SQL on " + batch.size() + " rows -- old correlated NOT EXISTS: " + oldElapsedMs
      + "ms, new windowed query: " + newElapsedMs + "ms");

    java.util.function.Function<List<Map<String, Object>>, Set<String>> toKeySet = rows -> {
      Set<String> keys = new HashSet<>();
      for (var r : rows) keys.add(r.get("deviceId") + "|" + r.get("METRIC") + "|" + Math.round(((Number) r.get("value")).doubleValue() * 10000.0));
      return keys;
    };
    assertEquals(toKeySet.apply(oldRows), toKeySet.apply(newRows), "rewritten query must select the exact same latest rows as the original");
    assertEquals(devices.size() * metrics.size(), oldRows.size());
    assertEquals(oldRows.size(), newRows.size());
  }

  String tenant() {return db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'",String.class);}
  Map<String,Object> irrigationFixture() throws Exception {
    String plot=call(admin,"POST","/plots",Map.of("farmId",farmId,"name","虚构蔬菜地-"+UUID.randomUUID(),"crop","蔬菜","areaMu",5),200).path("ID").asText();
    String planting=call(admin,"POST","/plantings",Map.of("plotId",plot,"crop","蔬菜","variety","演示品种","areaMu",5,"startDate",java.time.LocalDate.now().minusDays(20).toString(),"endDate",java.time.LocalDate.now().plusDays(30).toString()),200).path("ID").asText();
    call(admin,"PATCH","/plantings/"+planting+"/status",Map.of("status","ACTIVE"),200);
    String sensor=createDevice(plot,"SOIL"),pump=createDevice(plot,"PUMP");
    reading(sensor,"SOIL_MOISTURE",20,0);reading(pump,"REMOTE_ENABLED",1,0);reading(pump,"FAULT",0,0);reading(pump,"PUMP_RUNNING",0,0);
    var policy=new LinkedHashMap<String,Object>();policy.put("farmId",farmId);policy.put("plotId",plot);policy.put("sensorId",sensor);policy.put("pumpId",pump);policy.put("mode","MANUAL");policy.put("thresholdValue",30);policy.put("durationSeconds",60);policy.put("cooldownMinutes",120);policy.put("dailyLimit",3);policy.put("revision",0);return policy;
  }
  String createDevice(String plot,String type) throws Exception {
    var input=new LinkedHashMap<String,Object>();input.put("farmId",farmId);input.put("plotId",plot);input.put("name","虚构"+type);input.put("code","TEST-"+UUID.randomUUID());input.put("deviceType",type);input.put("protocol","SIMULATED");input.put("lifecycle","ACTIVE");input.put("model","测试");input.put("notes","虚构");input.put("intervalSeconds",60);input.put("revision",0);input.put("controlEnabled",type.equals("PUMP"));input.put("channels",app.zhinong.workspace.MetricCatalog.PRESETS.get(type).stream().map(m->Map.of("metric",m)).toList());return call(admin,"POST","/assets",input,200).path("id").asText();
  }
  void reading(String device,String metric,int value,int secondsAgo) {
    db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=?",tenant(),device,metric);
    var now=java.time.OffsetDateTime.now().minusSeconds(secondsAgo);
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')",UUID.randomUUID().toString(),tenant(),device,metric,value,now,now);
  }
  String propose(Map<String,Object> p) throws Exception {return call(operator,"POST","/ai/irrigation/plots/"+p.get("plotId")+"/propose",null,200).path("ID").asText();}

  Map<String,Object> mappedIrrigationFixture() throws Exception {
    var p=irrigationFixture();String parcel=UUID.randomUUID().toString(),zone=UUID.randomUUID().toString();
    db.update("INSERT INTO farm_map_parcels(id,tenant_id,farm_id,plot_id,name,boundary_json,source,source_note,created_at) VALUES(?,?,?,?,?,?,'IMAGERY_ESTIMATE','虚构估绘',?)",parcel,tenant(),farmId,p.get("plotId"),"C-01","[[30,120],[30,120.001],[30.001,120.001],[30.001,120]]",java.time.OffsetDateTime.now());
    db.update("INSERT INTO farm_map_zones(id,tenant_id,farm_id,parcel_id,pump_id,name,pipeline_json,nodes_json,source) VALUES(?,?,?,?,?,?,'[[30,120],[30.0005,120.0005]]','[]','SIMULATED')",zone,tenant(),farmId,parcel,p.get("pumpId"),"C-01 灌溉分区");
    p.put("zoneId",zone);return p;
  }
  String linkedJob(String run) {return db.queryForObject("SELECT job_id FROM ai_irrigation_map_runs WHERE tenant_id=? AND run_id=?",String.class,tenant(),run);}

  @Test void mappedIrrigationDisplaysHardwareWithoutPolicyAndSharesManualHistory() throws Exception {
    var p=mappedIrrigationFixture();String path="/farms/"+farmId+"/field-map";
    var before=call(admin,"GET","/ai/irrigation?farmId="+farmId,null,200);
    assertTrue(before.path("policies").isEmpty());assertEquals(1,before.path("zones").size());assertEquals(2,before.path("devices").size());
    assertTrue(before.path("plots").get(0).path("diagnosis").asText().contains("尚未保存"));
    assertTrue(before.path("devices").findValues("moistureFresh").stream().anyMatch(JsonNode::asBoolean));
    var job=call(operator,"POST",path+"/irrigation",Map.of("zoneId",p.get("zoneId"),"durationSeconds",60,"requestId",UUID.randomUUID().toString()),200);
    var state=call(admin,"GET","/ai/irrigation?farmId="+farmId,null,200);
    assertEquals(job.path("id"),state.path("jobs").get(0).path("id"));assertEquals(1,state.at("/waterTotals/runs").asInt());
    assertTrue(state.path("runs").isEmpty(),"A manual task must not fabricate an AI recommendation");
    call(operator,"POST",path+"/jobs/"+job.path("id").asText()+"/actions",Map.of("action","STOP"),200);
    call(admin,"PUT","/ai/irrigation/policy",p,200);
    var refused=call(operator,"POST","/ai/irrigation/plots/"+p.get("plotId")+"/propose",null,409);
    assertTrue(refused.toString().contains("冷却期"),"Manual map irrigation participates in AI cooldown");
    call(otherAdmin,"GET","/ai/irrigation?farmId="+farmId,null,404);call(platform,"GET","/ai/irrigation?farmId="+farmId,null,403);
  }

  @Test void mappedApprovalCreatesOneSharedJobAndMapStopCompletesAiWithEstimatedWater() throws Exception {
    var p=mappedIrrigationFixture();call(admin,"PUT","/ai/irrigation/policy",p,200);String run=propose(p);
    call(operator,"POST","/ai/irrigation/runs/"+run+"/approve",null,200);
    call(admin,"POST","/ai/irrigation/runs/"+run+"/approve",null,200);
    String job=linkedJob(run);assertNotNull(job);
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM farm_map_jobs WHERE tenant_id=? AND farm_id=?",Integer.class,tenant(),farmId));
    var state=call(admin,"GET","/ai/irrigation?farmId="+farmId,null,200);
    assertEquals(job,state.path("runs").get(0).path("mapJobId").asText());assertEquals(run,state.path("jobs").get(0).path("aiRunId").asText());
    assertEquals(p.get("zoneId"),state.path("runs").get(0).path("ZONE_ID").asText());
    db.update("UPDATE farm_map_jobs SET last_tick_at=? WHERE tenant_id=? AND id=?",java.time.OffsetDateTime.now().minusSeconds(12),tenant(),job);
    var stopped=call(operator,"POST","/farms/"+farmId+"/field-map/jobs/"+job+"/actions",Map.of("action","STOP"),200);
    assertTrue(stopped.path("estimatedM3").asDouble()>0);assertTrue(stopped.path("measuredM3").isNull());
    assertEquals("COMPLETED",db.queryForObject("SELECT status FROM ai_irrigation_runs WHERE tenant_id=? AND id=?",String.class,tenant(),run));
    ticker.tick();call(admin,"POST","/ai/irrigation/runs/"+run+"/cancel",null,200);
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND device_id=? AND action='PUMP_STOP'",Integer.class,tenant(),p.get("pumpId")));
  }

  @Test void aiStopsSharedTaskOnDeadlinePolicyChangeAndRevokedAuthorization() throws Exception {
    for(String reason:List.of("deadline","policy","owner")) {
      var p=mappedIrrigationFixture();p.put("mode","AUTO");call(admin,"PUT","/ai/irrigation/policy",p,200);ticker.tick();
      String run=db.queryForObject("SELECT id FROM ai_irrigation_runs WHERE tenant_id=? AND plot_id=? AND status='RUNNING'",String.class,tenant(),p.get("plotId")),job=linkedJob(run);
      if(reason.equals("deadline"))db.update("UPDATE ai_irrigation_runs SET stop_at=? WHERE tenant_id=? AND id=?",java.time.OffsetDateTime.now().minusSeconds(1),tenant(),run);
      if(reason.equals("policy")){p.put("revision",1);p.put("mode","MANUAL");call(admin,"PUT","/ai/irrigation/policy",p,200);}
      if(reason.equals("owner"))db.update("UPDATE members SET enabled=FALSE WHERE tenant_id=? AND username='admin'",tenant());
      try {ticker.tick();assertEquals("COMPLETED",db.queryForObject("SELECT status FROM ai_irrigation_runs WHERE tenant_id=? AND id=?",String.class,tenant(),run));assertEquals("STOPPED",db.queryForObject("SELECT status FROM farm_map_jobs WHERE tenant_id=? AND id=?",String.class,tenant(),job));}
      finally {db.update("UPDATE members SET enabled=TRUE WHERE tenant_id=? AND username='admin'",tenant());db.update("UPDATE ai_irrigation_policies SET mode='MANUAL' WHERE tenant_id=?",tenant());}
    }
  }

  @Test void mapDeadlineCompletesAiAndRetainsAcceptedScopeAfterPolicyEdit() throws Exception {
    var p=mappedIrrigationFixture();call(admin,"PUT","/ai/irrigation/policy",p,200);String run=propose(p);
    call(admin,"POST","/ai/irrigation/runs/"+run+"/approve",null,200);String job=linkedJob(run);
    db.update("UPDATE farm_map_jobs SET last_tick_at=? WHERE tenant_id=? AND id=?",java.time.OffsetDateTime.now().minusMinutes(2),tenant(),job);
    fieldMaps.tick(tenant(),farmId,job);ticker.tick();
    assertEquals("COMPLETED",db.queryForObject("SELECT status FROM ai_irrigation_runs WHERE tenant_id=? AND id=?",String.class,tenant(),run));
    assertEquals("COMPLETED",db.queryForObject("SELECT status FROM farm_map_jobs WHERE tenant_id=? AND id=?",String.class,tenant(),job));
    p.put("revision",1);p.remove("zoneId");var updated=call(admin,"PUT","/ai/irrigation/policy",p,200);
    assertEquals(updated.path("runs").get(0).path("ZONE_ID"),updated.path("policies").get(0).path("ZONE_ID"),"Older clients preserve explicit zone binding");
  }

  @Test void zoneBindingRejectsWrongPlotPumpFarmAndPaddyStillRequiresWaterLevel() throws Exception {
    var p=mappedIrrigationFixture();var other=mappedIrrigationFixture();
    var bad=new LinkedHashMap<>(p);bad.put("zoneId",other.get("zoneId"));call(admin,"PUT","/ai/irrigation/policy",bad,400);
    bad=new LinkedHashMap<>(p);bad.put("pumpId",other.get("pumpId"));call(admin,"PUT","/ai/irrigation/policy",bad,400);
    String newFarm=call(admin,"POST","/farms",Map.of("name","另一农场","description","虚构"),200).path("ID").asText();
    db.update("UPDATE farm_map_zones SET farm_id=? WHERE tenant_id=? AND id=?",newFarm,tenant(),p.get("zoneId"));call(admin,"PUT","/ai/irrigation/policy",p,400);
    db.update("UPDATE farm_map_zones SET farm_id=? WHERE tenant_id=? AND id=?",farmId,tenant(),p.get("zoneId"));
    call(admin,"PUT","/ai/irrigation/policy",p,200);
    db.update("UPDATE plantings SET crop='水稻' WHERE tenant_id=? AND plot_id=?",tenant(),p.get("plotId"));
    var rejected=call(admin,"POST","/ai/irrigation/plots/"+p.get("plotId")+"/propose",null,409);assertTrue(rejected.toString().contains("水位"));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM farm_map_jobs WHERE tenant_id=? AND farm_id=?",Integer.class,tenant(),farmId));
  }

  @Test void irrigationRequiresApprovalIsIdempotentAndStopsAfterRestartDeadline() throws Exception {
    var p=irrigationFixture();
    call(operator,"PUT","/ai/irrigation/policy",p,403);call(viewer,"PUT","/ai/irrigation/policy",p,403);
    call(otherAdmin,"PUT","/ai/irrigation/policy",p,404);call(admin,"PUT","/ai/irrigation/policy",p,200);
    String id=propose(p);assertEquals(id,propose(p));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND device_id=?",Integer.class,tenant(),p.get("pumpId")));
    call(viewer,"POST","/ai/irrigation/runs/"+id+"/approve",null,403);call(otherAdmin,"POST","/ai/irrigation/runs/"+id+"/approve",null,404);
    call(admin,"POST","/ai/irrigation/runs/"+id+"/approve",null,200);call(admin,"POST","/ai/irrigation/runs/"+id+"/approve",null,200);
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND device_id=? AND action='PUMP_START'",Integer.class,tenant(),p.get("pumpId")));
    db.update("UPDATE ai_irrigation_runs SET stop_at=? WHERE tenant_id=? AND id=?",java.time.OffsetDateTime.now().minusSeconds(1),tenant(),id);
    ticker.tick();ticker.tick();
    assertEquals("COMPLETED",db.queryForObject("SELECT status FROM ai_irrigation_runs WHERE tenant_id=? AND id=?",String.class,tenant(),id));
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND device_id=? AND action='PUMP_STOP'",Integer.class,tenant(),p.get("pumpId")));
    call(admin,"POST","/ai/irrigation/plots/"+p.get("plotId")+"/propose",null,409);
  }

  @Test void irrigationRechecksFreshnessThresholdCropAndPolicyBeforeApproval() throws Exception {
    var p=irrigationFixture();call(admin,"PUT","/ai/irrigation/policy",p,200);String id=propose(p);
    reading(p.get("sensorId").toString(),"SOIL_MOISTURE",20,1000);
    call(admin,"POST","/ai/irrigation/runs/"+id+"/approve",null,409);
    reading(p.get("sensorId").toString(),"SOIL_MOISTURE",40,0);
    call(admin,"POST","/ai/irrigation/runs/"+id+"/approve",null,409);
    reading(p.get("sensorId").toString(),"SOIL_MOISTURE",20,0);
    db.update("UPDATE plantings SET crop='水稻' WHERE tenant_id=? AND plot_id=?",tenant(),p.get("plotId"));
    call(admin,"POST","/ai/irrigation/runs/"+id+"/approve",null,409);
    db.update("UPDATE plantings SET crop='蔬菜' WHERE tenant_id=? AND plot_id=?",tenant(),p.get("plotId"));
    p.put("revision",1);p.put("durationSeconds",30);call(admin,"PUT","/ai/irrigation/policy",p,200);
    call(admin,"POST","/ai/irrigation/runs/"+id+"/approve",null,409);
    p.put("durationSeconds",301);call(admin,"PUT","/ai/irrigation/policy",p,400);
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND device_id=?",Integer.class,tenant(),p.get("pumpId")));
  }

  @Test void automaticModeRunsWithoutModelAndStopsWhenOwnerDisabled() throws Exception {
    var p=irrigationFixture();p.put("mode","AUTO");call(admin,"PUT","/ai/irrigation/policy",p,200);
    ticker.tick();
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_irrigation_runs WHERE tenant_id=? AND plot_id=? AND status='RUNNING' AND approved_by='AI_AUTOMATION'",Integer.class,tenant(),p.get("plotId")));
    assertEquals(0,llm.calls.get());
    db.update("UPDATE members SET enabled=FALSE WHERE tenant_id=? AND username='admin'",tenant());
    try {ticker.tick();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_irrigation_runs WHERE tenant_id=? AND plot_id=? AND status='RUNNING'",Integer.class,tenant(),p.get("plotId")));}
    finally {db.update("UPDATE members SET enabled=TRUE WHERE tenant_id=? AND username='admin'",tenant());}
  }

  @Test void physicalProtocolAndCrossFarmSensorBindingsAreRejected() throws Exception {
    var p=irrigationFixture();String another=call(admin,"POST","/plots",Map.of("farmId",farmId,"name","另一块地","crop","蔬菜","areaMu",5),200).path("ID").asText();
    var invalid=new LinkedHashMap<>(p);invalid.put("plotId",another);call(admin,"PUT","/ai/irrigation/policy",invalid,400);
    db.update("UPDATE asset_profiles SET protocol='HTTP_PUSH' WHERE tenant_id=? AND device_id=?",tenant(),p.get("pumpId"));
    call(admin,"PUT","/ai/irrigation/policy",p,409);
  }

  @Test
  void statusExposesModelIdButNeverUrlOrKeyToAnyLoggedInAccount() throws Exception {
    // 规则回退：未配置云端模型时，/ai/status 不带 model 字段（旧行为保持不变）。
    JsonNode disabled = call(admin, "GET", "/ai/status", null, 200);
    assertFalse(disabled.has("model"));
    assertFalse(disabled.path("llm").asBoolean());

    llm.cloudEnabledValue = true;
    llm.modelValue = "deepseek-flash";
    try {
      for (String token : List.of(admin, operator, viewer, platform)) {
        JsonNode status = call(token, "GET", "/ai/status", null, 200);
        assertTrue(status.path("llm").asBoolean());
        assertEquals("deepseek-flash", status.path("model").asText());
        // 绝不能把地址或密钥带进这个轻量状态接口。
        String body = status.toString().toLowerCase();
        assertFalse(body.contains("url"));
        assertFalse(body.contains("key"));
        assertFalse(body.contains("apikey"));
      }
    } finally {
      llm.cloudEnabledValue = false;
      llm.modelValue = "";
    }
  }

  @Test
  void globalModelConfigurationIsRestrictedToPlatformAdmin() throws Exception {
    Map<String, String> input = Map.of("url", "https://example.invalid/v1", "model", "test-model");
    call(null, "GET", "/ai/status", null, 401);
    call(null, "GET", "/ai/config", null, 401);
    for (String token : List.of(admin, otherAdmin, operator, viewer)) {
      call(token, "GET", "/ai/config", null, 403);
      call(token, "POST", "/ai/config", input, 403);
    }
    assertNull(llm.saved);
    JsonNode config = call(platform, "GET", "/ai/config", null, 200);
    assertFalse(config.has("apiKey"));
    assertTrue(config.has("apiKeySet"));
    call(platform, "POST", "/ai/config", input, 200);
    assertEquals(Arrays.asList("https://example.invalid/v1", null, "test-model"), llm.saved);
  }

  @Test
  void modelConnectionTestAndModelOptionManagementAreRestrictedToPlatformAdmin() throws Exception {
    for (String token : List.of(admin, otherAdmin, operator, viewer)) {
      call(token, "POST", "/ai/config/test", Map.of(), 403);
      call(token, "POST", "/ai/config/models", Map.of(), 403);
      call(token, "POST", "/ai/config/models/options", Map.of("ids", List.of("sk-test-placeholder-model")), 403);
      call(token, "POST", "/ai/config/models/options/remove", Map.of("id", "sk-test-placeholder-model"), 403);
    }
    call(null, "POST", "/ai/config/test", Map.of(), 401);

    JsonNode test = call(platform, "POST", "/ai/config/test", Map.of(), 200);
    assertEquals("OK", test.path("code").asText());

    JsonNode fetched = call(platform, "POST", "/ai/config/models", Map.of(), 200);
    assertEquals("OK", fetched.path("diagnostic").asText());
    assertEquals(2, fetched.path("models").size());
    assertFalse(fetched.toString().toLowerCase().contains("apikey"));

    JsonNode added = call(platform, "POST", "/ai/config/models/options",
      Map.of("ids", List.of("test-model-a", "test-model-b", "test-model-a")), 200);
    List<String> options = new ArrayList<>();
    added.path("modelOptions").forEach(n -> options.add(n.asText()));
    assertEquals(List.of("test-model-a", "test-model-b"), options);

    JsonNode config = call(platform, "GET", "/ai/config", null, 200);
    assertFalse(config.toString().toLowerCase().contains("sk-test-placeholder"));
    List<String> configOptions = new ArrayList<>();
    config.path("modelOptions").forEach(n -> configOptions.add(n.asText()));
    assertEquals(List.of("test-model-a", "test-model-b"), configOptions);

    JsonNode removed = call(platform, "POST", "/ai/config/models/options/remove", Map.of("id", "test-model-a"), 200);
    List<String> afterRemove = new ArrayList<>();
    removed.path("modelOptions").forEach(n -> afterRemove.add(n.asText()));
    assertEquals(List.of("test-model-b"), afterRemove);
  }

  @Test
  void farmAccessIsCheckedBeforeAnyModelCall() throws Exception {
    // Even a model that answers without requesting tools cannot bypass farm authorization.
    llm.answer = Optional.of(
      new LlmGateway.LlmResponse("虚构演示回答", List.of())
    );
    Map<String, String> ask = Map.of("farmId", farmId, "question", "今天需要做什么？");
    call(null, "POST", "/ai/ask", ask, 401);
    call(otherAdmin, "POST", "/ai/ask", ask, 404);
    call(platform, "POST", "/ai/ask", ask, 403);
    call(admin, "POST", "/ai/ask", Map.of("farmId", "missing", "question", "农场概况"), 404);
    call(otherAdmin, "POST", "/ai/insights", Map.of("farmId", farmId), 404);
    call(platform, "POST", "/ai/insights", Map.of("farmId", farmId), 403);
    assertEquals(0, llm.calls.get());
    assertEquals("llm", call(admin, "POST", "/ai/ask", ask, 200).path("mode").asText());
    assertEquals(1, llm.calls.get());
  }

  @Test
  void tenantUsersReceiveRuleAnswersWhenModelIsUnavailable() throws Exception {
    for (String token : List.of(admin, operator, viewer)) {
      JsonNode answer = call(token, "POST", "/ai/ask", Map.of("farmId", farmId, "question", "农场概况"), 200);
      assertEquals("rule", answer.path("mode").asText());
      assertFalse(answer.path("answer").asText().isBlank());
      JsonNode insights = call(token, "POST", "/ai/insights", Map.of("farmId", farmId), 200);
      assertEquals("rule", insights.path("mode").asText());
      assertFalse(insights.path("items").isEmpty());
    }
  }

  // ---------- Task 5: streaming endpoint ----------

  JsonNode[] streamEvents(String token, String id, Object question, int expectedStatus) throws Exception {
    var request = HttpRequest
      .newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/conversations/" + id + "/stream"))
      .header("Content-Type", "application/json")
      // 同时接受 JSON：鉴权失败等场景在建立 SSE 连接之前就返回普通 JSON 错误体，
      // 只接受 text/event-stream 会导致协商失败变成 500（与真实前端 streamJson 的 Accept 一致）。
      .header("Accept", "text/event-stream, application/json;q=0.9, */*;q=0.1");
    if (token != null) request.header("Authorization", "Bearer " + token);
    request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(question)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expectedStatus, response.statusCode(), response.body());
    if (response.statusCode() != 200) return new JsonNode[0];
    List<JsonNode> events = new ArrayList<>();
    for (String block : response.body().replace("\r", "").split("\n\n")) {
      for (String line : block.split("\n")) {
        if (line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim()));
      }
    }
    return events.toArray(new JsonNode[0]);
  }

  @Test void streamEmitsOrderedEventsAndPersistsLikeSyncPath() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.answer = Optional.of(new LlmGateway.LlmResponse("基于当前农场记录的流式测试回答。", List.of()));
    var events = streamEvents(admin, id, Map.of("question", "请分析当前农场", "requestId", "stream-turn-1"), 200);
    assertTrue(events.length >= 5, "expected at least run.started/activity/message/run.completed events");
    assertEquals("run.started", events[0].path("type").asText());
    assertEquals("run.completed", events[events.length - 1].path("type").asText());
    assertTrue(java.util.Arrays.stream(events).anyMatch(e -> "activity.started".equals(e.path("type").asText())));
    assertTrue(java.util.Arrays.stream(events).anyMatch(e -> "activity.completed".equals(e.path("type").asText())));
    StringBuilder text = new StringBuilder();
    for (JsonNode e : events) if ("message.delta".equals(e.path("type").asText())) text.append(e.path("delta").asText());
    assertEquals("基于当前农场记录的流式测试回答。", text.toString());
    int lastSeq = -1;
    for (JsonNode e : events) {
      int seq = e.path("sequence").asInt();
      assertTrue(seq > lastSeq, "sequence must be strictly increasing");
      lastSeq = seq;
    }
    JsonNode completedEvent = java.util.Arrays.stream(events).filter(e -> "message.completed".equals(e.path("type").asText())).findFirst().orElseThrow();
    String messageId = completedEvent.path("messageId").asText();
    assertFalse(messageId.isBlank());
    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    assertEquals(2, persisted.size());
    assertEquals("请分析当前农场", persisted.get(0).path("CONTENT").asText());
    assertEquals("基于当前农场记录的流式测试回答。", persisted.get(1).path("CONTENT").asText());
    assertEquals(messageId, persisted.get(1).path("ID").asText());
    assertEquals("llm", persisted.get(1).path("MODE").asText());
    // The context activity summary emitted during the run must actually be persisted (not just shown
    // live over SSE) so it survives a reload, exactly like the sync path's saveActivities contract.
    assertEquals(2, persisted.get(1).path("activities").size());
    var persistedActivity = persisted.get(1).path("activities").get(0);
    assertEquals("context", persistedActivity.path("ACTIVITY_ID").asText());
    assertEquals("context", persistedActivity.path("KIND").asText());
    assertEquals("completed", persistedActivity.path("STATUS").asText());
    assertFalse(persistedActivity.path("RESULT_SUMMARY").asText().isBlank());
    // Task: a "生成回答" task activity spans the model generation itself, separate from reading the
    // farm context, so its own duration is meaningful instead of being folded into the context step.
    var answerActivity = persisted.get(1).path("activities").get(1);
    assertEquals("answer", answerActivity.path("ACTIVITY_ID").asText());
    assertEquals("task", answerActivity.path("KIND").asText());
    assertEquals("生成回答", answerActivity.path("LABEL").asText());
    assertEquals("completed", answerActivity.path("STATUS").asText());
    assertTrue(persisted.get(0).path("activities").isEmpty(), "the user message has no activities of its own");

    // Replaying the same requestId must emit the stored activity as an activity.completed event too.
    var replay = streamEvents(admin, id, Map.of("question", "请分析当前农场", "requestId", "stream-turn-1"), 200);
    var replayedActivity = java.util.Arrays.stream(replay).filter(e -> "activity.completed".equals(e.path("type").asText())).findFirst().orElseThrow();
    assertEquals("context", replayedActivity.path("activity").path("id").asText());
    assertFalse(replayedActivity.path("activity").path("resultSummary").asText().isBlank());
  }

  @Test void streamRuleFallbackWhenModelUnavailableIsPersistedAndMarked() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    // llm.answer stays Optional.empty() from @BeforeEach -> model "unavailable", same as the sync rule fallback path.
    var events = streamEvents(admin, id, Map.of("question", "现在需要灌溉吗", "requestId", "stream-rule-1"), 200);
    assertEquals("run.completed", events[events.length - 1].path("type").asText());
    assertTrue(java.util.Arrays.stream(events).anyMatch(e -> "message.delta".equals(e.path("type").asText())));
    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    assertEquals(2, persisted.size());
    assertEquals("rule", persisted.get(1).path("MODE").asText());
    assertTrue(persisted.get(1).path("CONTENT").asText().contains("没有下发"));
  }

  @Test void streamDuplicateRequestIdReplaysWithoutDuplicateRowsOrModelCalls() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.answer = Optional.of(new LlmGateway.LlmResponse("只应该出现一次的回答。", List.of()));
    var question = Map.of("question", "请总结一下现状", "requestId", "stream-dup-1");
    var first = streamEvents(admin, id, question, 200);
    assertEquals(1, llm.calls.get());
    String firstMessageId = java.util.Arrays.stream(first).filter(e -> "message.completed".equals(e.path("type").asText())).findFirst().orElseThrow().path("messageId").asText();
    var replay = streamEvents(admin, id, question, 200);
    assertEquals(1, llm.calls.get(), "duplicate requestId must not call the model again");
    assertEquals("run.started", replay[0].path("type").asText());
    assertEquals("run.completed", replay[replay.length - 1].path("type").asText());
    StringBuilder replayedText = new StringBuilder();
    for (JsonNode e : replay) if ("message.delta".equals(e.path("type").asText())) replayedText.append(e.path("delta").asText());
    assertEquals("只应该出现一次的回答。", replayedText.toString());
    String replayMessageId = java.util.Arrays.stream(replay).filter(e -> "message.completed".equals(e.path("type").asText())).findFirst().orElseThrow().path("messageId").asText();
    assertEquals(firstMessageId, replayMessageId);
    assertEquals(2, call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200).size());
  }

  // Upstream failed AFTER it had already streamed some model text to the client: the partial text must
  // never be silently patched up with a rule-fallback tail (that would make the client's screen disagree
  // with the database). This must surface run.error and leave nothing persisted, and a retry with the
  // same requestId must still succeed normally afterwards.
  @Test void streamFailureAfterPartialModelTextEmitsRunErrorAndDoesNotPersistAnything() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.streamFailure = new RuntimeException("模拟上游中断");
    llm.streamPartialBeforeFailure = "部分模型正文";
    var events = streamEvents(admin, id, Map.of("question", "这次会失败", "requestId", "stream-fail-1"), 200);
    StringBuilder seenText = new StringBuilder();
    for (JsonNode e : events) if ("message.delta".equals(e.path("type").asText())) seenText.append(e.path("delta").asText());
    assertEquals("部分模型正文", seenText.toString(), "the partial model text the client saw");
    assertEquals("run.error", events[events.length - 1].path("type").asText());
    assertFalse(events[events.length - 1].path("error").asText().isBlank());
    assertTrue(java.util.Arrays.stream(events).noneMatch(e -> "message.completed".equals(e.path("type").asText()) || "run.completed".equals(e.path("type").asText())));
    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    assertEquals(0, persisted.size(), "a run interrupted mid-answer must not leave a persisted user/assistant message behind, partial or otherwise");
    // Retrying with the same requestId after the failure must still be possible (nothing was partially written).
    llm.streamFailure = null;
    llm.streamPartialBeforeFailure = null;
    llm.answer = Optional.of(new LlmGateway.LlmResponse("重试后的回答。", List.of()));
    var retried = streamEvents(admin, id, Map.of("question", "这次会失败", "requestId", "stream-fail-1"), 200);
    assertEquals("run.completed", retried[retried.length - 1].path("type").asText());
    assertEquals(2, call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200).size());
  }

  // Upstream failed before producing ANY text at all (e.g. connection refused, immediate exception):
  // that's equivalent to "model unavailable" in the sync path — it must fall back to the rule answer,
  // marked mode=rule, persisted and completed normally. It must NOT be reported as run.error.
  @Test void streamFailureBeforeAnyModelTextFallsBackToRuleAnswerLikeModelUnavailable() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.streamFailure = new RuntimeException("连接失败");
    var events = streamEvents(admin, id, Map.of("question", "现在需要灌溉吗", "requestId", "stream-unavailable-1"), 200);
    assertTrue(java.util.Arrays.stream(events).noneMatch(e -> "run.error".equals(e.path("type").asText())));
    assertEquals("run.completed", events[events.length - 1].path("type").asText());
    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    assertEquals(2, persisted.size());
    assertEquals("rule", persisted.get(1).path("MODE").asText());
    assertTrue(persisted.get(1).path("CONTENT").asText().contains("没有下发"));
  }

  @Test void streamCrossTenantAndUnauthorizedRequestsNeverStartAStream() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    var question = Map.of("question", "不应该被允许", "requestId", "stream-forbidden-1");
    streamEvents(null, id, question, 401);
    streamEvents(otherAdmin, id, question, 404);
    streamEvents(operator, id, question, 404);
    streamEvents(platform, id, question, 403);
    assertEquals(0, llm.calls.get());
    assertEquals(0, call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200).size());
  }

  @Test void syncMessagesEndpointStillWorksUnchangedAfterStreamingWasAdded() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.answer = Optional.of(new LlmGateway.LlmResponse("同步接口依旧可用。", List.of()));
    var response = call(admin, "POST", "/ai/conversations/" + id + "/messages", Map.of("question", "同步测试", "requestId", "sync-after-stream-1"), 200);
    assertEquals("同步接口依旧可用。", response.path("answer").asText());
    assertEquals("llm", response.path("mode").asText());
    assertEquals(2, call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200).size());
  }

  // ---------- 阶段F：会话列表与自动标题 ----------

  @Test void firstStreamedQuestionGetsModelGeneratedTitleAndEmitsConversationTitledEvent() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    assertEquals("新的农事对话", call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200).path(0).path("title").asText());
    llm.titleAnswer = Optional.of(new LlmGateway.LlmResponse("“灌溉建议咨询”。", List.of()));
    llm.answer = Optional.of(new LlmGateway.LlmResponse("基于当前农场记录的回答。", List.of()));
    var events = streamEvents(admin, id, Map.of("question", "现在需要灌溉吗", "requestId", "title-turn-1"), 200);
    assertEquals(1, llm.titleCalls.get(), "exactly one separate short title request, distinct from the main answer call");
    assertEquals(1, llm.calls.get());
    var titledEvents = java.util.Arrays.stream(events).filter(e -> "conversation.titled".equals(e.path("type").asText())).toList();
    // First an immediate fallback title (no model wait), then the model title once the answer is done.
    assertEquals(2, titledEvents.size());
    assertEquals("现在需要灌溉吗", titledEvents.get(0).path("title").asText());
    var titled = titledEvents.get(1);
    // Sanitized: surrounding quotes and the trailing full-width period must be stripped.
    assertEquals("灌溉建议咨询", titled.path("title").asText());
    assertEquals(id, titled.path("conversationId").asText());
    // The model title must not delay the answer: it arrives after run.completed.
    int completedSeq = java.util.Arrays.stream(events).filter(e -> "run.completed".equals(e.path("type").asText())).findFirst().orElseThrow().path("sequence").asInt();
    assertTrue(titledEvents.get(0).path("sequence").asInt() < completedSeq);
    assertTrue(titled.path("sequence").asInt() > completedSeq);
    var list = call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200);
    var row = findConversation(list, id);
    assertEquals("灌溉建议咨询", row.path("title").asText());
    assertEquals("auto", row.path("titleSource").asText());
  }

  @Test void autoTitleFallsBackToFirst16CharsWhenModelUnavailable() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    // llm.titleAnswer/llm.answer stay empty from @BeforeEach -> model unavailable for both title and answer.
    String question = "今天地块一的土壤水分看起来偏低该怎么办";
    var events = streamEvents(admin, id, Map.of("question", question, "requestId", "title-fallback-1"), 200);
    var titled = java.util.Arrays.stream(events).filter(e -> "conversation.titled".equals(e.path("type").asText())).findFirst().orElseThrow();
    assertEquals(question.substring(0, 16), titled.path("title").asText());
    assertEquals(0, llm.titleCalls.get(), "no model title request when the model already failed to answer");
    var row = findConversation(call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200), id);
    assertEquals(question.substring(0, 16), row.path("title").asText());
    assertEquals("auto", row.path("titleSource").asText());
  }

  @Test void userRenamedTitleIsNeverOverwrittenByAutoTitling() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    var renamed = call(admin, "POST", "/ai/conversations/" + id + "/rename", Map.of("title", "我自己起的标题"), 200);
    assertEquals("user", renamed.path("titleSource").asText());
    llm.titleAnswer = Optional.of(new LlmGateway.LlmResponse("模型想改的标题", List.of()));
    llm.answer = Optional.of(new LlmGateway.LlmResponse("回答正文。", List.of()));
    streamEvents(admin, id, Map.of("question", "第一次提问", "requestId", "title-user-1"), 200);
    // Renamed before the first message was ever asked: auto-titling must not even attempt a model call.
    assertEquals(0, llm.titleCalls.get());
    var row = findConversation(call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200), id);
    assertEquals("我自己起的标题", row.path("title").asText());
    assertEquals("user", row.path("titleSource").asText());
  }

  @Test void autoGeneratedTitleCannotExceedSixteenCharacters() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.titleAnswer = Optional.of(new LlmGateway.LlmResponse("这是一个明显超过十六个汉字限制的离谱标题文本内容", List.of()));
    llm.answer = Optional.of(new LlmGateway.LlmResponse("回答正文。", List.of()));
    var events = streamEvents(admin, id, Map.of("question", "随便问点什么", "requestId", "title-cap-1"), 200);
    var titled = java.util.Arrays.stream(events).filter(e -> "conversation.titled".equals(e.path("type").asText())).reduce((a, b) -> b).orElseThrow();
    assertEquals(1, llm.titleCalls.get());
    assertTrue(titled.path("title").asText().length() <= 16, "title must be capped at 16 characters: " + titled.path("title").asText());
    var row = findConversation(call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200), id);
    assertTrue(row.path("title").asText().length() <= 16);
  }

  @Test void secondQuestionInSameConversationNeverTriggersAutoTitling() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.answer = Optional.of(new LlmGateway.LlmResponse("第一轮回答。", List.of()));
    streamEvents(admin, id, Map.of("question", "第一个问题", "requestId", "title-seq-1"), 200);
    assertEquals(1, llm.titleCalls.get());
    llm.answer = Optional.of(new LlmGateway.LlmResponse("第二轮回答。", List.of()));
    var events = streamEvents(admin, id, Map.of("question", "第二个问题", "requestId", "title-seq-2"), 200);
    assertEquals(1, llm.titleCalls.get(), "a conversation only gets auto-titled once, on its first question");
    assertTrue(java.util.Arrays.stream(events).noneMatch(e -> "conversation.titled".equals(e.path("type").asText())));
  }

  @Test void renamePinAndDeleteAreTenantAndOwnerScoped() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    for (String token : List.of(otherAdmin, operator, viewer)) {
      call(token, "POST", "/ai/conversations/" + id + "/rename", Map.of("title", "不应该成功"), 404);
      call(token, "POST", "/ai/conversations/" + id + "/pin", Map.of("pinned", true), 404);
    }
    call(platform, "POST", "/ai/conversations/" + id + "/rename", Map.of("title", "平台不可写"), 403);
    call(admin, "POST", "/ai/conversations/" + id + "/rename", Map.of("title", "  两侧空白会被裁掉  "), 200);
    assertEquals("两侧空白会被裁掉", findConversation(call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200), id).path("title").asText());
    call(admin, "POST", "/ai/conversations/" + id + "/rename", Map.of("title", ""), 400);
    call(admin, "POST", "/ai/conversations/" + id + "/rename", Map.of("title", "x".repeat(41)), 400);
    call(admin, "POST", "/ai/conversations/" + id + "/rename", Map.of("title", "x".repeat(40)), 200);
  }

  @Test void pinnedConversationsSortBeforeUnpinnedRegardlessOfRecency() throws Exception {
    String older = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    String newer = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    // newer is more recently updated than older (just created, no messages needed for ordering by updated_at).
    call(admin, "POST", "/ai/conversations/" + older + "/pin", Map.of("pinned", true), 200);
    var list = call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200);
    // The pinned (older) conversation must sort first even though `newer` has a later updated_at.
    assertEquals(older, list.get(0).path("id").asText());
    assertNotNull(list.get(0).path("pinnedAt").asText(null));
    call(admin, "POST", "/ai/conversations/" + older + "/pin", Map.of("pinned", false), 200);
    var afterUnpin = call(admin, "GET", "/ai/conversations?farmId=" + farmId, null, 200);
    assertEquals(newer, afterUnpin.get(0).path("id").asText(), "after unpinning, the more recently updated conversation sorts first again");
  }

  JsonNode findConversation(JsonNode list, String id) {
    for (JsonNode row : list) if (id.equals(row.path("id").asText())) return row;
    throw new AssertionError("conversation " + id + " not found in list: " + list);
  }

  // ---------- Task 6: safe tool + approval activities in the stream ----------

  @Test void toolRoundNarrationNeverLeaksIntoTheFinalStreamedOrPersistedAnswer() throws Exception {
    // Live acceptance finding: the model sometimes narrates before calling a tool
    // ("我先查一下任务和问题列表。") and that narration — streamed as message.delta like any other
    // text — must never end up prefixed onto the final answer, neither in what the client displays
    // nor in what gets persisted. The server must emit message.reset to tell the client to discard it.
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.scriptedDeltaBeforeStream = new java.util.LinkedList<>(List.of("我先查一下任务和问题列表。"));
    llm.scriptedStream = new java.util.LinkedList<>(List.of(
      LlmGateway.StreamResult.toolCalls(List.of(new LlmGateway.ToolCall("call-1", "get_pending_tasks", "{}"))),
      LlmGateway.StreamResult.completed("结论：目前没有待办任务。")
    ));
    var events = streamEvents(admin, id, Map.of("question", "今天有哪些待办任务", "requestId", "stream-reset-1"), 200);

    var resetEvents = java.util.Arrays.stream(events).filter(e -> "message.reset".equals(e.path("type").asText())).toList();
    assertEquals(1, resetEvents.size(), "exactly one tool round must produce exactly one reset");
    assertEquals("", resetEvents.getFirst().path("text").asText());

    // The narration must have been streamed as a delta (proving this test actually exercises the
    // leak scenario) ...
    var deltas = java.util.Arrays.stream(events).filter(e -> "message.delta".equals(e.path("type").asText())).toList();
    assertTrue(deltas.stream().anyMatch(e -> e.path("delta").asText().contains("我先查一下")));
    // ... but every message.delta emitted *after* the reset must belong only to the final round's text,
    // and concatenating all of them (the only thing the client has any business doing) must reproduce
    // exactly the final answer, with no leaked narration prefix.
    int resetSeq = resetEvents.getFirst().path("sequence").asInt();
    StringBuilder afterReset = new StringBuilder();
    for (var e : deltas) if (e.path("sequence").asInt() > resetSeq) afterReset.append(e.path("delta").asText());
    assertEquals("结论：目前没有待办任务。", afterReset.toString());

    // And the persisted answer (what finalizeStream wrote to the DB) must match exactly — no
    // narration prefix sneaking into storage either.
    var messages = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    String persisted = messages.get(messages.size() - 1).path("CONTENT").asText();
    assertEquals("结论：目前没有待办任务。", persisted);
  }

  @Test void streamSurfacesToolActivitiesInOrderWithWhitelistedLabelsAndPersistsThem() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    // A second tenant's open issue must never leak into this tenant's "读取到 N 条" count.
    String otherFarm = call(otherAdmin, "POST", "/farms", Map.of("name", "其他租户农场-" + UUID.randomUUID(), "description", "虚构测试数据"), 200).path("ID").asText();
    String otherPlot = call(otherAdmin, "POST", "/plots", Map.of("farmId", otherFarm, "name", "其他地块", "crop", "蔬菜", "areaMu", 1), 200).path("ID").asText();
    call(otherAdmin, "POST", "/field-work/issues", Map.of("plotId", otherPlot, "category", "OTHER", "severity", "NORMAL", "description", "不应被看到"), 200);
    String plot = call(admin, "POST", "/plots", Map.of("farmId", farmId, "name", "测试地块", "crop", "蔬菜", "areaMu", 1), 200).path("ID").asText();
    call(admin, "POST", "/field-work/issues", Map.of("plotId", plot, "category", "OTHER", "severity", "NORMAL", "description", "测试现场问题"), 200);

    llm.scriptedStream = new java.util.LinkedList<>(List.of(
      LlmGateway.StreamResult.toolCalls(List.of(new LlmGateway.ToolCall("call-1", "get_open_issues", "{}"))),
      LlmGateway.StreamResult.completed("已读取现场问题，建议优先处理。")
    ));
    var events = streamEvents(admin, id, Map.of("question", "有没有未解决的问题", "requestId", "stream-tool-1"), 200);
    assertEquals(2, llm.calls.get(), "exactly one tool round then one final round");

    var started = java.util.Arrays.stream(events).filter(e -> "activity.started".equals(e.path("type").asText())).toList();
    var toolStarted = started.stream().filter(e -> "tool".equals(e.path("activity").path("kind").asText())).findFirst().orElseThrow();
    assertEquals("查询现场问题", toolStarted.path("activity").path("label").asText());
    assertEquals("running", toolStarted.path("activity").path("status").asText());

    var completed = java.util.Arrays.stream(events).filter(e -> "activity.completed".equals(e.path("type").asText())).toList();
    var toolCompleted = completed.stream().filter(e -> "tool".equals(e.path("activity").path("kind").asText())).findFirst().orElseThrow();
    assertEquals("completed", toolCompleted.path("activity").path("status").asText());
    assertEquals("读取到 1 条未处理问题", toolCompleted.path("activity").path("resultSummary").asText());
    assertFalse(toolCompleted.path("activity").has("detail") && !toolCompleted.path("activity").path("detail").isNull(), "tool result summary must never carry raw arguments/details");

    // Task: the tool result handed back to the model must use Chinese labels for status/severity
    // enum codes (OPEN/HIGH/...), never the raw English codes — otherwise the model just echoes them
    // straight into the answer (observed live: "ASSIGNED", "OPEN", "HIGH" leaking into Chinese prose).
    String toolReplyJson = llm.lastMessages.stream()
      .filter(m -> "tool".equals(m.get("role")))
      .map(m -> String.valueOf(m.get("content")))
      .findFirst()
      .orElseThrow();
    assertTrue(toolReplyJson.contains("待安排"), toolReplyJson);
    assertTrue(toolReplyJson.contains("常规跟进"), toolReplyJson);
    assertFalse(toolReplyJson.contains("\"OPEN\""), toolReplyJson);
    assertFalse(toolReplyJson.contains("\"NORMAL\""), toolReplyJson);

    // Task: "读取当前农场资料" must complete right after the context is built and BEFORE the model
    // is even called — not after the whole run (tool rounds + final generation) finishes, otherwise
    // its reported duration is actually the whole run's duration (observed live: 5.7s for a step that
    // does no network I/O at all). So activity.completed(context) must come before activity.started(tool).
    int toolStartedSeq = toolStarted.path("sequence").asInt();
    var contextCompleted = completed.stream().filter(e -> "context".equals(e.path("activity").path("kind").asText())).findFirst().orElseThrow();
    assertTrue(contextCompleted.path("sequence").asInt() < toolStartedSeq);

    // And a "task"-kind "生成回答" activity spans the tool round(s) + final generation round,
    // starting right after context and completing once the answer is ready — giving the model's own
    // thinking/generation time a duration separate from "reading farm data" and from each tool call.
    var answerStarted = started.stream().filter(e -> "task".equals(e.path("activity").path("kind").asText())).findFirst().orElseThrow();
    assertEquals("生成回答", answerStarted.path("activity").path("label").asText());
    assertTrue(contextCompleted.path("sequence").asInt() < answerStarted.path("sequence").asInt());
    assertTrue(answerStarted.path("sequence").asInt() < toolStartedSeq);
    var answerCompleted = completed.stream().filter(e -> "task".equals(e.path("activity").path("kind").asText())).findFirst().orElseThrow();
    assertEquals("completed", answerCompleted.path("activity").path("status").asText());

    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    assertEquals(2, persisted.size());
    var activities = persisted.get(1).path("activities");
    assertEquals(3, activities.size());
    assertEquals("context", activities.get(0).path("KIND").asText());
    assertEquals("tool", activities.get(1).path("KIND").asText());
    assertEquals("查询现场问题", activities.get(1).path("LABEL").asText());
    assertEquals("completed", activities.get(1).path("STATUS").asText());
    assertEquals("读取到 1 条未处理问题", activities.get(1).path("RESULT_SUMMARY").asText());
    assertEquals("task", activities.get(2).path("KIND").asText());
    assertEquals("生成回答", activities.get(2).path("LABEL").asText());
  }

  @Test void streamToolFailureEmitsErrorActivityAndRunStillCompletes() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    toolsFake.forcedFailureTool = "get_pending_tasks";
    llm.scriptedStream = new java.util.LinkedList<>(List.of(
      LlmGateway.StreamResult.toolCalls(List.of(new LlmGateway.ToolCall("call-1", "get_pending_tasks", "{}"))),
      LlmGateway.StreamResult.completed("工具失败后依然给出的回答。")
    ));
    var events = streamEvents(admin, id, Map.of("question", "有哪些待办", "requestId", "stream-tool-fail-1"), 200);
    assertEquals("run.completed", events[events.length - 1].path("type").asText(), "a tool failure must not abort the whole run");

    var toolCompleted = java.util.Arrays.stream(events)
      .filter(e -> "activity.completed".equals(e.path("type").asText()) && "tool".equals(e.path("activity").path("kind").asText()))
      .findFirst().orElseThrow();
    assertEquals("error", toolCompleted.path("activity").path("status").asText());
    assertEquals("读取失败，请重试", toolCompleted.path("activity").path("detail").asText());
    assertTrue(toolCompleted.path("activity").path("resultSummary").isMissingNode() || toolCompleted.path("activity").path("resultSummary").isNull());

    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    assertEquals(2, persisted.size());
    assertEquals("llm", persisted.get(1).path("MODE").asText());
    var activities = persisted.get(1).path("activities");
    var toolActivity = activities.get(1);
    assertEquals("error", toolActivity.path("STATUS").asText());
    assertEquals("读取失败，请重试", toolActivity.path("DETAIL").asText());
  }

  @Test void streamUnknownToolNameEmitsErrorActivityWithoutExecuting() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.scriptedStream = new java.util.LinkedList<>(List.of(
      LlmGateway.StreamResult.toolCalls(List.of(new LlmGateway.ToolCall("call-1", "delete_all_devices", "{}"))),
      LlmGateway.StreamResult.completed("忽略未知工具后给出的回答。")
    ));
    var events = streamEvents(admin, id, Map.of("question", "试试未知工具", "requestId", "stream-unknown-tool-1"), 200);
    assertEquals("run.completed", events[events.length - 1].path("type").asText());
    var toolCompleted = java.util.Arrays.stream(events)
      .filter(e -> "activity.completed".equals(e.path("type").asText()) && "tool".equals(e.path("activity").path("kind").asText()))
      .findFirst().orElseThrow();
    assertEquals("error", toolCompleted.path("activity").path("status").asText());
    assertEquals("不支持的操作", toolCompleted.path("activity").path("label").asText());
    assertTrue(java.util.Arrays.stream(events).noneMatch(e -> "activity.started".equals(e.path("type").asText()) && "tool".equals(e.path("activity").path("kind").asText())),
      "an unknown tool name must never reach an activity.started (running) state — it is never executed");
  }

  @Test void streamInterruptedDuringASecondToolRoundPersistsNothing() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    // Round 1 succeeds (tool executes normally); round 2's model call then fails after it had already
    // streamed partial text. Nothing may be persisted — same contract as a plain mid-answer interruption.
    llm.scriptedStream = new java.util.LinkedList<>(List.of(
      LlmGateway.StreamResult.toolCalls(List.of(new LlmGateway.ToolCall("call-1", "get_farm_summary", "{}")))
    ));
    llm.streamFailure = new RuntimeException("模拟上游中断");
    llm.streamPartialBeforeFailure = "部分正文";
    var events = streamEvents(admin, id, Map.of("question", "汇总一下", "requestId", "stream-tool-interrupt-1"), 200);
    assertEquals("run.error", events[events.length - 1].path("type").asText());
    assertTrue(java.util.Arrays.stream(events).anyMatch(e -> "activity.completed".equals(e.path("type").asText()) && "tool".equals(e.path("activity").path("kind").asText()) && "completed".equals(e.path("activity").path("status").asText())),
      "the first tool round did complete before the later interruption");
    assertEquals(0, call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200).size(), "an interrupted multi-round run must not persist anything, even if an earlier tool round succeeded");
  }

  @Test void streamEmitsApprovalActivityForPendingIrrigationProposalWithoutChangingItsState() throws Exception {
    var p = irrigationFixture();
    call(admin, "PUT", "/ai/irrigation/policy", p, 200);
    String runId = propose(p);
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.answer = Optional.of(new LlmGateway.LlmResponse("已汇总，请在灌溉管理页签确认建议。", List.of()));
    var events = streamEvents(admin, id, Map.of("question", "现在要不要灌溉", "requestId", "stream-approval-1"), 200);

    var approval = java.util.Arrays.stream(events)
      .filter(e -> "activity.completed".equals(e.path("type").asText()) && "approval".equals(e.path("activity").path("kind").asText()))
      .findFirst().orElseThrow();
    assertTrue(approval.path("activity").path("label").asText().contains("灌溉建议待确认"));
    assertEquals("irrigation-run:" + runId, approval.path("activity").path("id").asText());
    // 阶段 E：建议仍在等待人工确认，不能显示成已完成——状态是 pending，没有 finishedAt。
    assertEquals("pending", approval.path("activity").path("status").asText());
    assertTrue(approval.path("activity").path("finishedAt").isMissingNode() || approval.path("activity").path("finishedAt").isNull());
    assertFalse(approval.path("activity").path("resultSummary").asText().contains("。；"), "sentence-final punctuation must be deduplicated when concatenating reason segments");

    // Streaming only surfaces the proposal; it must still be PROPOSED, never approved/started by the chat.
    assertEquals("PROPOSED", db.queryForObject("SELECT status FROM ai_irrigation_runs WHERE tenant_id=? AND id=?", String.class, tenant(), runId));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND device_id=?", Integer.class, tenant(), p.get("pumpId")));

    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    var activities = persisted.get(1).path("activities");
    var approvalRow = activities.get(activities.size() - 1);
    assertEquals("approval", approvalRow.path("KIND").asText());
    assertEquals("irrigation-run:" + runId, approvalRow.path("ACTIVITY_ID").asText());
    assertEquals("pending", approvalRow.path("STATUS").asText());
    assertTrue(approvalRow.path("FINISHED_AT").isNull());
  }

  // 阶段 E：propose() 生成的建议原因文案里，百分比保留1位小数，不能把 NUMERIC 列的存储精度
  // （例如阈值存成 80.00）或传感器读数的多位小数（例如 45.266）原样暴露给用户。
  @Test void irrigationProposalReasonFormatsPercentagesToOneDecimal() throws Exception {
    var p = irrigationFixture();
    p.put("thresholdValue", 80);
    call(admin, "PUT", "/ai/irrigation/policy", p, 200);
    reading(p.get("sensorId").toString(), "SOIL_MOISTURE", 45, 0);
    db.update("UPDATE telemetry_readings SET measured_value=45.266 WHERE tenant_id=? AND device_id=? AND metric='SOIL_MOISTURE'", tenant(), p.get("sensorId"));
    String runId = propose(p);
    String reason = db.queryForObject("SELECT reason FROM ai_irrigation_runs WHERE tenant_id=? AND id=?", String.class, tenant(), runId);
    assertTrue(reason.contains("45.3%"), reason);
    assertTrue(reason.contains("80.0%"), reason);
    assertFalse(reason.matches(".*\\d\\.\\d{2,}%.*"), reason);
  }

  String login(String tenant, String username) throws Exception {
    return call(null, "POST", "/auth/login",
      Map.of("tenantCode", tenant, "username", username, "password", "Test-Only-Password-429!"), 200)
      .path("token").asText();
  }

  JsonNode call(String token, String method, String path, Object body, int expected) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
      .header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  @TestConfiguration
  static class ModelConfig {
    @Bean
    @Primary
    TestLlmGateway testLlmGateway() {
      return new TestLlmGateway();
    }

    @Bean
    @Primary
    TestAiStreamTools testAiStreamTools(FarmWorkspaceService farmWorkspace, FieldWorkService fieldWork, AgronomyAnalysis analysis, ObjectMapper json) {
      return new TestAiStreamTools(farmWorkspace, fieldWork, analysis, json);
    }
  }

  /** Task 6: mirrors {@link TestLlmGateway}'s override pattern so a single named tool can be made to
   * throw on demand, without touching real farm data or adding any production-only test hook. */
  static class TestAiStreamTools extends AiStreamTools {
    volatile String forcedFailureTool;

    TestAiStreamTools(FarmWorkspaceService farmWorkspace, FieldWorkService fieldWork, AgronomyAnalysis analysis, ObjectMapper json) {
      super(farmWorkspace, fieldWork, analysis, json);
    }

    @Override
    protected Object run(String name, String farmId) {
      if (name.equals(forcedFailureTool)) throw new RuntimeException("simulated tool failure for test " + name);
      return super.run(name, farmId);
    }
  }

  static class TestLlmGateway extends LlmGateway {
    volatile Optional<LlmResponse> answer = Optional.empty();
    volatile List<String> saved;
    final AtomicInteger calls = new AtomicInteger();
    volatile List<Map<String,Object>> lastMessages=List.of();
    // Task 5 streaming fixtures: when set, stream() throws instead of returning normally, simulating an
    // upstream failure/disconnect so tests can assert either rule-fallback (zero deltas emitted before
    // the failure) or run.error (some deltas already emitted) without touching real HTTP SSE parsing
    // (which is exercised for real by LlmGateway.stream itself, outside of this fake).
    volatile RuntimeException streamFailure;
    // When streamFailure is set, emit this text as a delta first (simulating a partial model answer)
    // before throwing. Left null to simulate a failure that never produced any text at all.
    volatile String streamPartialBeforeFailure;
    // Task 6: when non-null, each stream() call pops the next scripted StreamResult instead of using
    // `answer` — lets a test script a tool_calls round followed by a final text round. Once the queue
    // is empty, stream() falls back to the normal answer/streamFailure-driven behavior below (so a
    // scripted tool_calls round can be followed by a scripted or injected failure for the final round).
    volatile java.util.Deque<StreamResult> scriptedStream;
    // Parallel queue to scriptedStream: when non-empty, pops one entry per stream() call and emits it
    // as a listener.onDelta() *before* returning that call's scripted StreamResult — simulates a model
    // that narrates ("我先查一下任务和问题列表。") before its tool_calls finish_reason arrives, so tests
    // can assert that narration never leaks into the persisted/streamed final answer (message.reset).
    volatile java.util.Deque<String> scriptedDeltaBeforeStream;

    @Override
    public Optional<LlmResponse> complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
      calls.incrementAndGet();
      lastMessages=List.copyOf(messages);
      return answer;
    }

    // 阶段F：标题走独立的 shortTitle()，单独计数，不影响既有测试对 calls 的精确断言。
    @Override
    public Optional<String> shortTitle(List<Map<String, Object>> messages) {
      titleCalls.incrementAndGet();
      lastTitleMessages = List.copyOf(messages);
      return titleAnswer.map(LlmResponse::content);
    }

    volatile Optional<LlmResponse> titleAnswer = Optional.empty();
    final AtomicInteger titleCalls = new AtomicInteger();
    volatile List<Map<String,Object>> lastTitleMessages = List.of();

    @Override
    public StreamResult stream(List<Map<String, Object>> messages, List<Map<String, Object>> tools, StreamListener listener, java.util.function.BooleanSupplier cancelled) {
      calls.incrementAndGet();
      lastMessages=List.copyOf(messages);
      if (scriptedStream != null && !scriptedStream.isEmpty()) {
        if (scriptedDeltaBeforeStream != null && !scriptedDeltaBeforeStream.isEmpty()) {
          String narration = scriptedDeltaBeforeStream.poll();
          if (narration != null && !narration.isEmpty()) listener.onDelta(narration);
        }
        StreamResult scripted = scriptedStream.poll();
        // Mirror the real gateway's contract: a COMPLETED/INTERRUPTED result's content was already
        // streamed via listener.onDelta before the result itself is returned — AiStreamService
        // accumulates text only from those callbacks, never from result.content() directly.
        if (scripted.content() != null && !scripted.content().isEmpty()) listener.onDelta(scripted.content());
        return scripted;
      }
      if (streamFailure != null) {
        if (streamPartialBeforeFailure != null) listener.onDelta(streamPartialBeforeFailure);
        throw streamFailure;
      }
      if (answer.isEmpty() || answer.get().content() == null) return StreamResult.unavailable("NOT_CONFIGURED");
      String text = answer.get().content();
      // Split into a couple of chunks so ordering/accumulation of message.delta is actually exercised.
      int mid = Math.max(1, text.length() / 2);
      for (String chunk : List.of(text.substring(0, mid), text.substring(mid))) {
        if (cancelled.getAsBoolean()) return StreamResult.interrupted(text.substring(0, Math.min(mid, text.length())), "CANCELLED");
        if (!chunk.isEmpty()) listener.onDelta(chunk);
      }
      return StreamResult.completed(text);
    }

    @Override
    public Optional<String> chat(String system, String user) {
      calls.incrementAndGet();
      return answer.map(LlmResponse::content);
    }

    volatile Boolean savedClear;

    @Override
    public void saveConfig(String url, String key, String model, boolean clearApiKey) {
      saved = Arrays.asList(url, key, model);
      savedClear = clearApiKey;
    }

    @Override
    public String test(String url, String key, String model) {
      calls.incrementAndGet();
      return "OK";
    }

    @Override
    public String url() { return ""; }

    // Task: /api/ai/status now reports the configured model id (never the url or key) so the header
    // badge can show "云端模型 · deepseek-flash" instead of a generic label. Defaults keep every other
    // existing test's assumption (no cloud model configured) unchanged; a dedicated test flips these.
    volatile String modelValue = "";
    volatile boolean cloudEnabledValue = false;

    @Override
    public String model() { return modelValue; }

    @Override
    public boolean apiKeySet() { return false; }

    @Override
    public boolean cloudEnabled() { return cloudEnabledValue; }

    volatile List<String> modelOptionsValue = new ArrayList<>(List.of());

    @Override
    public synchronized List<String> modelOptions() {
      return List.copyOf(modelOptionsValue);
    }

    @Override
    public synchronized List<String> mergeModelOptions(List<String> ids) {
      calls.incrementAndGet();
      for (String raw : ids == null ? List.<String>of() : ids) {
        String id = raw == null ? "" : raw.trim();
        if (!id.isEmpty() && !modelOptionsValue.contains(id)) modelOptionsValue.add(id);
      }
      return List.copyOf(modelOptionsValue);
    }

    @Override
    public synchronized List<String> removeModelOption(String id) {
      calls.incrementAndGet();
      modelOptionsValue.remove(id == null ? "" : id.trim());
      return List.copyOf(modelOptionsValue);
    }

    volatile LlmGateway.FetchModelsResult fetchModelsResult =
      new LlmGateway.FetchModelsResult(List.of("test-model-a", "test-model-b"), "OK");

    @Override
    public LlmGateway.FetchModelsResult fetchModels(String url, String apiKey) {
      calls.incrementAndGet();
      return fetchModelsResult;
    }
  }
}
