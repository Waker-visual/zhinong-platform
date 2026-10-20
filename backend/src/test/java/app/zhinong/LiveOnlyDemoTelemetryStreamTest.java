package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.database.DatabaseTime;
import app.zhinong.security.Identity;
import app.zhinong.workspace.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Restoring current fictional signals must never turn into a historical fixture migration. */
@SpringBootTest(properties={
  "spring.datasource.url=jdbc:h2:mem:live-only-demo-stream-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=false", "farm.demo-rich=false", "farm.demo-live=false", "farm.demo-portfolio=false",
  "farm.demo-stream.enabled=true", "farm.demo-stream.live-only=true", "farm.demo-stream.delay-ms=3600000",
  "farm.demo-retention.enabled=false", "farm.alerts.automation-enabled=false", "farm.weather.enabled=false",
  "farm.llm.file-enabled=false", "farm.bootstrap-password=Test-Only-Password-429!"
})
@Import(LiveOnlyDemoTelemetryStreamTest.Config.class)
@Transactional
class LiveOnlyDemoTelemetryStreamTest {
  static final Instant START=Instant.parse("2026-10-17T15:59:00Z"); // Immediately before a farm-calendar day boundary.
  @TestConfiguration static class Config {
    @Bean MutableClock liveOnlyTestClock(){return new MutableClock();}
  }
  static final class MutableClock extends Clock {
    volatile Instant now=START;
    public Instant instant(){return now;}
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return new Clock(){
      public Instant instant(){return now;}
      public ZoneId getZone(){return zone;}
      public Clock withZone(ZoneId other){return MutableClock.this.withZone(other);}
    };}
    void advance(Duration amount){now=now.plus(amount);}
  }
  record Fixture(String tenant,String farm,String device){}
  @Autowired JdbcTemplate db;
  @Autowired DemoTelemetryStream stream;
  @Autowired DemoTelemetryRetention retention;
  @Autowired FarmWorkspaceService workspace;
  @Autowired ApplicationContext context;
  @Autowired MutableClock time;
  final Map<String,String> tenants=new HashMap<>();

  @BeforeEach void resetClock(){time.now=START;tenants.clear();}

  @Test void fullRefreshAndDayRolloverAppendOnlyCurrentBatchesIncludingOperatingFarms(){
    var operating=fixture("demo-a",true,true,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    var secondTenant=fixture("demo-b",true,false,"SIMULATED","SIMULATED","ACTIVE","POINT-");
    var old=START.minus(Duration.ofDays(800));
    reading(operating,"TEMPERATURE",new BigDecimal("17.123"),old,"SIMULATED");
    reading(operating,"TEMPERATURE",new BigDecimal("18.456"),old.plusSeconds(120),"HTTP_PUSH");
    observation(operating,new BigDecimal("16.789"),old);
    addBusinessHistory(operating);
    var oldReadings=historicalReadings(START);
    var oldObservations=db.queryForList("SELECT * FROM observations ORDER BY id");
    var business=businessCounts();

    stream.refresh(time.instant(),true);
    assertEquals(3,readingCount(operating));
    assertEquals(1,readingCount(secondTenant));
    stream.refresh(time.instant(),true);
    assertEquals(3,readingCount(operating));

    time.advance(Duration.ofMinutes(2));
    stream.refresh(time.instant(),true); // Even a full scan across midnight must not fill the missing minute.
    assertEquals(4,readingCount(operating));
    assertEquals(2,readingCount(secondTenant));
    assertEquals(List.of(START,time.instant()),currentTimes(operating));
    assertEquals(oldReadings,historicalReadings(START));
    assertEquals(oldObservations,db.queryForList("SELECT * FROM observations ORDER BY id"));
    assertEquals(business,businessCounts());
    assertEquals(0,retention.prune(time.instant()).deleted());
    assertEquals(oldReadings,historicalReadings(START));
  }

  @Test void repeatedTicksAndRestartDoNotDuplicateAnOccupiedMinuteOrBackfillANewChannel(){
    var f=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    reading(f,"TEMPERATURE",new BigDecimal("21.250"),START,"HTTP_PUSH");
    var occupied=db.queryForList("SELECT * FROM telemetry_readings WHERE device_id=?",f.device());
    var legacy=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","POINT-");
    observation(legacy,new BigDecimal("18.375"),START.minus(Duration.ofDays(2)));
    observation(legacy,new BigDecimal("20.625"),START);
    var originalObservations=db.queryForList("SELECT * FROM observations WHERE tenant_id=? AND device_id=? ORDER BY id",legacy.tenant(),legacy.device());
    stream.refresh(time.instant(),true);
    assertEquals(occupied,db.queryForList("SELECT * FROM telemetry_readings WHERE device_id=?",f.device()));
    assertEquals(0,readingCount(legacy));
    time.advance(Duration.ofSeconds(15));
    stream.refresh(time.instant(),false);
    var restarted=context.getAutowireCapableBeanFactory().createBean(DemoTelemetryStream.class);
    try {
      restarted.refresh(time.instant(),true);
      assertEquals(1,readingCount(f));
      db.update("INSERT INTO device_channels(tenant_id,device_id,metric) VALUES(?,?,'HUMIDITY')",f.tenant(),f.device());
      restarted.refresh(time.instant(),true);
      assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE device_id=? AND metric='HUMIDITY'",Integer.class,f.device()));
      assertEquals(time.instant(),DatabaseTime.instant(db.queryForObject("SELECT measured_at FROM telemetry_readings WHERE device_id=? AND metric='HUMIDITY'",Object.class,f.device())));
      assertEquals(occupied,db.queryForList("SELECT * FROM telemetry_readings WHERE device_id=? AND metric='TEMPERATURE'",f.device()));
      assertEquals(0,readingCount(legacy));
      assertEquals(originalObservations,db.queryForList("SELECT * FROM observations WHERE tenant_id=? AND device_id=? ORDER BY id",legacy.tenant(),legacy.device()));
    } finally {context.getAutowireCapableBeanFactory().destroyBean(restarted);}
  }

  @Test void futureAndPreRecoveryCallsNeverWrite(){
    var f=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    stream.refresh(START.minusSeconds(1),true);
    stream.refresh(START.plusSeconds(1),true);
    assertEquals(0,readingCount(f));
    time.advance(Duration.ofMinutes(3));
    var restarted=context.getAutowireCapableBeanFactory().createBean(DemoTelemetryStream.class);
    try {
      restarted.refresh(START.plusSeconds(60),true);
      assertEquals(0,readingCount(f));
      restarted.refresh(time.instant(),true);
      assertEquals(List.of(time.instant()),currentTimes(f));
    } finally {context.getAutowireCapableBeanFactory().destroyBean(restarted);}
  }

  @Test void cumulativeCountersResumeTheirSavedValuesAndNeverEstimateAnOfflineGap(){
    var f=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    db.update("UPDATE asset_profiles SET device_type='PUMP' WHERE tenant_id=? AND device_id=?",f.tenant(),f.device());
    var counters=Map.of("RAINFALL",new BigDecimal("112.345"),"ENERGY",new BigDecimal("1234.567"),"WATER_TOTAL",new BigDecimal("4567.890"));
    var saved=new HashMap<>(counters);
    saved.put("PUMP_RUNNING",BigDecimal.ONE);saved.put("PUMP_FREQUENCY",new BigDecimal("35"));
    for(var entry:saved.entrySet()){
      db.update("INSERT INTO device_channels(tenant_id,device_id,metric) VALUES(?,?,?)",f.tenant(),f.device(),entry.getKey());
      reading(f,entry.getKey(),entry.getValue(),START.minus(Duration.ofDays(9)),"SIMULATED");
    }
    var historical=historicalReadings(START);
    stream.refresh(time.instant(),true);
    for(var entry:counters.entrySet())assertEquals(0,entry.getValue().compareTo(valueAt(f,entry.getKey(),time.instant())));

    time.advance(Duration.ofSeconds(60));
    stream.refresh(time.instant(),false);
    var expected=SyntheticTelemetry.sample("PUMP",f.farm(),time.instant(),ZoneId.of("Asia/Shanghai"),saved,60);
    for(String metric:counters.keySet())assertEquals(0,expected.get(metric).compareTo(valueAt(f,metric,time.instant())));
    assertTrue(valueAt(f,"WATER_TOTAL",time.instant()).compareTo(counters.get("WATER_TOTAL"))>0);
    // fixedDelay includes the preceding batch's work: a slightly late normal tick still advances
    // counters, while its increment is capped to one sampling interval rather than the full delay.
    time.advance(Duration.ofSeconds(61));
    stream.refresh(time.instant(),false);
    var afterLateTick=SyntheticTelemetry.sample("PUMP",f.farm(),time.instant(),ZoneId.of("Asia/Shanghai"),expected,60);
    for(String metric:counters.keySet())assertEquals(0,afterLateTick.get(metric).compareTo(valueAt(f,metric,time.instant())));
    assertTrue(valueAt(f,"WATER_TOTAL",time.instant()).compareTo(expected.get("WATER_TOTAL"))>0);
    var beforeGap=new HashMap<String,BigDecimal>();
    for(String metric:counters.keySet())beforeGap.put(metric,valueAt(f,metric,time.instant()));
    time.advance(Duration.ofDays(20));
    stream.refresh(time.instant(),true);
    for(String metric:counters.keySet())assertEquals(0,beforeGap.get(metric).compareTo(valueAt(f,metric,time.instant())));
    assertEquals(historical,historicalReadings(START));
    assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE device_id=? AND metric='TEMPERATURE'",Integer.class,f.device()));
  }

  @Test void onlyEnabledDemoTenantFarmsAndExplicitlySimulatedActiveDevicesAreEligible(){
    var eligible=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    var excluded=new ArrayList<Fixture>();
    excluded.add(fixture("demo-a",false,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-"));
    excluded.add(fixture("demo-a",true,false,"MANUAL","SIMULATED","ACTIVE","DEMO-"));
    excluded.add(fixture("demo-a",true,false,"SIMULATED","HTTP_PUSH","ACTIVE","DEMO-"));
    excluded.add(fixture("demo-a",true,false,"SIMULATED","SIMULATED","MAINTENANCE","DEMO-"));
    excluded.add(fixture("demo-a",true,false,"SIMULATED","SIMULATED","DISABLED","DEMO-"));
    excluded.add(fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","CUSTOM-"));
    excluded.add(fixture("ordinary",true,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-"));
    var disabled=fixture("demo-b",true,false,"SIMULATED","SIMULATED","ACTIVE","POINT-");
    excluded.add(disabled);
    db.update("UPDATE tenants SET enabled=FALSE WHERE id=?",disabled.tenant());
    var archived=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","POINT-");
    excluded.add(archived);archive(archived);
    stream.refresh(time.instant(),true);
    assertEquals(1,readingCount(eligible));
    for(var f:excluded){
      assertEquals(0,readingCount(f),f.toString());
      assertNull(db.queryForObject("SELECT last_received_at FROM asset_profiles WHERE tenant_id=? AND device_id=?",Object.class,f.tenant(),f.device()));
    }
    // A device that becomes ineligible after an earlier tick must also stop immediately.
    db.update("UPDATE asset_profiles SET protocol='HTTP_PUSH' WHERE tenant_id=? AND device_id=?",eligible.tenant(),eligible.device());
    time.advance(Duration.ofMinutes(1));stream.refresh(time.instant(),true);
    assertEquals(1,readingCount(eligible));
  }

  @Test void foreignSourceCumulativeBaselinesStayUnchangedWithoutBlockingOtherCurrentChannels(){
    for(String source:List.of("HTTP_PUSH","MANUAL")){
      var f=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
      db.update("INSERT INTO device_channels(tenant_id,device_id,metric) VALUES(?,?,'ENERGY')",f.tenant(),f.device());
      reading(f,"ENERGY",new BigDecimal("765432.123"),START.minus(Duration.ofDays(12)),source);
      var original=db.queryForList("SELECT * FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='ENERGY'",f.tenant(),f.device());
      stream.refresh(time.instant(),true);
      assertEquals(original,db.queryForList("SELECT * FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='ENERGY'",f.tenant(),f.device()));
      assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='TEMPERATURE'",Integer.class,f.tenant(),f.device()));
      time.advance(Duration.ofSeconds(60));
      stream.refresh(time.instant(),false);
      assertEquals(original,db.queryForList("SELECT * FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='ENERGY'",f.tenant(),f.device()));
      assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='TEMPERATURE'",Integer.class,f.tenant(),f.device()));
      assertEquals(1,openAlerts(f,"DATA_STALE"));
    }
  }

  @Test void currentSamplingResolvesOfflineAndContinuesNormalThresholdAlerts(){
    var f=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    db.update("UPDATE device_channels SET upper_limit=-70 WHERE tenant_id=? AND device_id=?",f.tenant(),f.device());
    db.update("UPDATE asset_profiles SET last_received_at=? WHERE tenant_id=? AND device_id=?",START.minusSeconds(3600).atOffset(ZoneOffset.UTC),f.tenant(),f.device());
    String offline=UUID.randomUUID().toString();
    db.update("INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at) VALUES(?,?,?,'OFFLINE',3600,'虚构停流',?,?)",
      offline,f.tenant(),f.device(),START.minusSeconds(3600).atOffset(ZoneOffset.UTC),START.minusSeconds(3600).atOffset(ZoneOffset.UTC));
    stream.refresh(time.instant(),true);
    assertEquals("RESOLVED",db.queryForObject("SELECT status FROM device_alerts WHERE tenant_id=? AND id=?",String.class,f.tenant(),offline));
    assertEquals(1,openAlerts(f,"TEMPERATURE"));
    String alert=db.queryForObject("SELECT id FROM device_alerts WHERE tenant_id=? AND device_id=? AND metric='TEMPERATURE' AND status='OPEN'",String.class,f.tenant(),f.device());
    time.advance(Duration.ofSeconds(60));stream.refresh(time.instant(),false);
    assertEquals(1,openAlerts(f,"TEMPERATURE"));
    assertEquals(time.instant(),DatabaseTime.instant(db.queryForObject("SELECT updated_at FROM device_alerts WHERE tenant_id=? AND id=?",Object.class,f.tenant(),alert)));
    db.update("UPDATE device_channels SET upper_limit=100 WHERE tenant_id=? AND device_id=?",f.tenant(),f.device());
    time.advance(Duration.ofSeconds(60));stream.refresh(time.instant(),false);
    assertEquals(0,openAlerts(f,"TEMPERATURE"));
    assertEquals("RESOLVED",db.queryForObject("SELECT status FROM device_alerts WHERE tenant_id=? AND id=?",String.class,f.tenant(),alert));
  }

  @Test void farmMetadataSeparatesCurrentTelemetryFromDisabledBusinessSimulation(){
    var f=fixture("demo-a",true,true,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    var ordinary=fixture("demo-a",false,false,"SIMULATED","SIMULATED","ACTIVE","DEMO-");
    var archived=fixture("demo-a",true,false,"SIMULATED","SIMULATED","ACTIVE","POINT-");archive(archived);
    String empty=UUID.randomUUID().toString();
    db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,'虚构无设备场')",empty,f.tenant(),empty);
    db.update("INSERT INTO farm_profiles(tenant_id,farm_id,demo) VALUES(?,?,TRUE)",f.tenant(),empty);
    var identity=new Identity("test-member",f.tenant(),"虚构租户","test-admin","测试管理员","ADMIN");
    Identity.runAs(identity,()->{
      var cards=workspace.cards();
      var live=card(cards,f.farm());
      assertEquals(Boolean.TRUE,live.get("telemetryLive"));assertEquals(Boolean.FALSE,live.get("demoLive"));
      assertEquals(Boolean.FALSE,card(cards,ordinary.farm()).get("telemetryLive"));
      assertEquals(Boolean.FALSE,card(cards,empty).get("telemetryLive"));
      assertEquals(Boolean.FALSE,card(workspace.cards(true),archived.farm()).get("telemetryLive"));
    });
  }

  Fixture fixture(String code,boolean demo,boolean operating,String adapter,String protocol,String lifecycle,String prefix){
    String tenant=tenants.computeIfAbsent(code,key->{String id=UUID.randomUUID().toString();db.update("INSERT INTO tenants(id,code,name) VALUES(?,?,?)",id,key,"虚构恢复租户"+key);return id;});
    String farm=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();
    db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,'虚构恢复农场')",farm,tenant,farm);
    db.update("INSERT INTO farm_profiles(tenant_id,farm_id,demo) VALUES(?,?,?)",tenant,farm,demo);
    if(operating)db.update("INSERT INTO demo_operating_farms(tenant_id,farm_id,seeded_on,last_daily_date) VALUES(?,?,?,?)",tenant,farm,LocalDate.of(2026,1,1),LocalDate.of(2026,1,2));
    db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,'TEMPERATURE','℃',?)",device,tenant,farm,device,adapter);
    db.update("INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol,lifecycle,interval_seconds) VALUES(?,?,?,'WEATHER',?,?,60)",tenant,device,prefix+device,protocol,lifecycle);
    db.update("INSERT INTO device_channels(tenant_id,device_id,metric) VALUES(?,?,'TEMPERATURE')",tenant,device);
    return new Fixture(tenant,farm,device);
  }
  void archive(Fixture f){db.update("INSERT INTO farm_archives(tenant_id,farm_id,reason,archived_at) VALUES(?,?,'虚构归档',?)",f.tenant(),f.farm(),START.atOffset(ZoneOffset.UTC));}
  void reading(Fixture f,String metric,BigDecimal value,Instant at,String source){db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),f.tenant(),f.device(),metric,value,at.atOffset(ZoneOffset.UTC),at.plusSeconds(1).atOffset(ZoneOffset.UTC),source);}
  void observation(Fixture f,BigDecimal value,Instant at){db.update("INSERT INTO observations(id,tenant_id,device_id,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,'MANUAL')",UUID.randomUUID().toString(),f.tenant(),f.device(),value,java.sql.Timestamp.from(at),at.atOffset(ZoneOffset.UTC));}
  long readingCount(Fixture f){return db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=?",Long.class,f.tenant(),f.device());}
  int openAlerts(Fixture f,String metric){return db.queryForObject("SELECT COUNT(*) FROM device_alerts WHERE tenant_id=? AND device_id=? AND metric=? AND status<>'RESOLVED'",Integer.class,f.tenant(),f.device(),metric);}
  BigDecimal valueAt(Fixture f,String metric,Instant at){return db.queryForObject("SELECT measured_value FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=? AND measured_at=?",BigDecimal.class,f.tenant(),f.device(),metric,at.atOffset(ZoneOffset.UTC));}
  List<Instant> currentTimes(Fixture f){return db.queryForList("SELECT measured_at FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND measured_at>=? ORDER BY measured_at",Object.class,f.tenant(),f.device(),START.atOffset(ZoneOffset.UTC)).stream().map(DatabaseTime::instant).toList();}
  List<Map<String,Object>> historicalReadings(Instant before){return db.queryForList("SELECT * FROM telemetry_readings WHERE measured_at<? ORDER BY id",before.atOffset(ZoneOffset.UTC));}
  Map<String,Long> businessCounts(){var result=new LinkedHashMap<String,Long>();for(String table:List.of("farms","plots","plantings","farm_tasks","production","production_tracking","production_history","field_issues","demo_operating_farms"))result.put(table,db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return result;}
  Map<String,Object> card(List<Map<String,Object>> cards,String farm){return cards.stream().filter(row->farm.equals(row.get("id"))).findFirst().orElseThrow();}
  void addBusinessHistory(Fixture f){
    String plot=UUID.randomUUID().toString(),planting=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),production=UUID.randomUUID().toString();
    db.update("INSERT INTO plots(id,tenant_id,farm_id,name,area_mu,crop) VALUES(?,?,?,'虚构历史地块',5,'水稻')",plot,f.tenant(),f.farm());
    db.update("INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,'水稻','虚构品种',5,'2025-01-01','2025-10-01','FINISHED')",planting,f.tenant(),plot);
    db.update("INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,'虚构历史任务','HARVEST','2025-10-01','COMPLETED','保留原始内容')",task,f.tenant(),plot);
    db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,'2025-10-01',1234.56,'保留虚构原始产量')",production,f.tenant(),plot);
    db.update("INSERT INTO production_tracking(tenant_id,production_id,planting_id,task_id,updated_at) VALUES(?,?,?,?,?)",f.tenant(),production,planting,task,START.minus(Duration.ofDays(300)).atOffset(ZoneOffset.UTC));
    db.update("INSERT INTO production_history(id,tenant_id,production_id,revision,action,reason,actor,snapshot_json,occurred_at) VALUES(?,?,?,0,'LEGACY','虚构保留记录','TEST','{}',?)",UUID.randomUUID().toString(),f.tenant(),production,START.minus(Duration.ofDays(300)).atOffset(ZoneOffset.UTC));
  }
}
