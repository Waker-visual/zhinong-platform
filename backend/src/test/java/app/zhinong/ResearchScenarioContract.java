package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.bootstrap.ResearchDemoData;
import app.zhinong.database.DatabaseSql;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

abstract class ResearchScenarioContract {
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired ResearchDemoData generator;
  @Autowired DatabaseSql sql;
  @Autowired org.springframework.context.ApplicationContext context;
  @LocalServerPort int port;
  final HttpClient http=HttpClient.newHttpClient();
  String tenant(){return db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'",String.class);}
  String farm(){return db.queryForObject("SELECT farm_id FROM demo_research_farms WHERE tenant_id=?",String.class,tenant());}
  JsonNode call(String token,String method,String route,Object body,int status)throws Exception{
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+route)).header("Content-Type","application/json").timeout(Duration.ofSeconds(30));
    if(token!=null)request.header("Authorization","Bearer "+token);
    request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(status,response.statusCode(),response.body());return json.readTree(response.body());
  }
  String login(String tenant,String username)throws Exception{return call(null,"POST","/auth/login",Map.of("tenantCode",tenant,"username",username,"password","Test-Only-Password-429!"),200).path("token").asText();}

  @Test void aiAnalysisAndPersistentStateArePortableAndScoped() throws Exception {
    String token=login("demo-a","admin"),id=farm();
    var analysis=call(token,"GET","/ai/analysis?farmId="+id,null,200);
    assertEquals(4,analysis.path("conditions").size());assertFalse(analysis.path("weather").isEmpty());
    var days=new java.util.HashSet<String>();analysis.path("weather").forEach(row->days.add(row.path("date").asText()));
    assertTrue(days.size()<=7);assertTrue(days.contains(LocalDate.now().toString()));
    assertTrue(analysis.at("/production/records").asInt()>30);
    call(login("demo-b","admin"),"GET","/ai/analysis?farmId="+id,null,404);
    String conversation=call(token,"POST","/ai/conversations",Map.of("farmId",id),200).path("id").asText();
    call(token,"POST","/ai/conversations/"+conversation+"/messages",Map.of("question","分析四情数据","requestId","portable-1"),200);
    assertEquals(2,call(token,"GET","/ai/conversations/"+conversation+"/messages",null,200).size());
    call(token,"DELETE","/ai/conversations/"+conversation,null,200);
    String plot=db.queryForObject("SELECT s.plot_id FROM plantings s JOIN plots p ON p.tenant_id=s.tenant_id AND p.id=s.plot_id WHERE s.tenant_id=? AND p.farm_id=? AND s.status='ACTIVE' LIMIT 1",String.class,tenant(),id);
    String sensor=db.queryForObject("SELECT device_id FROM asset_profiles WHERE tenant_id=? AND plot_id=? AND device_type='SOIL' LIMIT 1",String.class,tenant(),plot);
    String pump=db.queryForObject("SELECT a.device_id FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id WHERE a.tenant_id=? AND d.farm_id=? AND a.device_type='PUMP' AND a.control_enabled=TRUE LIMIT 1",String.class,tenant(),id);
    var policy=new LinkedHashMap<String,Object>();policy.put("farmId",id);policy.put("plotId",plot);policy.put("sensorId",sensor);policy.put("pumpId",pump);policy.put("mode","MANUAL");policy.put("thresholdValue",30);policy.put("durationSeconds",60);policy.put("cooldownMinutes",120);policy.put("dailyLimit",3);policy.put("revision",0);
    call(token,"PUT","/ai/irrigation/policy",policy,200);
    assertFalse(call(token,"GET","/ai/irrigation?farmId="+id,null,200).path("policies").isEmpty());
  }

  @Test void startupReadinessPreventsPrematureEmptyBusinessResponses() throws Exception {
    assertTrue(call(null,"GET","/health",null,200).path("ready").asBoolean());
    org.springframework.boot.availability.AvailabilityChangeEvent.publish(context,org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);
    try {
      assertFalse(call(null,"GET","/health",null,503).path("ready").asBoolean());
      call(null,"POST","/auth/login",Map.of("tenantCode","demo-a","username","admin","password","Test-Only-Password-429!"),503);
    } finally {
      org.springframework.boot.availability.AvailabilityChangeEvent.publish(context,org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  @Test void twoYearsAreLinkedSeasonalAndTenantScoped()throws Exception{
    String token=login("demo-a","admin"),id=farm();
    var report=call(token,"GET","/farms/"+id+"/research-data",null,200);
    assertTrue(report.path("consistent").asBoolean(),report.toString());
    assertEquals(LocalDate.now().minusYears(2).toString(),report.at("/manifest/historyStart").asText());
    assertTrue(report.at("/counts/farm_tasks").asInt()>350);
    assertTrue(report.at("/counts/production").asInt()>30);
    assertEquals(11,call(token,"GET","/assets?farmId="+id,null,200).size());
    var work=call(token,"GET","/field-work?farmId="+id,null,200);
    assertTrue(work.path("tasks").get(0).has("ASSIGNEE_ID"));
    assertTrue(work.path("issues").size()>30);
    for(String route:List.of("/workspace","/analytics?days=730","/operations?hours=720")) call(token,"GET","/farms/"+id+route,null,200);
    call(login("demo-b","admin"),"GET","/farms/"+id+"/research-data",null,404);
    call(login("platform","platform"),"GET","/farms/"+id+"/research-data",null,403);
    call(null,"GET","/system/database",null,401);
    var status=call(token,"GET","/system/database",null,200);
    assertTrue(status.path("connected").asBoolean()); assertFalse(status.has("url"));assertFalse(status.has("password"));
    var sim=call(token,"GET","/simulations/catalog",null,200);
    assertTrue(sim.at("/storage/weatherRows").asInt()>0);
  }

  @Test @Transactional void reseedingPreservesUserEditsAndHarvestReferences() {
    String tenant=tenant(),farm=farm();
    long count=db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=?",Long.class,tenant);
    String task=db.queryForObject("SELECT MIN(id) FROM farm_tasks WHERE tenant_id=?",String.class,tenant);
    db.update("UPDATE farm_tasks SET note='用户保存的记录' WHERE tenant_id=? AND id=?",tenant,task);
    generator.initialize();generator.initialize();
    assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=?",Long.class,tenant));
    assertEquals("用户保存的记录",db.queryForObject("SELECT note FROM farm_tasks WHERE tenant_id=? AND id=?",String.class,tenant,task));
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM demo_research_farms WHERE tenant_id=? AND farm_id=?",Integer.class,tenant,farm));
  }

  @Test void telemetryAndTimeBucketsWorkAcrossDatabaseEngines()throws Exception{
    String token=login("demo-a","admin"),id=farm();
    var assets=call(token,"GET","/assets?farmId="+id,null,200);
    for(var asset:assets) {
      String device=asset.path("id").asText();
      call(token,"POST","/assets/"+device+"/collect",Map.of(),200);
      for(int hours:List.of(24,720)) {
        var history=call(token,"GET","/assets/"+device+"/history?metric="+asset.path("primaryMetric").asText()+"&hours="+hours,null,200);
        assertFalse(history.path("points").isEmpty());
        assertEquals(hours <= 24 ? "MINUTE" : "HOUR", history.path("aggregation").asText());
        var newest = history.path("points").get(history.path("points").size()-1);
        Instant bucketTime=OffsetDateTime.parse(newest.path("time").asText()).toInstant();
        assertFalse(bucketTime.isAfter(Instant.now().plusSeconds(5)), "A current sample must not move into the future across SQL time zones");
        assertTrue(bucketTime.isAfter(Instant.now().minusSeconds(hours<=24?65:3605)), "A current sample must remain in the current aggregation bucket");
      }
    }
    var operations=call(token,"GET","/farms/"+id+"/operations?hours=720",null,200);
    assertEquals(11,operations.at("/summary/freshDevices").asInt());
  }

  @Test @Transactional void upsertsKeepCompositeTenantKeysAndBooleanTypes() {
    String tenant=tenant(),farm=farm();
    String plot=db.queryForObject("SELECT MIN(id) FROM plots WHERE tenant_id=? AND farm_id=?",String.class,tenant,farm);
    String query=sql.upsert("plot_shapes","tenant_id,plot_id","tenant_id,plot_id,boundary_json");
    db.update(query,tenant,plot,"[[1,2],[3,4],[5,6]]");db.update(query,tenant,plot,"[[6,5],[4,3],[2,1]]");
    assertEquals("[[6,5],[4,3],[2,1]]",db.queryForObject("SELECT boundary_json FROM plot_shapes WHERE tenant_id=? AND plot_id=?",String.class,tenant,plot));
    var row=db.queryForMap("SELECT enabled AS \"enabled\",id AS \"id\" FROM tenants WHERE id=?",tenant);
    assertEquals(true,row.get("enabled"));assertTrue(row.containsKey("id"));
    var now=java.sql.Timestamp.from(Instant.now());
    String audit=UUID.randomUUID().toString();
    db.update("INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,'TEST','TIME_TEST',?,?)",audit,tenant,farm,now);
    var roundtrip=db.queryForObject("SELECT occurred_at FROM audit_events WHERE tenant_id=? AND id=?",java.sql.Timestamp.class,tenant,audit);
    assertTrue(Duration.between(now.toInstant(),roundtrip.toInstant()).abs().toMillis()<1000,
      "Timestamp round trip: " + now + " -> " + roundtrip + "; session=" + (sql.mysql()?db.queryForObject("SELECT @@session.time_zone",String.class):"H2"));
    var local=LocalDateTime.now().withNano(0);
    db.update("UPDATE audit_events SET occurred_at=? WHERE tenant_id=? AND id=?",local,tenant,audit);
    assertEquals(local,db.queryForObject("SELECT occurred_at FROM audit_events WHERE tenant_id=? AND id=?",java.sql.Timestamp.class,tenant,audit).toLocalDateTime());
    var serverNow=db.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class);
    assertTrue(Math.abs(Duration.between(Instant.now(),serverNow.toInstant()).toSeconds())<3);
  }

  @Test void currentWorkCanBeCompletedAndSimulatedGateControlPersists() throws Exception {
    String token=login("demo-a","admin"),id=farm();
    var work=call(token,"GET","/field-work?farmId="+id,null,200);
    JsonNode pending=null;
    for(var task:work.path("tasks")) if(task.path("STATUS").asText().equals("PENDING") && task.path("METHOD").asText().equals("MANUAL")) { pending=task;break; }
    assertNotNull(pending);
    String route="/field-work/tasks/"+pending.path("ID").asText()+"/progress";
    call(token,"PATCH",route,Map.of("status","RUNNING","method","MANUAL","note","研究回归：开始作业","actualAreaMu",0),200);
    call(token,"PATCH",route,Map.of("status","COMPLETED","method","MANUAL","note","研究回归：完成并保存回执","actualAreaMu",pending.path("PLOT_AREA_MU").decimalValue()),200);
    JsonNode gate=null;
    for(var asset:call(token,"GET","/assets?farmId="+id,null,200)) if(asset.path("deviceType").asText().equals("GATE")) gate=asset;
    assertNotNull(gate);
    String device=gate.path("id").asText();
    call(token,"POST","/assets/"+device+"/collect",Map.of(),200);
    var command=call(token,"POST","/assets/"+device+"/commands",Map.of("requestId",UUID.randomUUID().toString(),"action","SET_OPENING","value",35,"note","研究回归模拟控制"),200);
    assertEquals("SUCCEEDED",command.path("status").asText());
    var sampled=call(token,"POST","/assets/"+device+"/collect",Map.of(),200);
    boolean found=false;
    for(var channel:sampled.path("channels")) if(channel.path("metric").asText().equals("GATE_OPENING")) {assertEquals(35,channel.at("/latest/value").asInt());found=true;}
    assertTrue(found);
    assertTrue(call(token,"GET","/farms/"+id+"/research-data",null,200).path("consistent").asBoolean());
  }
}
