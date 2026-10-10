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
  String farm(){return db.queryForObject("SELECT id FROM farms WHERE tenant_id=? AND name='青禾设备联动演示场'",String.class,tenant());}
  JsonNode call(String token,String method,String route,Object body,int status)throws Exception{
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+route)).header("Content-Type","application/json").timeout(Duration.ofSeconds(30));
    if(token!=null)request.header("Authorization","Bearer "+token);
    request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(status,response.statusCode(),response.body());return json.readTree(response.body());
  }
  String login(String tenant,String username)throws Exception{return call(null,"POST","/auth/login",Map.of("tenantCode",tenant,"username",username,"password","Test-Only-Password-429!"),200).path("token").asText();}

  @Test void portfolioFarmsHaveIndependentMapsModelsHistoryAndReversibleArchives() throws Exception {
    String token=login("demo-a","admin");
    var cards=call(token,"GET","/farm-workspaces",null,200);assertEquals(5,cards.size());
    assertEquals(5,call(token,"GET","/farms",null,200).size());
    assertEquals(15,call(token,"GET","/plots",null,200).size());
    var totals=call(token,"GET","/dashboard",null,200);assertEquals(5,totals.path("farms").asInt());assertEquals(80,totals.path("devices").asInt());
    Set<String> boundaries=new HashSet<>(), cameraScenes=new HashSet<>();int models=0;
    for(var card:cards) {
      String f=card.path("id").asText();var map=call(token,"GET","/farms/"+f+"/field-map",null,200);
      assertTrue(map.path("parcels").size()>=9);assertEquals(9,map.path("zones").size());
      assertTrue(boundaries.add(map.path("parcels").get(0).path("boundary").toString()));
      var report=call(token,"GET","/farms/"+f+"/research-data",null,200);assertTrue(report.path("consistent").asBoolean(),report.toString());
      assertTrue(report.at("/counts/farm_tasks").asInt()>350);
      var cameras=call(token,"GET","/farms/"+f+"/cameras",null,200).path("devices");
      assertEquals(3,cameras.size());assertEquals(f,cameras.get(0).path("farmId").asText());
      assertEquals("DEMO_IMAGE",cameras.get(0).at("/camera/mode").asText());
      assertTrue(cameraScenes.add(cameras.get(0).at("/camera/playbackUrl").asText()));
      Set<String> plots=new HashSet<>(),views=new HashSet<>();
      for(var c:cameras) {
        assertEquals(f,c.path("farmId").asText());assertTrue(c.path("positioned").asBoolean());
        assertTrue(plots.add(c.path("plotId").asText()));assertTrue(views.add(c.at("/camera/playbackUrl").asText()));
        assertFalse(c.at("/camera/viewLabel").asText().contains("合成演示"));
      }
      for(var a:call(token,"GET","/assets?farmId="+f,null,200)) if(a.has("machinery")) {
        models++;var profile=a.path("machinery");assertFalse(profile.path("model").asText().isBlank());
        if(!f.equals(farm()))assertEquals("",profile.path("serialNumber").asText());
        if(profile.path("kind").asText().equals("DRONE"))assertTrue(profile.path("horsepower").isNull());
      }
    }
    assertEquals(20,models);
    var archives=call(token,"GET","/farm-workspaces?archived=true",null,200);assertTrue(archives.size()>=3);
    String retired=archives.get(0).path("id").asText();
    call(login("demo-b","admin"),"POST","/farms/"+retired+"/restore",Map.of(),404);
    call(login("demo-a","viewer"),"POST","/farms/"+retired+"/restore",Map.of(),403);
    long tasks=db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=?",Long.class,tenant());
    var record=db.queryForMap("SELECT reason,archived_at FROM farm_archives WHERE tenant_id=? AND farm_id=?",tenant(),retired);
    try {
      call(token,"POST","/farms/"+retired+"/restore",Map.of(),200);
      assertEquals(6,call(token,"GET","/farms",null,200).size());
      context.getBean(app.zhinong.bootstrap.FarmPortfolioDemoData.class).run(null);
      assertEquals(6,call(token,"GET","/farms",null,200).size(),"Restart must preserve a restored farm");
      assertEquals(tasks,db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=?",Long.class,tenant()));
    } finally {db.update("INSERT INTO farm_archives(tenant_id,farm_id,reason,archived_at) VALUES(?,?,?,?)",tenant(),retired,record.get("REASON"),record.get("ARCHIVED_AT"));}
  }

  @Test void modelSpecificPlansPersistReceiptsAndRejectWrongCapabilities() throws Exception {
    String token=login("demo-a","admin"),f=farm(),path="/farms/"+f+"/field-map";
    var p=call(token,"GET",path,null,200).path("parcels").get(0);
    for(var a:call(token,"GET","/assets?farmId="+f,null,200)) if(a.has("machinery")) {
      String device=a.path("id").asText(),kind=a.at("/machinery/kind").asText();
      call(token,"POST","/assets/"+device+"/collect",Map.of(),200);
      var in=new LinkedHashMap<String,Object>();in.put("parcelId",p.path("id").asText());in.put("deviceId",device);in.put("title","型号调度验收");in.put("taskType",a.at("/machinery/taskTypes/0").asText());
      in.put("widthMeters",a.at("/machinery/widthMeters").asDouble());in.put("headlandMeters",a.at("/machinery/headlandMeters").asDouble());in.put("speedKmh",a.at("/machinery/speedKmh").asDouble());in.put("bearing",90);in.put("durationSeconds",0);in.put("planningMode","AUTO");in.put("recommendBearing",true);in.put("simulationRate",1);in.put("altitudeMeters",kind.equals("DRONE")?4:null);in.put("requestId",UUID.randomUUID().toString());
      var preview=call(token,"POST",path+"/routes/preview",in,200);
      assertEquals(Math.ceil(preview.path("estimatedMinutes").asDouble()*60),preview.path("simulationSeconds").asDouble(),0.01);
      in.put("reverse",true);var reversed=call(token,"POST",path+"/routes/preview",in,200);
      assertEquals(preview.path("points").get(0),reversed.path("points").get(reversed.path("points").size()-1));
      var invalid=new LinkedHashMap<>(in);invalid.put("taskType",kind.equals("HARVESTER")?"PROTECTION":"HARVEST");call(token,"POST",path+"/routes/preview",invalid,400);
      var j=call(token,"POST",path+"/jobs",in,200);String job=j.path("id").asText();
      call(token,"PATCH","/field-work/tasks/"+j.path("taskId").asText()+"/progress",Map.of("status","CANCELLED","method","SERVICE","note","验证调度状态不可从另一入口覆盖","actualAreaMu",0),409);
      assertEquals(a.at("/machinery/model"),j.at("/plan/machine/model"));assertEquals("ACCEPTED",j.at("/events/0/action").asText());
      assertEquals(job,call(token,"POST",path+"/jobs",in,200).path("id").asText());
      var duplicate=new LinkedHashMap<>(in);duplicate.put("requestId",UUID.randomUUID().toString());call(token,"POST",path+"/jobs",duplicate,409);
      call(token,"POST",path+"/jobs/"+job+"/actions",Map.of("action","PAUSE"),200);
      call(token,"POST",path+"/jobs/"+job+"/actions",Map.of("action","RESUME"),200);
      var stopped=call(token,"POST",path+"/jobs/"+job+"/actions",Map.of("action","STOP"),200);assertTrue(stopped.path("events").size()>=4);
      // Reuse an automatically validated centreline as a hand-drawn path; coverage remains explicitly unknown.
      in.put("planningMode","MANUAL");in.put("manualPoints",preview.path("points"));in.put("requestId",UUID.randomUUID().toString());
      var manual=call(token,"POST",path+"/routes/preview",in,200);assertTrue(manual.path("workAreaMu").isNull());
    }
  }

  @Test void mobileOperatorCanDispatchOnlyTenantScopedSimulatedMachinery() throws Exception {
    String admin=login("demo-a","admin"),operator=login("demo-a","operator"),f=farm(),path="/farms/"+f+"/field-map";
    var parcel=call(operator,"GET",path,null,200).path("parcels").get(0);
    String machine=db.queryForObject("SELECT a.device_id FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id WHERE a.tenant_id=? AND d.farm_id=? AND a.code='DEMO-CTRL-MACHINERY'",String.class,tenant(),f);
    call(admin,"POST","/assets/"+machine+"/collect",Map.of(),200);
    var input=new LinkedHashMap<String,Object>();
    input.put("parcelId",parcel.path("id").asText());input.put("deviceId",machine);input.put("title","手机操作员巡田验证");input.put("taskType","INSPECTION");
    input.put("widthMeters",5);input.put("bearing",90);input.put("headlandMeters",4);input.put("speedKmh",4);input.put("durationSeconds",120);input.put("simulationRate",1);input.put("requestId",UUID.randomUUID().toString());
    call(login("demo-a","viewer"),"POST",path+"/jobs",input,403);
    call(login("platform","platform"),"POST",path+"/jobs",input,403);
    call(login("demo-b","operator"),"POST",path+"/jobs",input,404);
    var job=call(operator,"POST",path+"/jobs",input,200);String id=job.path("id").asText();
    try {
      String owner=db.queryForObject("SELECT id FROM members WHERE tenant_id=? AND username='operator'",String.class,tenant());
      assertEquals(owner,db.queryForObject("SELECT owner_id FROM farm_map_jobs WHERE tenant_id=? AND id=?",String.class,tenant(),id));
      assertEquals(owner,db.queryForObject("SELECT assignee_id FROM task_fieldwork WHERE tenant_id=? AND task_id=?",String.class,tenant(),job.path("taskId").asText()));
      assertEquals(id,call(operator,"POST",path+"/jobs",input,200).path("id").asText());
      call(admin,"POST",path+"/jobs",input,409);
      var duplicate=new LinkedHashMap<>(input);duplicate.put("requestId",UUID.randomUUID().toString());call(operator,"POST",path+"/jobs",duplicate,409);
      assertEquals("PAUSED",call(operator,"POST",path+"/jobs/"+id+"/actions",Map.of("action","PAUSE"),200).path("status").asText());
    } finally {call(operator,"POST",path+"/jobs/"+id+"/actions",Map.of("action","STOP"),200);}
    db.update("UPDATE asset_profiles SET protocol='HTTP_PUSH' WHERE tenant_id=? AND device_id=?",tenant(),machine);
    try {input.put("requestId",UUID.randomUUID().toString());call(operator,"POST",path+"/jobs",input,409);}
    finally {db.update("UPDATE asset_profiles SET protocol='SIMULATED' WHERE tenant_id=? AND device_id=?",tenant(),machine);}
  }

  @Test void mapRoutesTasksAndEstimatedWaterPersistWithTenantIsolation() throws Exception {
    String token=login("demo-a","admin"),f=farm(),path="/farms/"+f+"/field-map";
    var map=call(token,"GET",path,null,200);assertEquals(9,map.path("parcels").size());assertEquals(9,map.path("zones").size());
    assertEquals("WGS84",map.path("coordinateSystem").asText());
    assertTrue(db.queryForObject("SELECT layout_revision FROM farm_profiles WHERE tenant_id=? AND farm_id=?",Integer.class,tenant(),f)>0,"Seeding new locations must invalidate pre-upgrade map editors");
    var p=map.path("parcels").get(0);assertEquals("IMAGERY_ESTIMATE",p.path("source").asText());
    call(login("demo-b","admin"),"GET",path,null,404);call(login("platform","platform"),"GET",path,null,403);
    String machine=db.queryForObject("SELECT a.device_id FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id WHERE a.tenant_id=? AND d.farm_id=? AND a.code='DEMO-CTRL-MACHINERY'",String.class,tenant(),f);
    call(token,"POST","/assets/"+machine+"/collect",Map.of(),200);
    var input=new LinkedHashMap<String,Object>();input.put("parcelId",p.path("id").asText());input.put("deviceId",machine);input.put("title","边界内巡检验收");input.put("taskType","INSPECTION");input.put("widthMeters",5);input.put("bearing",90);input.put("headlandMeters",4);input.put("speedKmh",4);input.put("durationSeconds",120);input.put("requestId",UUID.randomUUID().toString());
    var preview=call(token,"POST",path+"/routes/preview",input,200);assertTrue(preview.path("points").size()>4);
    call(login("demo-a","viewer"),"POST",path+"/jobs",input,403);
    var j=call(token,"POST",path+"/jobs",input,200);String id=j.path("id").asText();
    assertEquals(id,call(token,"POST",path+"/jobs",input,200).path("id").asText());assertTrue(j.path("actualTrack").isEmpty());assertFalse(j.path("taskId").asText().isBlank());
    var conflicting=new LinkedHashMap<>(input);conflicting.put("title","同编号不同参数");call(token,"POST",path+"/jobs",conflicting,409);
    call(login("demo-b","admin"),"POST",path+"/jobs/"+id+"/actions",Map.of("action","STOP"),404);
    assertEquals("PAUSED",call(token,"POST",path+"/jobs/"+id+"/actions",Map.of("action","PAUSE"),200).path("status").asText());
    assertEquals("RUNNING",call(token,"POST",path+"/jobs/"+id+"/actions",Map.of("action","RESUME"),200).path("status").asText());
    db.update("UPDATE farm_map_jobs SET last_tick_at=? WHERE tenant_id=? AND id=?",OffsetDateTime.now().minusMinutes(3),tenant(),id);
    context.getBean(app.zhinong.workspace.FieldMapService.class).tick(tenant(),f,id);
    assertEquals("COMPLETED",db.queryForObject("SELECT status FROM farm_tasks WHERE tenant_id=? AND id=?",String.class,tenant(),j.path("taskId").asText()));
    var zone=map.path("zones").get(0);String pump=zone.path("pumpId").asText();
    call(token,"POST","/assets/"+pump+"/collect",Map.of(),200);
    var water=Map.of("zoneId",zone.path("id").asText(),"durationSeconds",120,"requestId",UUID.randomUUID().toString());
    var run=call(token,"POST",path+"/irrigation",water,200);String runId=run.path("id").asText();
    assertEquals(runId,call(token,"POST",path+"/irrigation",water,200).path("id").asText());
    call(token,"POST",path+"/irrigation",Map.of("zoneId",zone.path("id").asText(),"durationSeconds",60,"requestId",UUID.randomUUID().toString()),409);
    call(token,"POST",path+"/jobs/"+runId+"/actions",Map.of("action","PAUSE"),409);
    call(token,"POST","/assets/"+pump+"/commands",Map.of("action","SET_FREQUENCY","value",30,"requestId",UUID.randomUUID().toString(),"note","互斥验证"),409);
    db.update("UPDATE farm_map_jobs SET last_tick_at=? WHERE tenant_id=? AND id=?",OffsetDateTime.now().minusSeconds(10),tenant(),runId);
    var stopped=call(token,"POST",path+"/jobs/"+runId+"/actions",Map.of("action","STOP"),200);
    assertEquals("STOPPED",stopped.path("status").asText());assertTrue(stopped.path("estimatedM3").asDouble()>0);assertTrue(stopped.path("measuredM3").isNull());
    var asset=call(token,"GET","/assets/"+pump,null,200);for(var c:asset.path("channels"))if(c.path("metric").asText().equals("PUMP_RUNNING"))assertEquals(0,c.at("/latest/value").asInt());
    assertTrue(call(token,"GET",path,null,200).at("/waterTotals/estimatedM3").asDouble()>0);
    var restart=call(token,"POST",path+"/irrigation",Map.of("zoneId",zone.path("id").asText(),"durationSeconds",10,"requestId",UUID.randomUUID().toString()),200);
    String restartId=restart.path("id").asText();
    db.update("UPDATE farm_map_jobs SET last_tick_at=? WHERE tenant_id=? AND id=?",OffsetDateTime.now().minusMinutes(1),tenant(),restartId);
    context.getBean(app.zhinong.workspace.FieldMapService.class).tick(tenant(),f,restartId);
    assertEquals("COMPLETED",db.queryForObject("SELECT status FROM farm_map_jobs WHERE tenant_id=? AND id=?",String.class,tenant(),restartId));
    var disabled=call(token,"POST",path+"/irrigation",Map.of("zoneId",zone.path("id").asText(),"durationSeconds",120,"requestId",UUID.randomUUID().toString()),200);
    db.update("UPDATE tenants SET enabled=FALSE WHERE id=?",tenant());
    try {context.getBean(app.zhinong.workspace.FieldMapService.class).tick(tenant(),f,disabled.path("id").asText());
      assertEquals("STOPPED",db.queryForObject("SELECT status FROM farm_map_jobs WHERE tenant_id=? AND id=?",String.class,tenant(),disabled.path("id").asText()));
    } finally { db.update("UPDATE tenants SET enabled=TRUE WHERE id=?",tenant()); }
    call(login("demo-a","viewer"),"POST",path+"/parcels",Map.of("plotId",p.path("plotId").asText(),"name","未授权估绘","boundary",p.path("boundary")),403);
    var saved=call(token,"POST",path+"/parcels",Map.of("plotId",p.path("plotId").asText(),"name","人工估绘验收","boundary",p.path("boundary")),200);
    var updated=call(token,"GET",path,null,200);boolean found=false;for(var row:updated.path("parcels"))if(row.path("id").equals(saved.path("id"))){found=true;assertEquals(p.path("boundary"),row.path("boundary"));assertEquals("USER_ESTIMATE",row.path("source").asText());}assertTrue(found);
  }

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
    assertEquals(16,call(token,"GET","/assets?farmId="+id,null,200).size());
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
    assertEquals(16,operations.at("/summary/freshDevices").asInt());
  }

  @Test @Transactional void cameraUpgradeFillsMissingStationsWithoutReplacingConfiguredSources() {
    String tenant=tenant(),farm=farm();
    String original=db.queryForObject("SELECT d.id FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id WHERE d.tenant_id=? AND d.farm_id=? AND a.code='DEMO-CTRL-CAMERA'",String.class,tenant,farm);
    db.update("UPDATE devices SET name='已接入的自定义机位' WHERE tenant_id=? AND id=?",tenant,original);
    db.update("UPDATE camera_profiles SET media_mode='VIDEO',source_url='https://example.org/private-feed.mp4',view_label='东侧固定机位' WHERE tenant_id=? AND device_id=?",tenant,original);
    // Recreate a v1 installation with one camera, and verify the additive upgrade twice.
    for(String id:db.queryForList("SELECT d.id FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id WHERE d.tenant_id=? AND d.farm_id=? AND a.code LIKE 'DEMO-CTRL-ZCAM-%'",String.class,tenant,farm)) {
      db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id=?",tenant,id);
      db.update("DELETE FROM observations WHERE tenant_id=? AND device_id=?",tenant,id);
      db.update("DELETE FROM devices WHERE tenant_id=? AND id=?",tenant,id);
    }
    db.update("DELETE FROM demo_scenarios WHERE tenant_id=? AND scenario=?",tenant,"cam2"+farm);
    long others=db.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id<>?",Long.class,tenant);
    var seed=context.getBean(app.zhinong.bootstrap.CameraDemoData.class);seed.run(null);seed.run(null);
    assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id WHERE d.tenant_id=? AND d.farm_id=? AND a.device_type='CAMERA'",Integer.class,tenant,farm));
    assertEquals("已接入的自定义机位",db.queryForObject("SELECT name FROM devices WHERE tenant_id=? AND id=?",String.class,tenant,original));
    assertEquals("https://example.org/private-feed.mp4",db.queryForObject("SELECT source_url FROM camera_profiles WHERE tenant_id=? AND device_id=?",String.class,tenant,original));
    assertEquals("东侧固定机位",db.queryForObject("SELECT view_label FROM camera_profiles WHERE tenant_id=? AND device_id=?",String.class,tenant,original));
    assertEquals(others,db.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id<>?",Long.class,tenant));
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
