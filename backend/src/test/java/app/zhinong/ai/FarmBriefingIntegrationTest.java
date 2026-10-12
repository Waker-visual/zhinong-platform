package app.zhinong.ai;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.security.Identity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:briefing-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=false", "farm.ai.automation-enabled=false",
  "farm.map.automation-enabled=false", "farm.bootstrap-password=Test-Only-Password-429!"
})
@Import(FarmBriefingIntegrationTest.ModelConfig.class)
class FarmBriefingIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired FarmBriefingService briefings;
  @Autowired TestLlmGateway llm;
  final HttpClient client = HttpClient.newHttpClient();
  String admin, viewer, other, platform, farm, tenant;

  @BeforeEach void setup() throws Exception {
    llm.answer = Optional::empty;
    llm.calls.set(0);
    admin = login("demo-a", "admin"); viewer = login("demo-a", "viewer");
    other = login("demo-b", "admin"); platform = login("platform", "platform");
    farm = farm(admin);
    tenant = db.queryForObject("SELECT tenant_id FROM farms WHERE id=?", String.class, farm);
  }

  @Test void sameDayConcurrentVisitsReturnOneDurableReportAndOneModelCall() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    llm.answer = () -> {
      entered.countDown();
      try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
      catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
      return Optional.of("经营判断：尚无收入成本记录。今日先做：巡田核实。复查条件：补齐记录后再评价。");
    };
    try {
      var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
      for (int i = 0; i < 12; i++) requests.add(client.sendAsync(request(viewer, "GET", "/ai/briefings/today?farmId=" + farm, null), HttpResponse.BodyHandlers.ofString()));
      CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(8, TimeUnit.SECONDS);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var ids = new HashSet<String>();
      for (var future : requests) {
        var response = future.get(); assertEquals(200, response.statusCode(), response.body());
        var report = json.readTree(response.body()); ids.add(report.path("id").asText());
        assertEquals("RULE_ENGINE", report.path("source").asText());
        assertTrue(Set.of("PENDING", "GENERATING").contains(report.path("status").asText()));
        assertTrue(report.path("summary").asText().contains("暂无生产记录"));
      }
      assertEquals(1, ids.size());
    } finally { release.countDown(); }
    var report = ready(farm);
    assertEquals("LLM", report.path("source").asText());
    assertEquals(LocalDate.now(FarmBriefingService.ZONE).toString(), report.path("reportDate").asText());
    assertEquals("Asia/Shanghai", report.path("timeZone").asText());
    assertTrue(report.path("aiSummary").asText().contains("复查条件"));
    assertEquals(report.path("id"), call(viewer, "GET", "/ai/briefings/today?farmId=" + farm, null, 200).path("id"));
    assertEquals(1, call(admin, "GET", "/ai/briefings/history?farmId=" + farm, null, 200).size());
    assertEquals(1, llm.calls.get());
  }

  @Test void dataIsFarmScopedAndUsesSevenCalendarDaysWithHonestGaps() throws Exception {
    String second = farm(admin), foreign = farm(other);
    String plot = plot(farm, tenant), secondPlot = plot(second, tenant);
    String foreignTenant = db.queryForObject("SELECT tenant_id FROM farms WHERE id=?", String.class, foreign);
    String foreignPlot = plot(foreign, foreignTenant);
    LocalDate date = LocalDate.now(FarmBriefingService.ZONE);
    production(tenant, plot, date.minusDays(6), 40); production(tenant, plot, date, 60);
    production(tenant, plot, date.minusDays(7), 700); production(tenant, plot, date.plusDays(1), 900);
    production(tenant, secondPlot, date, 2000); production(foreignTenant, foreignPlot, date, 3000);
    db.update("INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(), tenant, plot, "虚构逾期巡查", "INSPECT", date.minusDays(1), "PENDING", "测试数据");
    var report = call(viewer, "GET", "/ai/briefings/today?farmId=" + farm, null, 200);
    assertEquals(100, report.path("metrics").path("yieldKg7d").asInt());
    assertEquals(2, report.path("metrics").path("productionRecords7d").asInt());
    assertEquals(1, report.path("metrics").path("overdueTasks").asInt());
    assertTrue(report.path("dataGaps").toString().contains("利润"));
    assertTrue(report.path("dataGaps").toString().contains("天气预报"));
    assertEquals("SYNTHETIC_RESEARCH", report.path("dataPolicy").asText());
    assertFalse(report.toString().contains(secondPlot));
    var completed = ready(farm);
    assertEquals("RULE_ENGINE", completed.path("source").asText());
    assertEquals("NOT_CONFIGURED", completed.path("diagnostic").asText());
    assertTrue(completed.path("aiSummary").isNull());
    // A later record does not silently rewrite an already issued daily snapshot.
    production(tenant, plot, date, 1000);
    assertEquals(100, call(admin, "GET", "/ai/briefings/today?farmId=" + farm, null, 200).path("metrics").path("yieldKg7d").asInt());
  }

  @Test void foreignTenantsAndPlatformCannotReadOrTriggerModel() throws Exception {
    call(other, "GET", "/ai/briefings/today?farmId=" + farm, null, 404);
    call(other, "GET", "/ai/briefings/history?farmId=" + farm, null, 404);
    call(platform, "GET", "/ai/briefings/today?farmId=" + farm, null, 403);
    call(platform, "GET", "/ai/briefings/history?farmId=" + farm, null, 403);
    assertEquals(0, llm.calls.get());
    assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM ai_farm_briefings WHERE tenant_id=? AND farm_id=?", Long.class, tenant, farm));
  }

  @Test void readOnlyToolDoesNotStartGenerationAndAbandonedWorkRecoversWithoutRetry() throws Exception {
    var identity = new Identity("unused", tenant, "虚构租户", "viewer", "只读测试", "VIEWER");
    Identity.runAs(identity, () -> assertEquals(false, briefings.peekToday(farm).get("available")));
    assertEquals(0, llm.calls.get());
    call(admin, "GET", "/ai/briefings/today?farmId=" + farm, null, 200); ready(farm);
    db.update("UPDATE ai_farm_briefings SET status='GENERATING',updated_at=? WHERE tenant_id=? AND farm_id=?",
      OffsetDateTime.now().minusMinutes(11), tenant, farm);
    var report = call(admin, "GET", "/ai/briefings/today?farmId=" + farm, null, 200);
    assertEquals("READY", report.path("status").asText());
    assertEquals("INTERRUPTED", report.path("diagnostic").asText());
    assertEquals(1, llm.calls.get());
    db.update("UPDATE ai_farm_briefings SET report_date=?,status='PENDING',updated_at=? WHERE tenant_id=? AND farm_id=?",
      LocalDate.now(FarmBriefingService.ZONE).minusDays(1), OffsetDateTime.now().minusMinutes(11), tenant, farm);
    var history = call(admin, "GET", "/ai/briefings/history?farmId=" + farm, null, 200);
    assertEquals("READY", history.get(0).path("status").asText());
    assertEquals("INTERRUPTED", history.get(0).path("diagnostic").asText());
    assertEquals(1, llm.calls.get());
  }

  @Test void schedulerUsesEnabledMembersAndSkipsArchivedOrPlatformOnlyTenants() throws Exception {
    String eligibleTenant = newTenant("VIEWER", true, true), disabledMemberTenant = newTenant("ADMIN", false, true);
    String disabledTenant = newTenant("ADMIN", true, false), platformOnlyTenant = newTenant("PLATFORM_ADMIN", true, true);
    String eligibleFarm = insertFarm(eligibleTenant), archivedFarm = insertFarm(eligibleTenant);
    String disabledMemberFarm = insertFarm(disabledMemberTenant), disabledFarm = insertFarm(disabledTenant), platformFarm = insertFarm(platformOnlyTenant);
    db.update("INSERT INTO farm_archives(tenant_id,farm_id,reason,archived_at) VALUES(?,?,?,?)", eligibleTenant, archivedFarm, "虚构归档", OffsetDateTime.now());
    briefings.sweep();
    assertEquals(1L, reportCount(eligibleTenant, eligibleFarm));
    assertEquals(0L, reportCount(eligibleTenant, archivedFarm));
    assertEquals(0L, reportCount(disabledMemberTenant, disabledMemberFarm));
    assertEquals(0L, reportCount(disabledTenant, disabledFarm));
    assertEquals(0L, reportCount(platformOnlyTenant, platformFarm));
    // Ensure all queued calls finish before resetting the shared test gateway for another test.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (db.queryForObject("SELECT COUNT(*) FROM ai_farm_briefings WHERE status<>'READY'", Long.class) > 0 && System.nanoTime() < deadline) Thread.sleep(20);
    assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM ai_farm_briefings WHERE status<>'READY'", Long.class));
  }

  private long reportCount(String scopedTenant, String scopedFarm) {
    return db.queryForObject("SELECT COUNT(*) FROM ai_farm_briefings WHERE tenant_id=? AND farm_id=?", Long.class, scopedTenant, scopedFarm);
  }
  private String newTenant(String role, boolean memberEnabled, boolean tenantEnabled) {
    String id = UUID.randomUUID().toString();
    db.update("INSERT INTO tenants(id,code,name,enabled) VALUES(?,?,?,?)", id, "t-" + id, "虚构调度租户", tenantEnabled);
    db.update("INSERT INTO members(id,tenant_id,username,display_name,password_hash,role,enabled) VALUES(?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(), id, "test", "虚构调度成员", "unusable-test-hash", role, memberEnabled);
    return id;
  }
  private String insertFarm(String scopedTenant) {
    String id = UUID.randomUUID().toString();
    db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,?)", id, scopedTenant, "虚构农场" + id, "测试");
    return id;
  }
  private String plot(String scopedFarm, String scopedTenant) {
    String id = UUID.randomUUID().toString();
    db.update("INSERT INTO plots(id,tenant_id,farm_id,name,area_mu,crop) VALUES(?,?,?,?,?,?)", id, scopedTenant, scopedFarm, "虚构地块" + id, 10, "玉米");
    return id;
  }
  private void production(String scopedTenant, String plot, LocalDate date, int kg) {
    db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(), scopedTenant, plot, date, kg, "虚构采收记录");
  }
  private String farm(String token) throws Exception {
    return call(token, "POST", "/farms", Map.of("name", "虚构日报测试" + UUID.randomUUID(), "description", "虚构测试数据"), 200).path("ID").asText();
  }
  private JsonNode ready(String scopedFarm) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    JsonNode report;
    do {
      report = call(admin, "GET", "/ai/briefings/today?farmId=" + scopedFarm, null, 200);
      if ("READY".equals(report.path("status").asText())) return report;
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    fail("Daily report did not finish: " + report); return report;
  }
  private String login(String code, String username) throws Exception {
    return call(null, "POST", "/auth/login", Map.of("tenantCode", code, "username", username, "password", "Test-Only-Password-429!"), 200).path("token").asText();
  }
  private HttpRequest request(String token, String method, String path, Object body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path)).header("Content-Type", "application/json").timeout(Duration.ofSeconds(15));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
  }
  private JsonNode call(String token, String method, String path, Object body, int status) throws Exception {
    var response = client.send(request(token, method, path, body), HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), method + " " + path + " " + response.body());
    return json.readTree(response.body());
  }

  @TestConfiguration static class ModelConfig {
    @Bean @Primary TestLlmGateway testLlmGateway() { return new TestLlmGateway(); }
  }
  static class TestLlmGateway extends LlmGateway {
    final AtomicInteger calls = new AtomicInteger();
    volatile Supplier<Optional<String>> answer = Optional::empty;
    @Override public Optional<String> chat(String system, String user) { calls.incrementAndGet(); return answer.get(); }
    @Override public String diagnostic() { return "NOT_CONFIGURED"; }
  }
}
