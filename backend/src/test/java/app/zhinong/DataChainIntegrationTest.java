package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.ai.FarmDecisionSupport;
import app.zhinong.business.FarmClock;
import app.zhinong.security.Identity;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

/** Cross-interface arithmetic uses one synthetic ledger, including legacy invalid dates and voids. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
  "spring.datasource.url=jdbc:h2:mem:data-chain-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=false", "farm.demo-live=false",
  "farm.alerts.automation-enabled=false",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class DataChainIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired FarmClock clock;
  @Autowired FarmDecisionSupport decisions;
  final HttpClient http=HttpClient.newHttpClient();
  String admin, tenant, member, farm, plot, planting;

  JsonNode call(String method,String route,Object body,int status)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+route))
      .timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
    if(admin!=null)request.header("Authorization","Bearer "+admin);
    request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(status,response.statusCode(),response.body());return json.readTree(response.body());
  }
  @BeforeEach void prepare()throws Exception {
    admin=null;admin=call("POST","/auth/login",Map.of("tenantCode","demo-a","username","admin","password","Test-Only-Password-429!"),200).path("token").asText();
    tenant=db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'",String.class);
    member=db.queryForObject("SELECT id FROM members WHERE tenant_id=? AND username='admin'",String.class,tenant);
    farm=farm();plot=plot(farm,"链路地块");
    planting=call("POST","/plantings",Map.of("plotId",plot,"crop","水稻","variety","虚构链路品种","areaMu",5,
      "startDate",clock.today().minusDays(90).toString(),"endDate",clock.today().plusDays(2).toString()),200).path("ID").asText();
  }
  String farm()throws Exception {
    return call("POST","/farms",Map.of("name","虚构链路场-"+UUID.randomUUID(),"description","虚构链路验收"),200).path("ID").asText();
  }
  String plot(String farmId,String name)throws Exception {
    return call("POST","/plots",Map.of("farmId",farmId,"name",name,"crop","水稻","areaMu",10),200).path("ID").asText();
  }
  String rawHarvest(String targetPlot,LocalDate date,int amount) {
    String id=UUID.randomUUID().toString();
    db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",id,tenant,targetPlot,date,amount,"虚构链路旧记录");
    return id;
  }
  Map<String,Object> harvest(int amount) {
    var body=new LinkedHashMap<String,Object>();body.put("plotId",plot);body.put("recordDate",clock.today().toString());
    body.put("yieldKg",amount);body.put("note","虚构链路收获");body.put("plantingId",planting);body.put("requestId",UUID.randomUUID().toString());return body;
  }
  String completedHarvest(double area)throws Exception {
    String task=call("POST","/field-work/tasks",Map.of("plotId",plot,"title","虚构收获任务","taskType","HARVEST",
      "assigneeId",member,"dueDate",clock.today().toString(),"method","MANUAL","note","虚构链路验收"),200).path("id").asText();
    call("PATCH","/field-work/tasks/"+task+"/progress",Map.of("status","RUNNING","note","开始核验","method","MANUAL","actualAreaMu",0),200);
    call("PATCH","/field-work/tasks/"+task+"/progress",Map.of("status","COMPLETED","note","已称重并核对地块","method","MANUAL","actualAreaMu",area),200);
    return task;
  }

  @Test void registeredYieldAgreesAcrossWindowsAndNeverMultipliesThroughLineage()throws Exception {
    double before=call("GET","/dashboard",null,200).path("yieldKg").asDouble();
    LocalDate today=clock.today();
    String current=rawHarvest(plot,today,100);rawHarvest(plot,today.minusDays(29),50);
    rawHarvest(plot,today.minusDays(30),70);rawHarvest(plot,today.minusDays(60),80);
    String future=rawHarvest(plot,today.plusDays(1),900);
    String another=plot(farm,"第二地块");rawHarvest(another,today.minusDays(1),21);
    String voided=call("POST","/production",harvest(777),200).path("ID").asText();
    call("POST","/production/"+voided+"/void",Map.of("reason","虚构作废验收","expectedRevision",0),200);
    String task=completedHarvest(5);
    // Both old and new lineage metadata may exist for one physical harvest: neither is additive production.
    db.update("INSERT INTO production_lineage(tenant_id,production_id,planting_id,task_id) VALUES(?,?,?,?)",tenant,current,planting,task);
    db.update("INSERT INTO production_tracking(tenant_id,production_id,planting_id,task_id,status,revision,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?)",tenant,current,planting,task,clock.now());
    assertEquals(321,call("GET","/dashboard",null,200).path("yieldKg").asDouble()-before,0.01);
    var analytics=call("GET","/farms/"+farm+"/analytics?days=30",null,200);
    assertEquals(171,analytics.at("/summary/yieldKg").asDouble(),0.01);
    assertEquals(3,analytics.at("/summary/productionRecords").asInt());
    double trend=0,byPlot=0;
    for(var item:analytics.path("productionTrend"))trend+=item.path("value").asDouble();
    for(var item:analytics.path("productionByPlot"))byPlot+=item.path("value").asDouble();
    assertEquals(171,trend,0.01);assertEquals(trend,byPlot,0.01);
    assertEquals(171,call("GET","/farms/"+farm+"/workspace?days=30",null,200).at("/analytics/summary/yieldKg").asDouble(),0.01);
    var pipeline=call("GET","/farms/"+farm+"/operations?hours=24",null,200).at("/pipeline/harvest");
    assertEquals(171,pipeline.path("yieldKg").asDouble(),0.01);assertEquals(3,pipeline.path("records").asInt());
    assertEquals(today.minusDays(29).toString(),pipeline.path("fromDate").asText());assertEquals(today.toString(),pipeline.path("toDate").asText());
    assertEquals(321,call("GET","/ai/analysis?farmId="+farm,null,200).at("/production/yieldKg").asDouble(),0.01);
    Identity.runAs(new Identity(member,tenant,"虚构验收租户","admin","管理员","ADMIN"),()->{
      var report=json.valueToTree(decisions.productionComparison(farm));
      assertEquals(141,report.at("/currentPeriod/yieldKg").asDouble(),0.01);
      assertEquals(80,report.at("/previousPeriod/yieldKg").asDouble(),0.01);
      double total=0;for(var item:report.path("byPlot"))total+=item.path("yieldKg").asDouble();assertEquals(141,total,0.01);
    });
    var briefing=call("GET","/ai/briefings/today?farmId="+farm,null,200);
    assertEquals(121,briefing.at("/metrics/yieldKg7d").asDouble(),0.01);
    assertEquals(2,briefing.at("/metrics/productionRecords7d").asInt());
    assertEquals(7,call("GET","/production?farmId="+farm,null,200).size());
    assertEquals(today.plusDays(1),db.queryForObject("SELECT record_date FROM production WHERE tenant_id=? AND id=?",LocalDate.class,tenant,future),"Legacy invalid data stays available for correction");
  }

  @Test void archivedRecordsRemainHistoryAndCannotBeMovedIntoActiveTotalsByCorrection()throws Exception {
    var original=harvest(44);String id=call("POST","/production",original,200).path("ID").asText();
    String targetFarm=farm(),targetPlot=plot(targetFarm,"接收地块");
    db.update("INSERT INTO farm_archives(tenant_id,farm_id,reason,archived_at) VALUES(?,?,?,?)",tenant,farm,"虚构归档验收",clock.now());
    var correction=new LinkedHashMap<>(original);correction.remove("requestId");correction.remove("plantingId");
    correction.put("plotId",targetPlot);correction.put("reason","尝试迁移归档数据");correction.put("expectedRevision",0);
    call("PUT","/production/"+id,correction,409);
    for(var record:call("GET","/production",null,200))assertNotEquals(id,record.path("ID").asText());
    assertEquals(id,call("GET","/production?farmId="+farm,null,200).get(0).path("ID").asText());
    assertEquals(1,call("GET","/production/"+id+"/history",null,200).size());
    assertEquals(0,call("GET","/farms/"+targetFarm+"/analytics",null,200).at("/summary/yieldKg").asDouble());
  }

  @Test void plotsWithHarvestHistoryCannotMoveFarmsWhileEmptyPlotsCan()throws Exception {
    String target=farm(),recordPlot=plot(farm,"只有旧收获的地块");rawHarvest(recordPlot,clock.today(),40);
    var update=Map.of("farmId",target,"name","移场验收","areaMu",10,"crop","玉米");
    call("PUT","/plots/"+recordPlot,update,409);call("PUT","/plots/"+plot,update,409);
    String empty=plot(farm,"可迁移空地块");
    assertEquals(target,call("PUT","/plots/"+empty,update,200).path("FARM_ID").asText());
    assertEquals(40,call("GET","/farms/"+farm+"/analytics",null,200).at("/summary/yieldKg").asInt());
    assertEquals(0,call("GET","/farms/"+target+"/analytics",null,200).at("/summary/yieldKg").asInt());
  }

  @Test void harvestAssociationsRequireCompleteReceiptsWithinTheChosenBatchArea()throws Exception {
    String tooLarge=completedHarvest(8);
    var body=harvest(20);body.put("taskId",tooLarge);call("POST","/production",body,400);
    String task=completedHarvest(5);body.put("taskId",task);
    db.update("UPDATE task_fieldwork SET completion_note='' WHERE tenant_id=? AND task_id=?",tenant,task);
    call("POST","/production",body,409);
    db.update("UPDATE task_fieldwork SET completion_note='虚构完整回执' WHERE tenant_id=? AND task_id=?",tenant,task);
    assertEquals(task,call("POST","/production",body,200).path("TASK_ID").asText());
    // No silent resizing of history to fit a newly entered plot area.
    call("PUT","/plots/"+plot,Map.of("farmId",farm,"name","链路地块","areaMu",6,"crop","水稻"),409);
  }

  @Test void concurrentCorrectionsCannotBothConsumeTheSameRevision()throws Exception {
    var original=harvest(100);String id=call("POST","/production",original,200).path("ID").asText();
    var update=new LinkedHashMap<>(original);update.remove("requestId");update.put("reason","并发称重复核");update.put("expectedRevision",0);
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/production/"+id))
      .header("Authorization","Bearer "+admin).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(update))).build();
    var first=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());var second=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
    var statuses=new ArrayList<>(List.of(first.get(10,TimeUnit.SECONDS).statusCode(),second.get(10,TimeUnit.SECONDS).statusCode()));
    Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
    assertEquals(2,call("GET","/production/"+id+"/history",null,200).size());
  }

  @Test void workspaceTotalsStayCompleteWhenListsAreTruncatedAndCancelledTasksAreSeparate()throws Exception {
    String device=call("POST","/devices",Map.of("farmId",farm,"name","虚构告警设备","metric","TEMPERATURE","adapter","SIMULATED"),200).path("ID").asText();
    for(int index=0;index<206;index++)db.update("""
      INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,status,opened_at,updated_at)
      VALUES(?,?,?,'TEMPERATURE',50,'虚构分页验收告警',?,?,?)
      """,UUID.randomUUID().toString(),tenant,device,index==205?"RESOLVED":"OPEN",clock.now(),clock.now());
    for(int index=0;index<105;index++)db.update("""
      INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note)
      VALUES(?,?,?,'虚构分页验收任务','INSPECTION',?,?,'')
      """,UUID.randomUUID().toString(),tenant,plot,clock.today(),index==0?"CANCELLED":index==1?"COMPLETED":"PENDING");
    var workspace=call("GET","/farms/"+farm+"/workspace?days=7",null,200);
    assertEquals(205,workspace.path("alertTotal").asInt());assertEquals(200,workspace.path("alerts").size());
    assertTrue(workspace.path("alertsTruncated").asBoolean());
    var briefing=call("GET","/ai/briefings/today?farmId="+farm,null,200);
    assertEquals(205,briefing.at("/metrics/activeAlerts").asInt());
    Identity.runAs(new Identity(member,tenant,"虚构验收租户","admin","管理员","ADMIN"),()->
      assertEquals(205,((Number)decisions.alerts(farm).get("activeAlertCount")).intValue()));
    assertEquals(105,workspace.path("taskTotal").asInt());assertEquals(100,workspace.path("tasks").size());
    assertTrue(workspace.path("tasksTruncated").asBoolean());
    assertEquals(1,workspace.at("/analytics/summary/cancelledTasks").asInt());
    assertEquals(103,workspace.at("/analytics/summary/openTasks").asInt());
    assertEquals(104,workspace.at("/analytics/summary/completionEligibleTasks").asInt());
    assertFalse(workspace.path("generatedAt").asText().isBlank());
    assertEquals(clock.today().minusDays(6).toString(),workspace.at("/window/fromDate").asText());
    assertEquals(clock.today().toString(),workspace.at("/window/toDate").asText());
  }

  @Test void explicitTrackingNullsNeverBorrowUnrelatedLegacyTaskAssociations()throws Exception {
    String id=rawHarvest(plot,clock.today(),15),task=completedHarvest(5);
    db.update("INSERT INTO production_lineage(tenant_id,production_id,planting_id,task_id) VALUES(?,?,?,?)",tenant,id,planting,task);
    db.update("INSERT INTO production_tracking(tenant_id,production_id,planting_id,status,revision,updated_at) VALUES(?,?,?,'ACTIVE',0,?)",tenant,id,planting,clock.now());
    var record=call("GET","/production?farmId="+farm,null,200).get(0);
    assertEquals(planting,record.path("PLANTING_ID").asText());assertTrue(record.path("TASK_ID").isNull());
    assertEquals(task,db.queryForObject("SELECT task_id FROM production_lineage WHERE tenant_id=? AND production_id=?",String.class,tenant,id),
      "Reading canonical tracking metadata does not rewrite legacy history");
  }
}
