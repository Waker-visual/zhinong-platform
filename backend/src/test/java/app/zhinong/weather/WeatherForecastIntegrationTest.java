package app.zhinong.weather;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.business.*;
import app.zhinong.security.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "spring.datasource.url=jdbc:h2:mem:weather-forecast-tests;DB_CLOSE_DELAY=-1","farm.demo=true","farm.demo-rich=false",
  "farm.demo-live=false","farm.weather.enabled=true","farm.alerts.automation-enabled=false","farm.llm.file-enabled=false",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
@Import(WeatherForecastIntegrationTest.Config.class)
class WeatherForecastIntegrationTest {
  static final Instant START=Instant.parse("2026-10-17T00:00:00Z");
  @TestConfiguration static class Config {
    @Bean MutableClock weatherTestClock() {return new MutableClock();}
    @Bean @Primary FakeGateway forecastTestGateway() {return new FakeGateway();}
  }
  static final class MutableClock extends Clock {
    volatile Instant now=START;
    public Instant instant() {return now;}
    public ZoneId getZone() {return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone) {return new Clock() {public Instant instant(){return now;}public ZoneId getZone(){return zone;}public Clock withZone(ZoneId other){return MutableClock.this.withZone(other);}};}
    void advance(Duration amount) {now=now.plus(amount);}
  }
  static final class FakeGateway implements WeatherForecastGateway {
    record Request(double latitude,double longitude,ZoneId zone,LocalDate firstDate) {}
    final List<Request> requests=new CopyOnWriteArrayList<>();
    volatile Supplier<List<Day>> answer=List::of;
    public List<Day> fetch(double latitude,double longitude,ZoneId zone,LocalDate date) {requests.add(new Request(latitude,longitude,zone,date));return answer.get();}
  }
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired FarmClock clock;
  @Autowired MutableClock time;
  @Autowired FakeGateway gateway;
  @Autowired Store store;
  @Autowired AuthService auth;
  final HttpClient http=HttpClient.newHttpClient();
  String admin,viewer,other,platform,tenant,farm;

  @BeforeEach void setup() throws Exception {
    time.now=START;gateway.requests.clear();gateway.answer=()->forecast(clock.today());
    admin=login("demo-a","admin");viewer=login("demo-a","viewer");other=login("demo-b","admin");platform=login("platform","platform");
    tenant=call(admin,"GET","/auth/me",null,200).path("tenantId").asText();
    farm=call(admin,"POST","/farms",Map.of("name","虚构预报田-"+UUID.randomUUID(),"description","无外部联网的预报回归"),200).path("ID").asText();
  }

  List<WeatherForecastGateway.Day> forecast(LocalDate first) {
    var days=new ArrayList<WeatherForecastGateway.Day>();
    for(int i=0;i<7;i++)days.add(new WeatherForecastGateway.Day(first.plusDays(i),i==0?61:0,15.0,25.0,i==0?80.0:null,i==0?0.0:null,3.0,6.0,2.0));
    return List.copyOf(days);
  }
  void save(double lat,double lon) {
    db.update("INSERT INTO farm_georeference(tenant_id,farm_id,mode,latitude,longitude,width_meters,height_meters,location_label,revision) VALUES(?,?,'SATELLITE',?,?,1600,1200,'虚构已保存中心',1)",tenant,farm,lat,lon);
  }
  JsonNode get() throws Exception {return call(viewer,"GET","/farms/"+farm+"/weather-forecast",null,200);}

  @Test void unsavedMapDefaultIsNotARealLocationAndNoForecastIsInvented() throws Exception {
    var map=call(admin,"GET","/farms/"+farm+"/map-config",null,200);assertFalse(map.path("configured").asBoolean());
    assertTrue(map.path("latitude").isNumber());
    var result=get();assertEquals("UNCONFIGURED",result.path("status").asText());assertTrue(result.path("location").isNull());
    assertEquals(0,result.path("days").size());assertTrue(gateway.requests.isEmpty());
  }

