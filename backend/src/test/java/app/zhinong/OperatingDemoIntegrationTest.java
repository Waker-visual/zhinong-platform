package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.bootstrap.OperatingDemoData;
import app.zhinong.workspace.TelemetryService;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:operating-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=true", "farm.demo-live=false",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class OperatingDemoIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired OperatingDemoData demo;
  @Autowired TelemetryService telemetry;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();

  String tenant() { return db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'", String.class); }
  String farm() { return db.queryForObject("SELECT farm_id FROM demo_operating_farms WHERE tenant_id=?", String.class, tenant()); }
  long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, tenant()); }
  String login(String code, String user) throws Exception {
    return call(null, "POST", "/auth/login", Map.of("tenantCode", code, "username", user, "password", "Test-Only-Password-429!"), 200).path("token").asText();
  }
  JsonNode call(String token, String method, String path, Object body, int status) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
      .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30));
    if (token != null) request.header("Authorization", "Bearer " + token);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  @Test void completeScenarioIsVisibleThroughTenantScopedApis() throws Exception {
    String admin = login("demo-a", "admin"), id = farm();
    var work = call(admin, "GET", "/field-work?farmId=" + id, null, 200);
    assertEquals(66, work.path("tasks").size());
    long pending = 0, completed = 0, blocked = 0;
    for (var task : work.path("tasks")) {
      assertFalse(task.path("ASSIGNEE_ID").asText().isBlank());
      String state = task.path("STATUS").asText();
      if (Set.of("PENDING", "RUNNING").contains(state)) pending++;
      if (state.equals("COMPLETED")) {
        completed++;
        assertTrue(task.path("ACTUAL_AREA_MU").asDouble() > 0);
        assertFalse(task.path("COMPLETION_NOTE").asText().isBlank());
        assertFalse(call(admin, "GET", "/field-work/tasks/" + task.path("ID").asText() + "/logs", null, 200).isEmpty());
      }
      if (!task.path("BLOCKED_REASON").asText().isBlank()) blocked++;
    }
    assertEquals(8, pending); assertEquals(57, completed); assertEquals(1, blocked);
    assertEquals(7, work.path("issues").size());
    assertEquals(20, call(admin, "GET", "/production?farmId=" + id, null, 200).size());
    assertEquals(11, call(admin, "GET", "/plantings?farmId=" + id, null, 200).size());
    var operations = call(admin, "GET", "/farms/" + id + "/operations", null, 200);
    assertEquals(8, operations.at("/summary/openTasks").asInt());
    assertEquals(4, operations.at("/summary/openIssues").asInt());
    call(login("demo-b", "admin"), "GET", "/field-work?farmId=" + id, null, 404);
    call(login("platform", "platform"), "GET", "/farm-workspaces", null, 403);
    var cards = call(login("demo-a", "viewer"), "GET", "/farm-workspaces", null, 200);
    boolean found = false;
    for (var card : cards) if (card.path("id").asText().equals(id)) {
      found = true; assertTrue(card.path("operatingDemo").asBoolean()); assertFalse(card.path("demoLive").asBoolean());
    }
    assertTrue(found);
  }

  @Test @Transactional void initializationIsAdditiveAndLeavesManualEditsAndOtherTenantsAlone() {
    String tenant = tenant(), farm = farm();
    long tasks = count("farm_tasks"), production = count("production"), readings = count("telemetry_readings");
    String otherIssuesSql = "SELECT COUNT(*) FROM field_issues i JOIN tenants t ON t.id=i.tenant_id WHERE t.code='demo-b'";
    int otherIssues = db.queryForObject(otherIssuesSql, Integer.class);
    String task = db.queryForObject("SELECT MIN(t.id) FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id WHERE t.tenant_id=? AND p.farm_id=?", String.class, tenant, farm);
    db.update("UPDATE farm_tasks SET note='用户保留的演示备注' WHERE tenant_id=? AND id=?", tenant, task);
    demo.initialize(); demo.initialize();
    assertEquals(tasks, count("farm_tasks")); assertEquals(production, count("production")); assertEquals(readings, count("telemetry_readings"));
    assertEquals("用户保留的演示备注", db.queryForObject("SELECT note FROM farm_tasks WHERE tenant_id=? AND id=?", String.class, tenant, task));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM demo_operating_farms", Integer.class));
    assertEquals(otherIssues, db.queryForObject(otherIssuesSql, Integer.class));
  }

  @Test @Transactional void existingBusinessHistoryWithoutAnOperatingRegistryIsNotSeededAgain() {
    String tenant=tenant(), farm=farm();
    db.update("DELETE FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?",tenant,farm);
    long tasks=count("farm_tasks"), production=count("production"), readings=count("telemetry_readings");
    demo.initialize();
    assertEquals(tasks,count("farm_tasks"));
    assertEquals(production,count("production"));
    assertEquals(readings,count("telemetry_readings"));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?",Integer.class,tenant,farm));
  }

  @Test @Transactional void tickingIsScopedSkipsNonSimulatedOrInactiveDevicesAndPreservesControlFeedback() {
    String tenant = tenant(), farm = farm();
    var devices = db.queryForList("SELECT d.id,p.device_type FROM devices d JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id WHERE d.tenant_id=? AND d.farm_id=?", tenant, farm);
    String gate = devices.stream().filter(d -> d.get("DEVICE_TYPE").equals("GATE")).findFirst().orElseThrow().get("ID").toString();
    db.update("UPDATE telemetry_readings SET measured_value=47 WHERE tenant_id=? AND device_id=? AND metric='GATE_OPENING'", tenant, gate);
    db.update("UPDATE telemetry_readings SET measured_at=DATEADD('MINUTE',-3,measured_at) WHERE tenant_id=?", tenant);
    db.update("UPDATE asset_profiles SET lifecycle='MAINTENANCE' WHERE tenant_id=? AND device_type='SOIL'", tenant);
    db.update("UPDATE asset_profiles SET protocol='HTTP_PUSH' WHERE tenant_id=? AND device_type='WEATHER'", tenant);
    long external = db.queryForObject("SELECT COUNT(*) FROM telemetry_readings r JOIN devices d ON d.tenant_id=r.tenant_id AND d.id=r.device_id WHERE d.tenant_id<>? OR d.farm_id<>?", Long.class, tenant, farm);
    long commands = db.queryForObject("SELECT COUNT(*) FROM device_commands", Long.class);
    var before = new HashMap<String, Long>();
    for (var device : devices) before.put(device.get("ID").toString(), db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=?", Long.class, tenant, device.get("ID")));
    demo.tick();
    for (var device : devices) {
      String id = device.get("ID").toString();
      long after = db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=?", Long.class, tenant, id);
      if (Set.of("SOIL", "WEATHER").contains(device.get("DEVICE_TYPE"))) assertEquals(before.get(id).longValue(), after);
      else assertTrue(after > before.get(id));
    }
    assertEquals(47, db.queryForObject("SELECT measured_value FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='GATE_OPENING' ORDER BY measured_at DESC LIMIT 1", Double.class, tenant, gate));
    assertEquals(external, db.queryForObject("SELECT COUNT(*) FROM telemetry_readings r JOIN devices d ON d.tenant_id=r.tenant_id AND d.id=r.device_id WHERE d.tenant_id<>? OR d.farm_id<>?", Long.class, tenant, farm));
    assertEquals(commands, db.queryForObject("SELECT COUNT(*) FROM device_commands", Long.class));
    long after = count("telemetry_readings"); demo.tick(); assertEquals(after, count("telemetry_readings"));
    new OperatingDemoData(db, telemetry, false, true).tick(); assertEquals(after, count("telemetry_readings"));
  }

  @Test @Transactional void newDayAddsOnlyTodaysRoutineOnceWithoutCompletingOrRewritingExistingWork() {
    String tenant = tenant(), farm = farm();
    long before = count("farm_tasks"), production = count("production");
    db.update("UPDATE demo_operating_farms SET last_daily_date=? WHERE tenant_id=? AND farm_id=?", LocalDate.now().minusDays(10), tenant, farm);
    demo.tick(); assertEquals(before + 2, count("farm_tasks"));
    demo.tick(); assertEquals(before + 2, count("farm_tasks"));
    assertEquals(production, count("production"));
    assertEquals(LocalDate.now(), db.queryForObject("SELECT last_daily_date FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?", java.sql.Date.class, tenant, farm).toLocalDate());
  }

  @Test @Transactional void oldUntouchedFixtureUpgradesWithHarvestLineageAndWithoutDuplicates() {
    var upgrade = new app.zhinong.bootstrap.ResearchDemoData(db, demo, true, true, true);
    upgrade.initialize();
    long count = count("farm_tasks");
    assertTrue(count > 350);
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM demo_research_farms WHERE tenant_id=?", Integer.class, tenant()));
    assertTrue(count("production_lineage") > 30);
    upgrade.initialize(); assertEquals(count, count("farm_tasks"));
  }

  @Test @Transactional void oldFixtureWithUserChangesIsNotReplaced() {
    String tenant=tenant(), farm=farm();
    String task=db.queryForObject("SELECT MIN(t.id) FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id WHERE t.tenant_id=? AND p.farm_id=?",String.class,tenant,farm);
    db.update("UPDATE farm_tasks SET note='用户补充的现场记录' WHERE tenant_id=? AND id=?",tenant,task);
    long count=count("farm_tasks");
    new app.zhinong.bootstrap.ResearchDemoData(db,demo,true,true,true).initialize();
    assertEquals(count,count("farm_tasks"));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM demo_research_farms WHERE tenant_id=?",Integer.class,tenant));
  }
}
