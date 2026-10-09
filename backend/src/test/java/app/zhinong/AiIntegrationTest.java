package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.ai.LlmGateway;
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

  String tenant() {return db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'",String.class);}
  Map<String,Object> irrigationFixture() throws Exception {
    String plot=call(admin,"POST","/plots",Map.of("farmId",farmId,"name","虚构蔬菜地","crop","蔬菜","areaMu",5),200).path("ID").asText();
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

  @Test void streamUpstreamFailureEmitsRunErrorAndDoesNotPersistAnAssistantSuccessRow() throws Exception {
    String id = call(admin, "POST", "/ai/conversations", Map.of("farmId", farmId), 200).path("id").asText();
    llm.streamFailure = new RuntimeException("模拟上游中断");
    var events = streamEvents(admin, id, Map.of("question", "这次会失败", "requestId", "stream-fail-1"), 200);
    assertEquals("run.error", events[events.length - 1].path("type").asText());
    assertFalse(events[events.length - 1].path("error").asText().isBlank());
    var persisted = call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200);
    assertEquals(0, persisted.size(), "a failed run must not leave a persisted user/assistant message behind");
    // Retrying with the same requestId after the failure must still be possible (nothing was partially written).
    llm.streamFailure = null;
    llm.answer = Optional.of(new LlmGateway.LlmResponse("重试后的回答。", List.of()));
    var retried = streamEvents(admin, id, Map.of("question", "这次会失败", "requestId", "stream-fail-1"), 200);
    assertEquals("run.completed", retried[retried.length - 1].path("type").asText());
    assertEquals(2, call(admin, "GET", "/ai/conversations/" + id + "/messages", null, 200).size());
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
  }

  static class TestLlmGateway extends LlmGateway {
    volatile Optional<LlmResponse> answer = Optional.empty();
    volatile List<String> saved;
    final AtomicInteger calls = new AtomicInteger();
    volatile List<Map<String,Object>> lastMessages=List.of();
    // Task 5 streaming fixtures: when set, stream() throws instead of returning, simulating an
    // upstream failure/disconnect so tests can assert run.error without touching real HTTP SSE parsing
    // (which is exercised for real by LlmGateway.stream itself, outside of this fake).
    volatile RuntimeException streamFailure;

    @Override
    public Optional<LlmResponse> complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
      calls.incrementAndGet();
      lastMessages=List.copyOf(messages);
      return answer;
    }

    @Override
    public Optional<LlmResponse> stream(List<Map<String, Object>> messages, List<Map<String, Object>> tools, StreamListener listener, java.util.function.BooleanSupplier cancelled) {
      calls.incrementAndGet();
      lastMessages=List.copyOf(messages);
      if (streamFailure != null) throw streamFailure;
      if (answer.isEmpty() || answer.get().content() == null) return Optional.empty();
      String text = answer.get().content();
      // Split into a couple of chunks so ordering/accumulation of message.delta is actually exercised.
      int mid = Math.max(1, text.length() / 2);
      for (String chunk : List.of(text.substring(0, mid), text.substring(mid))) {
        if (cancelled.getAsBoolean()) return Optional.empty();
        if (!chunk.isEmpty()) listener.onDelta(chunk);
      }
      return answer;
    }

    @Override
    public Optional<String> chat(String system, String user) {
      calls.incrementAndGet();
      return answer.map(LlmResponse::content);
    }

    @Override
    public void saveConfig(String url, String key, String model) {
      saved = Arrays.asList(url, key, model);
    }

    @Override
    public String url() { return ""; }

    @Override
    public String model() { return ""; }

    @Override
    public boolean apiKeySet() { return false; }

    @Override
    public boolean cloudEnabled() { return false; }
  }
}