  @Test void savedCentreProducesSevenFarmCalendarDaysWithExplicitUnitsAndHonestNulls() throws Exception {
    save(31.2,121.5);var result=get();assertEquals("READY",result.path("status").asText());assertTrue(result.path("usable").asBoolean());
    assertEquals("PUBLIC_FORECAST",result.path("dataKind").asText());assertEquals("OPEN_METEO",result.path("source").asText());
    assertEquals("Asia/Shanghai",result.path("timeZone").asText());assertEquals(7,result.path("days").size());
    assertEquals(clock.today().toString(),result.at("/days/0/date").asText());assertEquals(clock.today().plusDays(6).toString(),result.at("/days/6/date").asText());
    assertEquals("小雨",result.at("/days/0/weatherLabel").asText());assertEquals(0.0,result.at("/days/0/precipitationMm").asDouble());
    assertTrue(result.at("/days/1/precipitationMm").isNull());assertEquals("m/s",result.at("/units/windMaxMs").asText());
    assertEquals("CC BY 4.0",result.at("/attribution/licence").asText());
    assertEquals(1,gateway.requests.size());var requested=gateway.requests.getFirst();
    assertEquals(31.2,requested.latitude());assertEquals(121.5,requested.longitude());assertEquals(clock.zone(),requested.zone());
    var fetched=result.path("fetchedAt");time.advance(Duration.ofMinutes(29));assertEquals(fetched,get().path("fetchedAt"));assertEquals(1,gateway.requests.size());
  }

  @Test void forbiddenReadsCannotReuseOrTriggerAnotherTenantsCachedForecast() throws Exception {
    save(31.2,121.5);get();int fetched=gateway.requests.size();String path="/farms/"+farm+"/weather-forecast";
    call(other,"GET",path,null,404);call(platform,"GET",path,null,403);call(null,"GET",path,null,401);
    assertEquals(fetched,gateway.requests.size());
  }

