package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.business.FarmClock;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "spring.datasource.url=jdbc:h2:mem:data-quality-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true","farm.demo-rich=false","farm.demo-live=false","farm.alerts.automation-enabled=false",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class DataQualityIntegrationTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired FarmClock clock;
  final HttpClient http=HttpClient.newHttpClient();
  String admin,tenant,farm,plot;

  @BeforeEach void prepare()throws Exception {
    admin=login("demo-a","admin");tenant=db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'",String.class);
    farm=call(admin,"POST","/farms",Map.of("name","虚构核对场-"+UUID.randomUUID(),"description","核对测试"),200).path("ID").asText();
    plot=call(admin,"POST","/plots",Map.of("farmId",farm,"name","核对地块","areaMu",10,"crop","水稻"),200).path("ID").asText();
  }
  @Test void reportsLegacyDuplicateAndFutureRowsWithoutChangingThem()throws Exception {
    assertEquals(0,report().at("/summary/errorCount").asInt());
    for(int i=0;i<25;i++) db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(),tenant,plot,clock.today().plusDays(1),20,"虚构待核实的分批采收");
    var before=db.queryForList("SELECT * FROM production WHERE tenant_id=? AND plot_id=? ORDER BY id",tenant,plot);
    var report=report();assertEquals(10,report.at("/summary/checkedRules").asInt());
    assertEquals(25,check(report,"FUTURE_PRODUCTION").path("count").asInt());
    assertEquals(20,check(report,"FUTURE_PRODUCTION").path("samples").size());
    assertEquals(1,check(report,"SUSPECTED_PRODUCTION_DUPLICATES").path("count").asInt());
    assertEquals(25,check(report,"UNLINKED_PRODUCTION").path("count").asInt());
    assertEquals(25,report.at("/summary/errorCount").asInt());
    assertEquals(1,report.at("/summary/warningCount").asInt());
    assertEquals(report.path("checks"),report().path("checks"));
    assertEquals(before,db.queryForList("SELECT * FROM production WHERE tenant_id=? AND plot_id=? ORDER BY id",tenant,plot));
    // A void is excluded by the same canonical effective-record rule as aggregates.
    String id=before.getFirst().get("ID").toString();
    db.update("INSERT INTO production_tracking(tenant_id,production_id,status,updated_at) VALUES(?,?,'VOID',?)",tenant,id,clock.now());
    assertEquals(24,check(report(),"FUTURE_PRODUCTION").path("count").asInt());
  }
  @Test void capturesBrokenBusinessLinksAndTreatsTrackingNullAsIntentional()throws Exception {
    String planting=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
    for(String id:List.of(planting,second)) db.update("INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
      id,tenant,plot,"水稻","虚构品种",12,clock.today().minusDays(1),clock.today().plusDays(1),"ACTIVE");
    String task=UUID.randomUUID().toString();
    db.update("INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,?,?,?,?,?)",
      task,tenant,plot,"缺回执采收","HARVEST",clock.today(),"COMPLETED","虚构测试");
    db.update("INSERT INTO task_fieldwork(tenant_id,task_id,method) VALUES(?,?,'UNCONFIRMED')",tenant,task);
    String production=UUID.randomUUID().toString();
    db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",production,tenant,plot,clock.today().minusDays(2),20,"虚构测试");
    db.update("INSERT INTO production_lineage(tenant_id,production_id,planting_id,task_id) VALUES(?,?,?,?)",tenant,production,planting,task);
    var report=report();assertEquals(1,check(report,"OVERLAPPING_PLANTINGS").path("count").asInt());
    assertEquals(2,check(report,"PLANTING_AREA").path("count").asInt());
    assertEquals(1,check(report,"TASK_RECEIPT").path("count").asInt());
    assertEquals(1,check(report,"PRODUCTION_ASSOCIATION").path("count").asInt());
    db.update("INSERT INTO production_tracking(tenant_id,production_id,status,updated_at) VALUES(?,?,'ACTIVE',?)",tenant,production,clock.now());
    assertEquals(0,check(report(),"PRODUCTION_ASSOCIATION").path("count").asInt());
  }
  @Test void requiresFarmAdministratorAndNeverLeaksForeignFarm()throws Exception {
    call(login("demo-a","viewer"),"GET","/farms/"+farm+"/data-quality",null,403);
    call(login("demo-a","operator"),"GET","/farms/"+farm+"/data-quality",null,403);
    call(login("demo-b","admin"),"GET","/farms/"+farm+"/data-quality",null,404);
    call(login("platform","platform"),"GET","/farms/"+farm+"/data-quality",null,403);
  }
  @Test void productionAssociationChecksHarvestTaskCycleAndThePlantedBatchArea()throws Exception {
    String planting=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),production=UUID.randomUUID().toString();
    db.update("INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
      planting,tenant,plot,"水稻","虚构关联核对品种",5,clock.today().minusDays(1),clock.today().plusDays(1),"ACTIVE");
    db.update("INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,?,?,?,?,?)",
      task,tenant,plot,"虚构批次采收回执","HARVEST",clock.today().minusDays(2),"COMPLETED","核对任务周期与批次面积");
    db.update("INSERT INTO task_fieldwork(tenant_id,task_id,method,completion_note,actual_area_mu,completed_at) VALUES(?,?,'MANUAL',?,8,?)",
      tenant,task,"虚构完整采收回执",clock.now().minusSeconds(1));
    db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",
      production,tenant,plot,clock.today(),20,"虚构批次关联记录");
    db.update("INSERT INTO production_tracking(tenant_id,production_id,planting_id,task_id,status,updated_at) VALUES(?,?,?,?,'ACTIVE',?)",
      tenant,production,planting,task,clock.now());

    var initial=report();
    assertEquals(0,check(initial,"TASK_RECEIPT").path("count").asInt(),"eight mu is valid for this ten-mu plot");
    assertEquals(1,check(initial,"PRODUCTION_ASSOCIATION").path("count").asInt());
    db.update("UPDATE task_fieldwork SET actual_area_mu=5 WHERE tenant_id=? AND task_id=?",tenant,task);
    assertEquals(1,check(report(),"PRODUCTION_ASSOCIATION").path("count").asInt(),"a complete receipt still cannot link a task outside the planting cycle");
    db.update("UPDATE farm_tasks SET due_date=? WHERE tenant_id=? AND id=?",clock.today(),tenant,task);
    assertEquals(0,check(report(),"PRODUCTION_ASSOCIATION").path("count").asInt());
    db.update("UPDATE task_fieldwork SET actual_area_mu=8 WHERE tenant_id=? AND task_id=?",tenant,task);
    assertEquals(1,check(report(),"PRODUCTION_ASSOCIATION").path("count").asInt(),"valid plot-wide area cannot exceed the linked five-mu planting batch");
  }
  JsonNode report()throws Exception {return call(admin,"GET","/farms/"+farm+"/data-quality",null,200);}
  JsonNode check(JsonNode report,String code){for(var row:report.path("checks"))if(code.equals(row.path("code").asText()))return row;throw new AssertionError(code);}
  String login(String code,String user)throws Exception{return call(null,"POST","/auth/login",Map.of("tenantCode",code,"username",user,"password","Test-Only-Password-429!"),200).path("token").asText();}
  JsonNode call(String token,String method,String path,Object body,int expected)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json");
    if(token!=null)request.header("Authorization","Bearer "+token);
    request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),response.body());
    return json.readTree(response.body());
  }
}
