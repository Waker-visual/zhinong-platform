package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.business.FarmClock;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
  "spring.datasource.url=jdbc:h2:mem:production-ledger-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=false", "farm.demo-live=false",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class ProductionLedgerIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired FarmClock clock;
  final HttpClient http=HttpClient.newHttpClient();
  String admin, operator, other, viewer, platform, farm, plot, planting;

  JsonNode call(String token,String method,String route,Object body,int status)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+route)).header("Content-Type","application/json");
    if(token!=null)request.header("Authorization","Bearer "+token);
    request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(status,response.statusCode(),response.body());return json.readTree(response.body());
  }
  String login(String tenant,String user)throws Exception {
    return call(null,"POST","/auth/login",Map.of("tenantCode",tenant,"username",user,"password","Test-Only-Password-429!"),200).path("token").asText();
  }
  @BeforeEach void prepare()throws Exception {
    admin=login("demo-a","admin");operator=login("demo-a","operator");other=login("demo-b","admin");
    viewer=login("demo-a","viewer");platform=login("platform","platform");
    farm=call(admin,"POST","/farms",Map.of("name","收获台账验收-"+UUID.randomUUID(),"description","虚构测试数据"),200).path("ID").asText();
    plot=call(admin,"POST","/plots",Map.of("farmId",farm,"name","测试地块","crop","水稻","areaMu",10),200).path("ID").asText();
    planting=call(admin,"POST","/plantings",Map.of("plotId",plot,"crop","水稻","variety","虚构品种","areaMu",10,
      "startDate",clock.today().minusDays(10).toString(),"endDate",clock.today().plusDays(2).toString()),200).path("ID").asText();
  }
  Map<String,Object> input() {
    var body=new LinkedHashMap<String,Object>();body.put("plotId",plot);body.put("recordDate",clock.today().toString());
    body.put("yieldKg",125.5);body.put("note","虚构收获验收");body.put("plantingId",planting);body.put("requestId",UUID.randomUUID().toString());return body;
  }
  Map<String,Object> correction(Map<String,Object> body,int revision) {
    var result=new LinkedHashMap<>(body);result.remove("requestId");result.put("reason","核对称重单后更正");result.put("expectedRevision",revision);return result;
  }
  String task(String targetPlot,String type,String status)throws Exception {
    String id=call(admin,"POST","/tasks",Map.of("plotId",targetPlot,"title","虚构验收任务","taskType",type,"dueDate",clock.today().toString(),"note",""),200).path("ID").asText();
    if(status.equals("COMPLETED")) {
      call(admin,"PATCH","/tasks/"+id+"/status",Map.of("status","RUNNING"),200);
      call(admin,"PATCH","/tasks/"+id+"/status",Map.of("status","COMPLETED"),200);
    }
    return id;
  }

  @Test void linkedCreateIsIdempotentAndTenantScoped()throws Exception {
    var body=input();body.put("taskId",task(plot,"HARVEST","COMPLETED"));
    var created=call(operator,"POST","/production",body,200);String id=created.path("ID").asText();
    assertEquals(planting,created.path("PLANTING_ID").asText());assertEquals("ACTIVE",created.path("STATUS").asText());
    assertEquals(0,created.path("REVISION").asInt());
    assertEquals(id,call(operator,"POST","/production",body,200).path("ID").asText());
    var different=new LinkedHashMap<>(body);different.put("yieldKg",126);call(admin,"POST","/production",different,409);
    assertEquals(1,call(admin,"GET","/production?farmId="+farm,null,200).size());
    assertEquals("CREATE",call(viewer,"GET","/production/"+id+"/history",null,200).get(0).path("ACTION").asText());
    call(other,"GET","/production/"+id+"/history",null,404);
    call(other,"GET","/production?farmId="+farm,null,404);call(platform,"GET","/production",null,403);
    call(other,"POST","/production",input(),404);call(viewer,"POST","/production",input(),403);
    call(other,"PUT","/production/"+id,correction(body,0),404);
  }

  @Test void correctionAndVoidRetainHistoryAndRemoveAggregateYield()throws Exception {
    double before=call(admin,"GET","/dashboard",null,200).path("yieldKg").asDouble();
    var body=input();var created=call(operator,"POST","/production",body,200);String id=created.path("ID").asText();
    var change=correction(body,0);change.put("yieldKg",100);
    call(operator,"PUT","/production/"+id,change,403);
    var fixed=call(admin,"PUT","/production/"+id,change,200);assertEquals(1,fixed.path("REVISION").asInt());
    call(admin,"PUT","/production/"+id,change,409);
    assertEquals(before+100,call(admin,"GET","/dashboard",null,200).path("yieldKg").asDouble(),0.01);
    assertEquals(100,call(admin,"GET","/farms/"+farm+"/analytics?days=30",null,200).at("/summary/yieldKg").asDouble(),0.01);
    assertEquals(1,call(admin,"GET","/farms/"+farm+"/operations?hours=24",null,200).at("/pipeline/harvest/records").asInt());
    call(operator,"POST","/production/"+id+"/void",Map.of("reason","重复登记","expectedRevision",1),403);
    var cancelled=call(admin,"POST","/production/"+id+"/void",Map.of("reason","重复登记","expectedRevision",1),200);
    assertEquals("VOID",cancelled.path("STATUS").asText());assertEquals(2,cancelled.path("REVISION").asInt());
    assertEquals(before,call(admin,"GET","/dashboard",null,200).path("yieldKg").asDouble(),0.01);
    var analytics=call(admin,"GET","/farms/"+farm+"/analytics?days=30",null,200);
    assertEquals(0,analytics.at("/summary/yieldKg").asDouble(),0.01);
    assertEquals(0,analytics.path("productionTrend").size());
    assertEquals(0,analytics.at("/productionByPlot/0/value").asDouble(),0.01);
    assertEquals(0,call(admin,"GET","/farms/"+farm+"/operations?hours=24",null,200).at("/pipeline/harvest/records").asInt());
    assertEquals("VOID",call(admin,"GET","/production?farmId="+farm,null,200).get(0).path("STATUS").asText());
    var history=call(admin,"GET","/production/"+id+"/history",null,200);assertEquals(3,history.size());
    assertEquals(125.5,history.get(0).at("/snapshot/YIELD_KG").asDouble(),0.01);
    assertEquals("核对称重单后更正",history.get(1).path("REASON").asText());
    assertEquals("VOID",history.get(2).at("/snapshot/STATUS").asText());
    assertEquals("VOID",call(operator,"POST","/production",body,200).path("STATUS").asText());
    call(admin,"POST","/production/"+id+"/void",Map.of("reason","再次作废","expectedRevision",2),409);
    call(admin,"PUT","/production/"+id,correction(body,2),409);
  }

  @Test void relationsRequireMatchingPlotCycleAndCompletedHarvest()throws Exception {
    var body=input();body.put("taskId",task(plot,"INSPECTION","COMPLETED"));call(admin,"POST","/production",body,400);
    body.put("taskId",task(plot,"HARVEST","PENDING"));call(admin,"POST","/production",body,409);
    body.put("taskId",task(plot,"HARVEST","COMPLETED"));body.remove("plantingId");call(admin,"POST","/production",body,400);
    body.put("plantingId",planting);body.put("recordDate",clock.today().minusDays(11).toString());call(admin,"POST","/production",body,400);
    String another=call(admin,"POST","/plots",Map.of("farmId",farm,"name","另一地块","crop","水稻","areaMu",10),200).path("ID").asText();
    body=input();body.put("plotId",another);call(admin,"POST","/production",body,400);
    body=input();body.put("plantingId",UUID.randomUUID().toString());call(admin,"POST","/production",body,404);
    body=input();body.put("yieldKg",1.005);call(admin,"POST","/production",body,400);
  }

  @Test void reasonsAndExpectedRevisionAreRequired()throws Exception {
    var body=input();String id=call(admin,"POST","/production",body,200).path("ID").asText();
    var change=correction(body,0);change.put("reason","  ");call(admin,"PUT","/production/"+id,change,400);
    change=correction(body,0);change.remove("expectedRevision");call(admin,"PUT","/production/"+id,change,400);
    call(admin,"POST","/production/"+id+"/void",Map.of("reason"," ","expectedRevision",0),400);
    call(admin,"POST","/production/"+id+"/void",Map.of("reason","无修订号"),400);
    assertEquals(1,call(admin,"GET","/production/"+id+"/history",null,200).size());
  }

  @Test void legacyClientAndLegacyRowsRemainEditableWithOriginalSnapshot()throws Exception {
    var oldClient=input();oldClient.remove("plantingId");oldClient.remove("requestId");
    assertTrue(call(operator,"POST","/production",oldClient,200).path("PLANTING_ID").isNull());
    String tenant=db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'",String.class),id=UUID.randomUUID().toString();
    db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",id,tenant,plot,clock.today(),55,"旧台账");
    String oldTask=task(plot,"HARVEST","COMPLETED");
    db.update("INSERT INTO production_lineage(tenant_id,production_id,planting_id,task_id) VALUES(?,?,?,?)",tenant,id,planting,oldTask);
    var change=correction(input(),0);change.put("yieldKg",60);change.remove("plantingId");
    var corrected=call(admin,"PUT","/production/"+id,change,200);
    assertTrue(corrected.path("PLANTING_ID").isNull());assertTrue(corrected.path("TASK_ID").isNull());
    var history=call(admin,"GET","/production/"+id+"/history",null,200);
    assertEquals(2,history.size());assertEquals("LEGACY",history.get(0).path("ACTION").asText());
    assertEquals(55,history.get(0).at("/snapshot/YIELD_KG").asInt());
    assertEquals("旧台账",history.get(0).at("/snapshot/NOTE").asText());
    assertEquals(planting,history.get(0).at("/snapshot/PLANTING_ID").asText());
    assertEquals(oldTask,history.get(0).at("/snapshot/TASK_ID").asText());
  }

  @Test void concurrentRetriesCreateOnlyOneHarvest()throws Exception {
    var body=input();
    try(var executor=Executors.newFixedThreadPool(2)) {
      Callable<JsonNode> send=()->call(operator,"POST","/production",body,200);
      var results=executor.invokeAll(List.of(send,send));
      assertEquals(results.get(0).get().path("ID"),results.get(1).get().path("ID"));
    }
    assertEquals(1,call(admin,"GET","/production?farmId="+farm,null,200).size());
  }
}
