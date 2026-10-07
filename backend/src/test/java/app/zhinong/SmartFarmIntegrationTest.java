package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.device.SmartFarmProtocol;
import app.zhinong.workspace.MetricCatalog;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "spring.datasource.url=jdbc:h2:mem:smart-farm-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true","farm.demo-rich=false","farm.bootstrap-password=Test-Only-Password-429!"
})
class SmartFarmIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  final HttpClient http=HttpClient.newHttpClient();
  String admin, other, viewer, platform;
  @BeforeEach void login() throws Exception {
    admin=login("demo-a","admin");other=login("demo-b","admin");viewer=login("demo-a","viewer");platform=login("platform","platform");
  }
  String login(String tenant,String user) throws Exception {
    return call(null,"POST","/auth/login",Map.of("tenantCode",tenant,"username",user,"password","Test-Only-Password-429!"),200).path("token").asText();
  }
  JsonNode call(String token,String method,String path,Object body,int expected,String...headers) throws Exception {
    var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json");
    if(token!=null) req.header("Authorization","Bearer "+token);
    for(int i=0;i<headers.length;i+=2) req.header(headers[i],headers[i+1]);
    req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var res=http.send(req.build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(expected,res.statusCode(),method+" "+path+" "+res.body());return json.readTree(res.body());
  }
  record Device(String id,String key,String external,String topic,String adapter) {}
  Device device(String type,String adapter) throws Exception {
    String farm=call(admin,"POST","/farm-workspaces",Map.of("name","虚构现场验收-"+UUID.randomUUID(),"description","测试数据","region","虚构区域","farmType","FIELD"),200).path("id").asText();
    String plot=call(admin,"POST","/plots",Map.of("farmId",farm,"name","虚构监测田","areaMu",10,"crop","水稻"),200).path("ID").asText();
    var asset=new LinkedHashMap<String,Object>();
    asset.put("farmId",farm);asset.put("name","虚构"+type);asset.put("code","FIXTURE-"+UUID.randomUUID());
    asset.put("plotId",plot);
    asset.put("deviceType",type);asset.put("protocol","HTTP_PUSH");asset.put("lifecycle","ACTIVE");asset.put("model","");asset.put("notes","");
    asset.put("intervalSeconds",60);asset.put("revision",0);asset.put("controlEnabled",Set.of("GATE","PUMP").contains(type));
    asset.put("channels",MetricCatalog.PRESETS.get(type).stream().map(m -> m.startsWith("SOIL_MOISTURE")?
      Map.of("metric",m,"lowerLimit",20,"upperLimit",60):Map.of("metric",m)).toList());
    String id=call(admin,"POST","/assets",asset,200).path("id").asText();
    String key=call(admin,"POST","/assets/"+id+"/credentials",null,200).path("key").asText();
    String ext="virtual-"+UUID.randomUUID();
    Device d=new Device(id,key,ext,adapter.equals("LAN_DTU")?"":"/fixture/"+ext+"/up",adapter);
    bind(d,type,0,200,admin);return d;
  }
  JsonNode bind(Device d,String type,int revision,int status,String token) throws Exception {
    return call(token,"PUT","/assets/"+d.id()+"/integration",Map.of("adapterType",d.adapter(),"externalId",d.external(),
      "upstreamTopic",d.topic(),"downstreamTopic",d.adapter().equals("LAN_DTU")?"":"/fixture/"+d.external()+"/down",
      "revision",revision,"bindings",SmartFarmProtocol.defaults(d.adapter(),type)),status);
  }
  Map<String,Object> packet(Device d,Instant time,Object payload) {
    return Map.of("messageId",UUID.randomUUID().toString(),"measuredAt",time.toString(),"externalId",d.external(),"topic",d.topic(),"payload",payload);
  }
  Object fields(Object...values) {
    var data=new ArrayList<Map<String,Object>>();
    for(int i=0;i<values.length;i+=2) data.add(Map.of("name",values[i],"value",values[i+1]));
    return Map.of("params",Map.of("dir","up","r_data",data));
  }
  JsonNode ingest(Device d,Object packet,int status) throws Exception {return call(null,"POST","/ingest/smart-farm",packet,status,"X-Device-Key",d.key());}
  JsonNode detail(Device d) throws Exception {return call(admin,"GET","/assets/"+d.id(),null,200);}
  JsonNode channel(JsonNode d,String metric) {for(var c:d.path("channels"))if(c.path("metric").asText().equals(metric))return c;throw new AssertionError(metric);}
  Map<String,Object> start() {return Map.of("requestId",UUID.randomUUID().toString(),"action","PUMP_START","note","虚构报文验收");}

  @Test void pumpUsesActualFeedbackAndNativeReceiptDoesNotInventRunningState() throws Exception {
    Device d=device("PUMP","PUMP_MQTT");
    var packet=packet(d,Instant.now().minusSeconds(1),fields("mainPumpManual",1,"mainPumpRunning","0","remoteFlag",1,"emergencyStop",0,"mainPumpFreqSet","35","mainPumpVoltage","380.2"));
    var accepted=ingest(d,packet,200);assertEquals(5,accepted.path("count").asInt());
    assertEquals(0,channel(detail(d),"PUMP_RUNNING").at("/latest/value").asInt());
    assertEquals("PARTIAL",detail(d).path("dataQuality").asText());
    var cmd=call(admin,"POST","/assets/"+d.id()+"/commands",start(),200);
    var polled=call(null,"POST","/ingest/smart-farm/commands/poll",null,200,"X-Device-Key",d.key());
    assertEquals("mainPumpManual",polled.at("/commands/0/payload/rw_prot/w_data/0/name").asText());
    assertEquals("1",polled.at("/commands/0/payload/rw_prot/w_data/0/value").asText());
    String id=cmd.path("id").asText();
    var receipt=Map.of("externalId",d.external(),"topic",d.topic(),"payload",Map.of("rw_prot",Map.of("dir","up","id",id,"w_data",List.of(Map.of("name","mainPumpManual","value","1")))));
    call(null,"POST","/ingest/smart-farm/commands/"+id+"/receipt",receipt,200,"X-Device-Key",d.key());
    assertTrue(call(null,"POST","/ingest/smart-farm/commands/"+id+"/receipt",receipt,200,"X-Device-Key",d.key()).path("duplicate").asBoolean());
    assertEquals(0,channel(detail(d),"PUMP_RUNNING").at("/latest/value").asInt());
    assertTrue(ingest(d,packet,200).path("duplicate").asBoolean());
    assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE device_id=?",Integer.class,d.id()));
  }

  @Test void tenantRoleIdentityAndTopicAreEnforced() throws Exception {
    Device d=device("GATE","GATE_MQTT");
    bind(d,"GATE",1,403,viewer);bind(d,"GATE",1,404,other);
    call(platform,"GET","/assets/"+d.id()+"/integration",null,403);
    var packet=packet(d,Instant.now().minusSeconds(1),fields("opening",45));
    call(null,"POST","/ingest/smart-farm",packet,401);
    call(null,"POST","/ingest/smart-farm",packet,403,"X-Device-Key",d.key(),"X-Tenant-Id","forged");
    var bad=new LinkedHashMap<>(packet);bad.put("externalId","another-device");ingest(d,bad,403);
    bad=new LinkedHashMap<>(packet);bad.put("topic","/fixture/wrong/up");ingest(d,bad,403);
    ingest(d,packet,200);
    assertEquals(45,channel(detail(d),"GATE_OPENING").at("/latest/value").asInt());
    call(admin,"POST","/assets/"+d.id()+"/commands",Map.of("requestId",UUID.randomUUID().toString(),"action","SET_OPENING","value",70,"note","缺少联锁应拒绝"),409);
  }

  @Test void gateSetpointAndMissingReadingsNeverBecomeActualZeroValues() throws Exception {
    Device d=device("GATE","GATE_MQTT");
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("setOpening",100)),400);
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("opening","--")),400);
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("opening","NaN")),400);
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("opening","1e-9999999")),400);
    assertEquals("NO_DATA",detail(d).path("freshness").asText());
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE device_id=?",Integer.class,d.id()));
  }

  @Test void soilLayersKeepTheirOwnSampleTimesAndStaleBackfillDoesNotRaiseLiveAlerts() throws Exception {
    Device d=device("SOIL","LAN_DTU");Instant now=Instant.now().minusSeconds(2), old=now.minusSeconds(7200);
    var payload=Map.of("deviceAddr",d.external(),"data",List.of(
      Map.of("nodeId",1,"temValue",22.3,"humValue",36.5,"timeStamp",now.toEpochMilli()),
      Map.of("nodeId",3,"temValue",21.7,"humValue",5,"timeStamp",old.toEpochMilli())));
    var result=ingest(d,packet(d,now,payload),200);
    assertEquals(4,result.path("count").asInt());assertEquals(6,result.path("missing").size());
    var detail=detail(d);
    assertEquals("FRESH",channel(detail,"SOIL_MOISTURE").path("freshness").asText());
    assertEquals("STALE",channel(detail,"SOIL_MOISTURE_2").path("freshness").asText());
    assertEquals("NO_DATA",channel(detail,"SOIL_MOISTURE_3").path("freshness").asText());
    assertEquals(0,detail.path("alerts").size());
    var history=call(admin,"GET","/assets/"+d.id()+"/history?metric=SOIL_MOISTURE_2&hours=24",null,200);
    assertEquals(1,history.path("points").size());
    var events=call(viewer,"GET","/assets/"+d.id()+"/integration",null,200).path("events");
    assertEquals(6,events.get(0).path("missing").size());
  }

  @Test void changedMappingCancelsCommandsAndRequiresNewFeedback() throws Exception {
    Device d=device("PUMP","PUMP_MQTT");
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("mainPumpRunning",0,"remoteFlag",1,"emergencyStop",0)),200);
    call(admin,"POST","/assets/"+d.id()+"/commands",start(),200);
    bind(d,"PUMP",1,200,admin);
    assertEquals("CANCELLED",call(admin,"GET","/assets/"+d.id()+"/commands",null,200).at("/commands/0/status").asText());
    call(admin,"POST","/assets/"+d.id()+"/commands",start(),409);
    bind(d,"PUMP",1,409,admin);
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("mainPumpRunning",0,"remoteFlag",1,"emergencyStop",0)),200);
    call(admin,"POST","/assets/"+d.id()+"/commands",start(),200);
  }

  @Test void duplicateConflictsAndInvalidBatchAreAtomic() throws Exception {
    Device d=device("PUMP","PUMP_MQTT");
    var packet=packet(d,Instant.now().minusSeconds(1),fields("mainPumpRunning",0,"remoteFlag",1));
    ingest(d,packet,200);
    var conflict=new LinkedHashMap<>(packet);conflict.put("payload",fields("mainPumpRunning",1,"remoteFlag",1));ingest(d,conflict,409);
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("mainPumpRunning",1,"remoteFlag",0.5)),400);
    assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE device_id=?",Integer.class,d.id()));
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM device_ingest_events WHERE device_id=?",Integer.class,d.id()));
    assertEquals(0,channel(detail(d),"PUMP_RUNNING").at("/latest/value").asInt());
  }

  @Test void frequencyRequiresSeparateParameterPermissionAndEmergencyStopBlocksStart() throws Exception {
    Device d=device("PUMP","PUMP_MQTT");
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("mainPumpRunning",0,"remoteFlag",1,"emergencyStop",0,"remoteParamSet",0)),200);
    var frequency=Map.of("requestId",UUID.randomUUID().toString(),"action","SET_FREQUENCY","value",35,"note","测试远程参数许可");
    call(admin,"POST","/assets/"+d.id()+"/commands",frequency,409);
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("remoteParamSet",1)),200);
    call(admin,"POST","/assets/"+d.id()+"/commands",frequency,200);
    ingest(d,packet(d,Instant.now().minusSeconds(1),fields("emergencyStop",1)),200);
    call(admin,"POST","/assets/"+d.id()+"/commands",start(),409);
  }

  @Test void alertBecomesOneTenantScopedIssueAndSensorRecoveryDoesNotCloseFieldWork() throws Exception {
    Device d=device("SOIL","LAN_DTU");
    ingest(d,packet(d,Instant.now().minusSeconds(1),Map.of("data",List.of(Map.of("nodeId",1,"humValue",5)))),200);
    String alert=detail(d).path("alerts").get(0).path("id").asText();
    var body=Map.of("severity","NORMAL","note","请到田间核查土壤水分和探头接触");
    call(viewer,"POST","/field-work/alerts/"+alert+"/issue",body,403);
    call(other,"POST","/field-work/alerts/"+alert+"/issue",body,404);
    var issue=call(admin,"POST","/field-work/alerts/"+alert+"/issue",body,200);
    var repeat=call(admin,"POST","/field-work/alerts/"+alert+"/issue",body,200);
    assertEquals(issue.path("id"),repeat.path("id"));assertTrue(repeat.path("duplicate").asBoolean());
    ingest(d,packet(d,Instant.now(),Map.of("data",List.of(Map.of("nodeId",1,"humValue",35)))),200);
    var record=detail(d).path("alerts").get(0);
    assertEquals("RESOLVED",record.path("status").asText());
    assertEquals("OPEN",record.path("issueStatus").asText());
    assertEquals(issue.path("id"),record.path("issueId"));
    assertEquals("WATER",db.queryForObject("SELECT category FROM field_issues WHERE id=?",String.class,issue.path("id").asText()));
  }
}
