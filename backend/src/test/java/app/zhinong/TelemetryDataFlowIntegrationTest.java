package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.api.ApiException;
import app.zhinong.business.FarmClock;
import app.zhinong.workspace.AssetService;
import app.zhinong.workspace.DeviceAlertMonitor;
import app.zhinong.workspace.MetricCatalog;
import app.zhinong.workspace.TelemetryService;
import app.zhinong.security.AuthService;
import app.zhinong.security.Identity;
import java.math.BigDecimal;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "spring.datasource.url=jdbc:h2:mem:telemetry-flow-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true","farm.demo-rich=false","farm.demo-live=false","farm.alerts.automation-enabled=false",
  "farm.llm.file-enabled=false","farm.bootstrap-password=Test-Only-Password-429!"
})
class TelemetryDataFlowIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired FarmClock clock;
  @Autowired DeviceAlertMonitor monitor;
  @Autowired TelemetryService telemetry;
  @Autowired AuthService auth;
  final HttpClient http=HttpClient.newHttpClient();
  String admin,operator,other,platform,tenant,farm,plot;
  @BeforeEach void prepare() throws Exception {
    admin=login("demo-a","admin");operator=login("demo-a","operator");other=login("demo-b","admin");platform=login("platform","platform");
    tenant=call(admin,"GET","/auth/me",null,200).path("tenantId").asText();
    farm=call(admin,"POST","/farms",Map.of("name","观测链路-"+UUID.randomUUID(),"description","虚构回归数据"),200).path("ID").asText();
    plot=call(admin,"POST","/plots",Map.of("farmId",farm,"name","测试田","crop","蔬菜","areaMu",10),200).path("ID").asText();
  }

  @Test void legacyObservationHasOneStoredSampleAndFeedsAlertsAgronomyHistoryAndDecisionEvidence() throws Exception {
    String id=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));
    var at=clock.now().minusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    call(operator,"POST","/devices/"+id+"/observations",Map.of("value",18.001,"measuredAt",at.toLocalDateTime().toString()),200);
    assertEquals(1L,count("observations",id));assertEquals(0L,count("telemetry_readings",id));
    var asset=call(admin,"GET","/assets/"+id,null,200);
    assertEquals("FRESH",asset.at("/channels/0/freshness").asText());
    assertEquals("observations",asset.at("/channels/0/latest/recordStore").asText());
    assertEquals(at.toInstant(),Instant.parse(asset.at("/channels/0/latest/time").asText()));
    assertFalse(asset.path("lastReceivedAt").isNull());
    assertTrue(asset.at("/channels/0/latest/receivedAtKnown").asBoolean());
    var agronomy=sensor(analysis(),id,"SOIL_MOISTURE");
    assertEquals(18.001,agronomy.path("value").asDouble(),0.00001);assertTrue(agronomy.path("fresh").asBoolean());
    assertEquals("%",agronomy.path("unit").asText());
    assertEquals(1,call(admin,"GET","/alerts?farmId="+farm,null,200).size());
    assertEquals(1,history(id).at("/sources/0/count").asInt());
    assertEquals(18.0,operations(24).at("/slices/99/moisture").asDouble(),0.001);
    var evidence=decision().path("evidence").path("readings").get(0);
    assertEquals(agronomy.path("recordId"),evidence.path("recordId"));
    assertEquals(agronomy.path("fresh"),evidence.path("fresh"));
    call(operator,"POST","/devices/"+id+"/observations",Map.of("value",20.0001,"measuredAt",at.toLocalDateTime().toString()),400);
    assertEquals(1L,count("observations",id));
    call(other,"GET","/assets/"+id,null,404);call(platform,"GET","/ai/analysis?farmId="+farm,null,403);
  }

  @Test void correctionsAndMirroredLegacySamplesCountOnceAndAllCurrentViewsChooseTheLastReceipt() throws Exception {
    String id=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));var at=clock.now().minusMinutes(2);
    String old="ffffffff-ffff-4fff-8fff-"+UUID.randomUUID().toString().substring(24), correction="00000000-0000-4000-8000-"+UUID.randomUUID().toString().substring(24);
    db.update("INSERT INTO observations(id,tenant_id,device_id,measured_value,measured_at,source) VALUES(?,?,?,?,?,'MANUAL')",
      UUID.randomUUID().toString(),tenant,id,18,java.sql.Timestamp.from(at.toInstant()));
    var unknown=call(admin,"GET","/assets/"+id,null,200).at("/channels/0/latest");
    assertTrue(unknown.path("receivedAt").isNull());assertFalse(unknown.path("receivedAtKnown").asBoolean());
    insert(old,id,"SOIL_MOISTURE",18,at,at.plusSeconds(1),"MANUAL");
    insert(correction,id,"SOIL_MOISTURE",45,at,at.plusSeconds(2),"MANUAL");
    assertEquals(45,call(admin,"GET","/assets/"+id,null,200).at("/channels/0/latest/value").asInt());
    assertEquals(correction,sensor(analysis(),id,"SOIL_MOISTURE").path("recordId").asText());
    assertEquals(45,decision().at("/evidence/readings/0/value").asInt());
    var history=history(id);assertEquals(1,history.path("points").size());assertEquals(1,history.at("/points/0/samples").asInt());
    assertEquals(45,history.at("/points/0/value").asInt());assertEquals(1,history.at("/sources/0/count").asInt());
    assertEquals(45,operations(24).at("/slices/99/moisture").asInt());
    assertEquals(3L,count("telemetry_readings",id)+count("observations",id),"canonical reads must never delete raw evidence");
    db.update("INSERT INTO observations(id,tenant_id,device_id,measured_value,measured_at,source,received_at) VALUES(?,?,?,?,?,'MANUAL',?)",
      UUID.randomUUID().toString(),tenant,id,55,java.sql.Timestamp.from(at.toInstant()),at.plusSeconds(3));
    assertEquals(55,call(admin,"GET","/assets/"+id,null,200).at("/channels/0/latest/value").asInt());
    assertEquals(55,sensor(analysis(),id,"SOIL_MOISTURE").path("value").asInt());
    assertEquals(55,history(id).at("/points/0/value").asInt());assertEquals(1,history(id).at("/points/0/samples").asInt());
  }

  @Test void futureAndWrongSourceRecordsAreNeverCurrentOrHistoricalEvidenceForTheConfiguredProtocol() throws Exception {
    String id=device("HTTP_PUSH","SOIL",List.of("SOIL_MOISTURE"));var now=clock.now();
    insert(UUID.randomUUID().toString(),id,"SOIL_MOISTURE",35,now.minusMinutes(2),now.minusSeconds(5),"HTTP_PUSH");
    insert(UUID.randomUUID().toString(),id,"SOIL_MOISTURE",99,now.plusMinutes(2),now.minusSeconds(4),"HTTP_PUSH");
    db.update("UPDATE asset_profiles SET last_received_at=? WHERE tenant_id=? AND device_id=?",now.minusSeconds(1),tenant,id);
    assertEquals("STALE",call(admin,"GET","/assets/"+id,null,200).at("/channels/0/freshness").asText());
    assertFalse(sensor(analysis(),id,"SOIL_MOISTURE").path("fresh").asBoolean());
    assertFalse(decision().at("/evidence/readings/0/fresh").asBoolean());
    assertEquals(35,history(id).at("/points/0/value").asInt());
    monitor.scanFarm(tenant,farm);
    assertEquals("DATA_STALE",call(admin,"GET","/alerts?farmId="+farm,null,200).get(0).path("kind").asText());
    db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND measured_at>?",tenant,id,now);
    insert(UUID.randomUUID().toString(),id,"SOIL_MOISTURE",40,now.minusSeconds(1),now.minusSeconds(1),"SIMULATED");
    assertFalse(sensor(analysis(),id,"SOIL_MOISTURE").path("fresh").asBoolean());
    assertEquals("采集来源与配置不一致",decision().at("/evidence/readings/0/reason").asText());
    assertEquals(35,history(id).at("/points/0/value").asInt(),"wrong-source samples cannot enter production trend aggregates");
  }

  @Test void unconfiguredMetricsMissingChannelsAndOtherPlotsCannotBorrowEachOthersReadings() throws Exception {
    String id=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));var now=clock.now();
    insert(UUID.randomUUID().toString(),id,"TEMPERATURE",80,now.minusSeconds(5),now.minusSeconds(5),"MANUAL");
    String anotherPlot=call(admin,"POST","/plots",Map.of("farmId",farm,"name","邻田","crop","蔬菜","areaMu",5),200).path("ID").asText();
    String neighbor=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));
    db.update("UPDATE asset_profiles SET plot_id=? WHERE tenant_id=? AND device_id=?",anotherPlot,tenant,neighbor);
    insert(UUID.randomUUID().toString(),neighbor,"SOIL_MOISTURE",35,now.minusSeconds(5),now.minusSeconds(5),"MANUAL");
    var report=analysis();assertEquals(2,report.path("sensors").size());
    var missing=sensor(report,id,"SOIL_MOISTURE");assertTrue(missing.path("value").isNull());
    assertEquals("NO_DATA",missing.path("freshness").asText());assertFalse(missing.path("fresh").asBoolean());
    var evidence=decision().path("evidence").path("readings");assertEquals(1,evidence.size());
    assertEquals(id,evidence.get(0).path("deviceId").asText());assertFalse(evidence.get(0).path("fresh").asBoolean());
    assertEquals(0,history(id).path("points").size());
  }

  @Test void canonicalUnitsConcurrentRetriesAndInvalidPrecisionNeverCreateDuplicateOrRoundedSamples() throws Exception {
    String id=device("HTTP_PUSH","WEATHER",List.of("AIR_PRESSURE","HUMIDITY"));
    String key=call(admin,"POST","/assets/"+id+"/credentials",null,200).path("key").asText();
    var at=clock.instant().minusSeconds(2).toString();String request=UUID.randomUUID().toString();
    var input=Map.of("messageId",request,"measuredAt",at,"readings",List.of(Map.of("metric","AIR_PRESSURE","value",101.3,"unit","kPa"),Map.of("metric","HUMIDITY","value",50,"unit","%RH")));
    try(var workers=Executors.newFixedThreadPool(2)) {
      Callable<JsonNode> send=()->ingest(key,input,200);var results=workers.invokeAll(List.of(send,send));
      assertNotEquals(results.get(0).get().path("duplicate").asBoolean(),results.get(1).get().path("duplicate").asBoolean());
    }
    assertEquals(2L,count("telemetry_readings",id));
    var equivalent=Map.of("messageId",request,"measuredAt",at,"readings",List.of(Map.of("metric","HUMIDITY","value",50,"unit","%"),Map.of("metric","AIR_PRESSURE","value",1013,"unit","hPa")));
    assertTrue(ingest(key,equivalent,200).path("duplicate").asBoolean());
    assertEquals(1013,sensor(call(admin,"GET","/assets/"+id,null,200),id,"AIR_PRESSURE",false).path("value").asInt());
    ingest(key,Map.of("messageId",UUID.randomUUID().toString(),"measuredAt",at,"readings",List.of(Map.of("metric","HUMIDITY","value",50.0001))),400);
    assertEquals(2L,count("telemetry_readings",id));
    assertThrows(ApiException.class,()->MetricCatalog.normalize("SOIL_MOISTURE",new BigDecimal("35"),"%RH"));
  }

  @Test void currentHealthAndSharedFreshnessBoundariesAreIndependentOfTheHistoricalWindow() throws Exception {
    var now=clock.now();String fresh=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));
    String old=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));
    insert(UUID.randomUUID().toString(),fresh,"SOIL_MOISTURE",0,now.minusMinutes(20),now.minusMinutes(20),"MANUAL");
    insert(UUID.randomUUID().toString(),old,"SOIL_MOISTURE",35,now.minusDays(2),now.minusDays(2),"MANUAL");
    assertTrue(sensor(analysis(),fresh,"SOIL_MOISTURE").path("fresh").asBoolean(),"900-second channels remain valid for 2700 seconds in every view");
    for(int hours:List.of(24,720)) {
      var summary=operations(hours).path("summary");assertEquals(33,summary.path("currentHealth").asInt());
      assertEquals(1,summary.path("freshDevices").asInt());assertEquals(1,summary.path("silentDevices").asInt());
    }
    Instant fixed=Instant.parse("2026-01-01T00:00:00Z");
    var valid=Map.<String,Object>of("time",fixed.minusSeconds(179),"value",BigDecimal.ZERO,"source","MANUAL");
    assertTrue(AssetService.quality(valid,"SOIL_MOISTURE","MANUAL","ACTIVE",30,fixed).fresh());
    var expired=new LinkedHashMap<>(valid);expired.put("time",fixed.minusSeconds(180));
    assertFalse(AssetService.quality(expired,"SOIL_MOISTURE","MANUAL","ACTIVE",30,fixed).fresh());
    expired.put("time",fixed.plusNanos(1));assertFalse(AssetService.quality(expired,"SOIL_MOISTURE","MANUAL","ACTIVE",30,fixed).fresh());
  }

  @Test void fullAlertSummaryDoesNotTruncateToTheDisplayLimit() throws Exception {
    String id=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));var now=clock.now();
    var rows=new ArrayList<Object[]>();
    for(int i=0;i<205;i++)rows.add(new Object[]{UUID.randomUUID().toString(),tenant,id,"SOIL_MOISTURE",18,"虚构告警",now,now});
    for(String metric:List.of("OFFLINE","DATA_STALE"))rows.add(new Object[]{UUID.randomUUID().toString(),tenant,id,metric,180,"虚构设备状态告警",now,now});
    db.batchUpdate("INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",rows);
    assertEquals(200,call(admin,"GET","/alerts?farmId="+farm,null,200).size());
    Identity.runAs(auth.authenticate(admin),()->{
      var totals=telemetry.alertSummary(farm);assertEquals(207,((Number)totals.get("activeAlerts")).intValue());
      assertEquals(1,((Number)totals.get("offlineDevices")).intValue());assertEquals(1,((Number)totals.get("staleDevices")).intValue());
    });
    Identity.runAs(auth.authenticate(other),()->assertThrows(ApiException.class,()->telemetry.alertSummary(farm)));
  }

  @Test void partiallyInvalidDeviceRetainsTheSameCurrentHealthAcrossAssetAndOperations() throws Exception {
    String id=device("MANUAL","SOIL",List.of("SOIL_MOISTURE","TEMPERATURE"));var now=clock.now();
    insert(UUID.randomUUID().toString(),id,"SOIL_MOISTURE",35,now.minusSeconds(30),now.minusSeconds(1),"MANUAL");
    insert(UUID.randomUUID().toString(),id,"TEMPERATURE",22,now.plusMinutes(2),now.minusSeconds(1),"MANUAL");
    var asset=call(admin,"GET","/assets/"+id,null,200);
    assertEquals("FRESH",asset.path("freshness").asText());assertEquals("PARTIAL",asset.path("dataQuality").asText());
    assertEquals(1,asset.path("freshChannelCount").asInt());
    for(var channel:asset.path("channels")) {
      assertEquals(channel.path("metric").asText().equals("SOIL_MOISTURE")?"FRESH":"STALE",channel.path("freshness").asText());
    }
    assertTrue(sensor(analysis(),id,"SOIL_MOISTURE").path("fresh").asBoolean());
    assertFalse(sensor(analysis(),id,"TEMPERATURE").path("fresh").asBoolean());
    assertEquals(100,operations(24).at("/summary/currentHealth").asInt());
  }

  @Test void physicalRangeAndDiscreteCorruptionStayDiagnosableButNeverEnterFarmOrAiAggregates() throws Exception {
    String soil=device("MANUAL","SOIL",List.of("SOIL_MOISTURE"));var now=clock.now();
    insert(UUID.randomUUID().toString(),soil,"SOIL_MOISTURE",40,now.minusMinutes(3),now.minusSeconds(4),"MANUAL");
    insert(UUID.randomUUID().toString(),soil,"SOIL_MOISTURE",35,now.minusMinutes(2),now.minusSeconds(3),"MANUAL");
    insert(UUID.randomUUID().toString(),soil,"SOIL_MOISTURE",101,now.minusMinutes(2),now.minusSeconds(2),"MANUAL");
    assertFalse(sensor(analysis(),soil,"SOIL_MOISTURE").path("fresh").asBoolean());
    assertEquals(40,operations(24).at("/slices/99/moisture").asInt(),"reject the invalid winning correction after ranking, without resurrecting its earlier value");
    var diagnostic=history(soil);assertEquals(2,diagnostic.path("points").size());
    boolean retained=false;for(var point:diagnostic.path("points"))retained|=point.path("max").asInt()==101;
    assertTrue(retained,"device diagnostics retain corrupt raw evidence for investigation");assertEquals(3L,count("telemetry_readings",soil));
    String pest=device("MANUAL","PEST",List.of("PEST_COUNT"));
    insert(UUID.randomUUID().toString(),pest,"PEST_COUNT",8,now.minusMinutes(3),now.minusSeconds(4),"MANUAL");
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'MANUAL')",
      UUID.randomUUID().toString(),tenant,pest,"PEST_COUNT",new BigDecimal("1.5"),now.minusMinutes(2),now.minusSeconds(3));
    var report=analysis();assertFalse(sensor(report,pest,"PEST_COUNT").path("fresh").asBoolean());
    assertEquals(8,report.at("/pestHistory/0/averageCount").asInt());assertEquals(1,report.at("/pestHistory/0/samples").asInt());
  }

  String device(String protocol,String type,List<String> metrics) throws Exception {
    var input=new LinkedHashMap<String,Object>();input.put("farmId",farm);input.put("plotId",plot);input.put("name","测点-"+UUID.randomUUID());input.put("code","F-"+UUID.randomUUID());
    input.put("deviceType",type);input.put("protocol",protocol);input.put("lifecycle","ACTIVE");input.put("model","");input.put("notes","");input.put("intervalSeconds",900);input.put("revision",0);
    input.put("channels",metrics.stream().map(m -> m.equals("SOIL_MOISTURE")?Map.of("metric",m,"lowerLimit",20,"upperLimit",80):Map.of("metric",m)).toList());
    return call(admin,"POST","/assets",input,200).path("id").asText();
  }
  void insert(String record,String device,String metric,int value,OffsetDateTime at,OffsetDateTime received,String source) {
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,?)",record,tenant,device,metric,value,at,received,source);
  }
  JsonNode analysis() throws Exception {return call(admin,"GET","/ai/analysis?farmId="+farm,null,200);}
  JsonNode operations(int hours) throws Exception {return call(admin,"GET","/farms/"+farm+"/operations?hours="+hours,null,200);}
  JsonNode history(String id) throws Exception {return call(admin,"GET","/assets/"+id+"/history?metric=SOIL_MOISTURE&hours=24",null,200);}
  JsonNode decision() throws Exception {return call(operator,"POST","/ai/decisions",Map.of("farmId",farm,"plotId",plot,"question","核对观测链路","requestId",UUID.randomUUID().toString()),200);}
  JsonNode sensor(JsonNode report,String id,String metric) {return sensor(report,id,metric,true);}
  JsonNode sensor(JsonNode report,String id,String metric,boolean agronomy) {
    var rows=agronomy?report.path("sensors"):report.path("channels");
    for(var row:rows)if((!agronomy||id.equals(row.path("deviceId").asText()))&&metric.equals(row.path("metric").asText()))return agronomy?row:row.path("latest");
    fail("configured channel missing: "+metric);return null;
  }
  long count(String table,String id) {return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND device_id=?",Long.class,tenant,id);}
  String login(String code,String user)throws Exception{return call(null,"POST","/auth/login",Map.of("tenantCode",code,"username",user,"password","Test-Only-Password-429!"),200).path("token").asText();}
  JsonNode ingest(String key,Object body,int expected)throws Exception{return request(null,"POST","/ingest/telemetry",body,expected,key);}
  JsonNode call(String token,String method,String path,Object body,int expected)throws Exception{return request(token,method,path,body,expected,null);}
  JsonNode request(String token,String method,String path,Object body,int expected,String key)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json").timeout(Duration.ofSeconds(30));
    if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("X-Device-Key",key);
    var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(expected,response.statusCode(),method+" "+path+": "+response.body());return json.readTree(response.body());
  }
}