  @Test void concurrentReadersShareOneFetch() throws Exception {
    save(31.2,121.5);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    gateway.answer=()->{entered.countDown();try {assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return forecast(clock.today());};
    try(var workers=Executors.newFixedThreadPool(8)) {
      var first=workers.submit(this::get);assertTrue(entered.await(2,TimeUnit.SECONDS));
      var readers=new ArrayList<Future<JsonNode>>();for(int i=0;i<7;i++)readers.add(workers.submit(this::get));
      release.countDown();assertEquals("READY",first.get(5,TimeUnit.SECONDS).path("status").asText());
      for(var reader:readers)assertEquals("READY",reader.get(5,TimeUnit.SECONDS).path("status").asText());
    } finally {release.countDown();}
    assertEquals(1,gateway.requests.size());
  }

  @Test void outagesUseMarkedOldDataWithBackoffButNeverBeyondSixHours() throws Exception {
    save(31.2,121.5);var fresh=get();gateway.answer=()->{throw new WeatherForecastGateway.Unavailable("TIMEOUT");};
    time.advance(Duration.ofMinutes(30));var stale=get();assertEquals("STALE",stale.path("status").asText());
    assertTrue(stale.path("available").asBoolean());assertFalse(stale.path("usable").asBoolean());assertTrue(stale.path("stale").asBoolean());
    assertEquals(fresh.path("fetchedAt"),stale.path("fetchedAt"));assertEquals(fresh.path("expiresAt"),stale.path("expiresAt"));assertEquals(2,gateway.requests.size());
    get();assertEquals(2,gateway.requests.size());time.advance(Duration.ofMinutes(2));get();assertEquals(3,gateway.requests.size());
    time.now=START.plus(Duration.ofHours(6));var unavailable=get();assertEquals("UNAVAILABLE",unavailable.path("status").asText());
    assertEquals(0,unavailable.path("days").size());assertFalse(unavailable.path("available").asBoolean());
  }

  @Test void midnightRebasesOldForecastAndLeavesTheUncoveredDayNull() throws Exception {
    time.now=Instant.parse("2026-10-17T15:50:00Z");save(31.2,121.5);var fresh=get();
    gateway.answer=()->{throw new WeatherForecastGateway.Unavailable("NETWORK_ERROR");};time.advance(Duration.ofMinutes(20));
    var stale=get();assertEquals("STALE",stale.path("status").asText());assertEquals(7,stale.path("days").size());
    assertEquals("2026-10-18",stale.at("/days/0/date").asText());assertEquals("2026-10-24",stale.at("/days/6/date").asText());
    assertTrue(stale.at("/days/6/tempMaxC").isNull());assertEquals(fresh.path("fetchedAt"),stale.path("fetchedAt"));
  }

  @Test void locationEditsAndRemovalInvalidateCachedValuesImmediately() throws Exception {
    save(31.2,121.5);get();
    db.update("UPDATE farm_georeference SET latitude=32.3,revision=revision+1 WHERE tenant_id=? AND farm_id=?",tenant,farm);
    var changed=get();assertEquals(32.3,changed.at("/location/latitude").asDouble());assertEquals(2,gateway.requests.size());
    db.update("DELETE FROM farm_georeference WHERE tenant_id=? AND farm_id=?",tenant,farm);
    assertEquals("UNCONFIGURED",get().path("status").asText());assertEquals(2,gateway.requests.size());
  }

  @Test void locationChangeDuringAFetchDoesNotPublishTheOldPositionAsCurrent() throws Exception {
    save(31.2,121.5);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    gateway.answer=()->{entered.countDown();try {assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return forecast(clock.today());};
    try(var worker=Executors.newSingleThreadExecutor()) {
      var pending=worker.submit(this::get);assertTrue(entered.await(2,TimeUnit.SECONDS));
      db.update("UPDATE farm_georeference SET longitude=122.4,revision=revision+1 WHERE tenant_id=? AND farm_id=?",tenant,farm);
      release.countDown();var result=pending.get(5,TimeUnit.SECONDS);assertEquals("UNAVAILABLE",result.path("status").asText());
      assertEquals("LOCATION_CHANGED",result.path("diagnostic").asText());assertEquals(0,result.path("days").size());assertEquals(122.4,result.at("/location/longitude").asDouble());
    } finally {release.countDown();}
  }

  @Test void disabledForecastStillAuthorizesButNeverCallsTheProvider() {
    save(31.2,121.5);var service=new WeatherForecastService(store,clock,gateway,false);
    Identity.runAs(auth.authenticate(admin),()->assertEquals("DISABLED",service.get(farm).get("status")));
    assertTrue(gateway.requests.isEmpty());
  }

  @Test void cacheHasABoundedCapacityWithoutExposingOneFarmsIdentityToAnother() {
    var service=new WeatherForecastService(store,clock,gateway,true);var farms=new ArrayList<String>();
    Identity.runAs(auth.authenticate(admin),()->{
      for(int i=0;i<129;i++) {
        String id=UUID.randomUUID().toString();farms.add(id);
        db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,'虚构缓存容量回归')",id,tenant,"预报容量田-"+i);
        db.update("INSERT INTO farm_georeference(tenant_id,farm_id,mode,latitude,longitude,width_meters,height_meters,location_label,revision) VALUES(?,?,'PLAN',31.2,121.5,1600,1200,'虚构中心',1)",tenant,id);
        assertEquals(id,service.get(id).get("farmId"));
      }
      assertEquals(129,gateway.requests.size());assertEquals(farms.getFirst(),service.get(farms.getFirst()).get("farmId"));
      assertEquals(130,gateway.requests.size(),"the first key was evicted even when all farms use the same public coordinates");
    });
  }

  String login(String code,String user) throws Exception {return call(null,"POST","/auth/login",Map.of("tenantCode",code,"username",user,"password","Test-Only-Password-429!"),200).path("token").asText();}
  JsonNode call(String token,String method,String path,Object body,int expected) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json");
    if(token!=null)request.header("Authorization","Bearer "+token);
    var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(expected,response.statusCode(),method+" "+path+": "+response.body());return json.readTree(response.body());
  }
}
