package app.zhinong.risk;
import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.business.*;
import app.zhinong.weather.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:risk-integration;DB_CLOSE_DELAY=-1","farm.demo=true","farm.demo-rich=false",
 "farm.weather.enabled=false","farm.alerts.automation-enabled=false","farm.ai.automation-enabled=false","farm.bootstrap-password=Test-Only-Password-429!"
})
@Import(FarmRiskIntegrationTest.Config.class)
class FarmRiskIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcTemplate db;
 @Autowired ObjectMapper json;
 @Autowired FarmClock clock;
 @Autowired TestForecast forecasts;
 final HttpClient http=HttpClient.newHttpClient();String admin,tenant,farm,plot,member;
 @BeforeEach void setup()throws Exception {
  admin=login("demo-a","admin");var me=call(admin,"GET","/auth/me",null,200);tenant=me.path("tenantId").asText();member=me.path("memberId").asText();
  farm=call(admin,"POST","/farms",Map.of("name","虚构风险场-"+UUID.randomUUID(),"description","隔离风险测试"),200).path("ID").asText();
  plot=call(admin,"POST","/plots",Map.of("farmId",farm,"name","风险核查田","crop","水稻","areaMu",10),200).path("ID").asText();forecasts.result=Map.of("status","UNCONFIGURED");
 }
 @Test void urgentOldAlarmsArePrioritisedBeforeLimitsAndLinkedIssuesAreMerged()throws Exception {
  String device=call(admin,"POST","/devices",Map.of("farmId",farm,"name","虚构测点","metric","TEMPERATURE","adapter","SIMULATED"),200).path("ID").asText();
  String urgent=UUID.randomUUID().toString();
  db.update("INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at) VALUES(?,?,?,'OFFLINE',0,'旧的离线告警',?,?)",urgent,tenant,device,clock.now().minusDays(3),clock.now());
  for(int i=0;i<205;i++)db.update("INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at) VALUES(?,?,?,'TEMPERATURE',50,'近期温度告警',?,?)",UUID.randomUUID().toString(),tenant,device,clock.now(),clock.now());
  var initial=report();assertEquals(206,initial.at("/summary/device").asInt());assertEquals(1,initial.at("/summary/high").asInt());
  assertTrue(initial.path("truncated").asBoolean());assertEquals(80,initial.path("items").size());
  assertEquals("device:"+urgent,initial.at("/items/0/id").asText(),"urgent events must be selected before the 200-detail limit");
  String issue=UUID.randomUUID().toString();
  db.update("INSERT INTO field_issues(id,tenant_id,plot_id,category,severity,description,reporter_id,status,created_at) VALUES(?,?,?,'EQUIPMENT','NORMAL','已转现场核查',?,'OPEN',?)",issue,tenant,plot,member,clock.now());
  db.update("INSERT INTO alert_field_issues(tenant_id,alert_id,issue_id) VALUES(?,?,?)",tenant,urgent,issue);
  var merged=report();assertEquals(205,merged.at("/summary/device").asInt());assertEquals(1,merged.at("/summary/field").asInt());
  assertEquals(initial.at("/summary/total"),merged.at("/summary/total"));
  assertEquals("field:"+issue,merged.at("/items/0/id").asText());assertEquals(1,merged.at("/items/0/linkedDeviceAlerts").asInt());
  for(var item:merged.path("items"))assertNotEquals("device:"+urgent,item.path("id").asText());
 }
 @Test void weatherRisksAreBoundToFreshForecastAndReadDoesNotCreateWork()throws Exception {
  var day=new LinkedHashMap<String,Object>();day.put("date",clock.today().toString());day.put("tempMaxC",36);
  forecasts.result=Map.of("status","READY","expiresAt",clock.now().plusMinutes(1).toInstant().toString(),"days",List.of(day));
  long tasks=db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=?",Long.class,tenant);
  var current=report();assertEquals(1,current.at("/summary/weather").asInt());assertTrue(current.path("notice").asText().contains("不是官方"));
  forecasts.result=Map.of("status","STALE","expiresAt",clock.now().minusMinutes(1).toInstant().toString(),"days",List.of(day));
  assertEquals(0,report().at("/summary/weather").asInt());
  forecasts.result=Map.of("status","READY","expiresAt",clock.now().minusSeconds(1).toInstant().toString(),"days",List.of(day));
  var expired=report();assertEquals("STALE",expired.path("forecastStatus").asText());
  assertEquals(0,expired.at("/summary/weather").asInt());
  boolean gap=false;for(var item:expired.path("items"))gap|="data:forecast".equals(item.path("id").asText());assertTrue(gap);
  assertEquals(tasks,db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=?",Long.class,tenant));
 }
 @Test void viewerCanReadOnlyOwnFarmAndPlatformCannotReadBusinessRisk()throws Exception {
  call(login("demo-a","viewer"),"GET","/farms/"+farm+"/risk-overview",null,200);
  call(login("demo-b","admin"),"GET","/farms/"+farm+"/risk-overview",null,404);
  call(login("platform","platform"),"GET","/farms/"+farm+"/risk-overview",null,403);
 }
 JsonNode report()throws Exception{return call(admin,"GET","/farms/"+farm+"/risk-overview",null,200);}
 String login(String code,String user)throws Exception{return call(null,"POST","/auth/login",Map.of("tenantCode",code,"username",user,"password","Test-Only-Password-429!"),200).path("token").asText();}
 JsonNode call(String token,String method,String route,Object body,int status)throws Exception {
  var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+route)).header("Content-Type","application/json");if(token!=null)r.header("Authorization","Bearer "+token);
  r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
  var result=http.send(r.build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,result.statusCode(),result.body());return json.readTree(result.body());
 }
 @TestConfiguration static class Config { @Bean @Primary TestForecast testForecast(Store store,FarmClock clock){return new TestForecast(store,clock);} }
 static class TestForecast extends WeatherForecastService {
  volatile Map<String,Object> result=Map.of("status","UNCONFIGURED");
  TestForecast(Store store,FarmClock clock){super(store,clock,null,false);}
  @Override public Map<String,Object> get(String farmId){return result;}
 }
}
