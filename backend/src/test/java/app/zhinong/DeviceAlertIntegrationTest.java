package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.workspace.DeviceAlertMonitor;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:device-alert-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=false", "farm.alerts.automation-enabled=false",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class DeviceAlertIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired DeviceAlertMonitor monitor;
  @Autowired DataSource dataSource;
  final HttpClient client = HttpClient.newHttpClient();
  String admin, other, viewer, platform, tenant;

  @BeforeEach void login() throws Exception {
    admin = login("demo-a", "admin"); other = login("demo-b", "admin");
    viewer = login("demo-a", "viewer"); platform = login("platform", "platform");
    tenant = db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'", String.class);
  }

  @Test void offlineIncidentsDeduplicatePreserveAcknowledgementAndRecoverOnReceipt() throws Exception {
    var device = device("HTTP_PUSH"); String id = device.get("id"), farm = device.get("farm");
    assertEquals(0, alerts(farm).size(), "newly registered devices get a grace period");
    age(id, 600);
    monitor.scanTenant(tenant); // Scheduled path works without a logged-in request identity.
    var opened = alerts(farm);
    assertEquals(1, opened.size());
    var alert = opened.get(0);
    assertEquals("OFFLINE", alert.path("kind").asText());
    assertEquals("HIGH", alert.path("severity").asText());
    assertEquals(180, alert.path("thresholdSeconds").asInt());
    assertTrue(alert.path("recommendation").asText().contains("电源"));
    assertFalse(alert.path("evidence").asText().isBlank());
    String alertId = alert.path("id").asText();
    call(admin, "PATCH", "/alerts/" + alertId, Map.of("status", "ACKNOWLEDGED", "note", "已安排现场排查"), 200);
    assertEquals("ACKNOWLEDGED", alerts(farm).get(0).path("status").asText());
    assertEquals(1, count(id, "OFFLINE", false));
    ingest(id, Instant.now().minusSeconds(1), List.of(reading("SOIL_MOISTURE", 35), reading("TEMPERATURE", 25)));
    assertEquals(0, alerts(farm).size());
    var history = call(admin, "GET", "/alerts?farmId=" + farm + "&status=ALL", null, 200);
    assertEquals("RESOLVED", history.get(0).path("status").asText());
    assertEquals("SYSTEM", history.get(0).path("handledBy").asText());
    age(id, 600);
    assertEquals(1, alerts(farm).size());
    assertEquals(2, count(id, "OFFLINE", true), "a later interruption is a new incident");
  }

  @Test void lateOrPartialDataIsNotTreatedAsRestoredProductionTelemetry() throws Exception {
    var device = device("HTTP_PUSH"); String id = device.get("id"), farm = device.get("farm");
    age(id, 600);
    assertEquals("OFFLINE", alerts(farm).get(0).path("kind").asText());
    ingest(id, Instant.now().minusSeconds(900), List.of(reading("SOIL_MOISTURE", 35), reading("TEMPERATURE", 25)));
    var stale = alerts(farm);
    assertEquals(1, stale.size());
    assertEquals("DATA_STALE", stale.get(0).path("kind").asText());
    ingest(id, Instant.now().minusSeconds(1), List.of(reading("SOIL_MOISTURE", 35)));
    assertEquals("DATA_STALE", alerts(farm).get(0).path("kind").asText());
    ingest(id, Instant.now().minusSeconds(1), List.of(reading("TEMPERATURE", 25)));
    assertEquals(0, alerts(farm).size());
    ingest(id, Instant.now().minusSeconds(1), List.of(reading("SOIL_MOISTURE", 10)));
    var threshold = alerts(farm).get(0);
    assertEquals("THRESHOLD", threshold.path("kind").asText());
    assertTrue(threshold.path("recommendation").asText().contains("复测土壤"));
    assertEquals("RULE_ENGINE", threshold.path("source").asText());
    ingest(id, Instant.now().minusSeconds(900), List.of(reading("SOIL_MOISTURE", 35)));
    assertEquals("THRESHOLD", alerts(farm).get(0).path("kind").asText(), "late normal data cannot close a current anomaly");
  }

  @Test void offlineMonitoringExcludesManualMaintenanceDisabledAndArchivedDevices() throws Exception {
    var manual = device("MANUAL"); age(manual.get("id"), 600);
    assertEquals(0, alerts(manual.get("farm")).size());
    for (String lifecycle : List.of("MAINTENANCE", "DISABLED")) {
      var device = device("HTTP_PUSH"); age(device.get("id"), 600);
      assertEquals(1, alerts(device.get("farm")).size());
      db.update("UPDATE asset_profiles SET lifecycle=? WHERE tenant_id=? AND device_id=?", lifecycle, tenant, device.get("id"));
      assertEquals(0, alerts(device.get("farm")).size());
    }
    var archived = device("HTTP_PUSH"); age(archived.get("id"), 600);
    assertEquals(1, alerts(archived.get("farm")).size());
    // Farm archival itself is covered by workspace tests; exercise the monitor's archived-farm boundary.
    db.update("INSERT INTO farm_archives(tenant_id,farm_id,archived_at,reason) VALUES(?,?,CURRENT_TIMESTAMP,?)",
      tenant, archived.get("farm"), "虚构归档测试");
    monitor.scanTenant(tenant);
    assertEquals(0, count(archived.get("id"), "OFFLINE", false));
  }

  @Test void healthAlertsKeepTenantPermissionsAndCanBecomeEquipmentFieldIssues() throws Exception {
    var device = device("HTTP_PUSH"); String id = device.get("id"), farm = device.get("farm");
    age(id, 600);
    var alert = alerts(farm).get(0); String alertId = alert.path("id").asText();
    call(other, "GET", "/alerts?farmId=" + farm, null, 404);
    call(platform, "GET", "/alerts", null, 403);
    call(viewer, "PATCH", "/alerts/" + alertId, Map.of("status", "ACKNOWLEDGED", "note", "虚构排查"), 403);
    call(other, "PATCH", "/alerts/" + alertId, Map.of("status", "ACKNOWLEDGED", "note", "虚构排查"), 404);
    var input = Map.of("severity", "HIGH", "note", "请检查设备供电与网络");
    var issue = call(admin, "POST", "/field-work/alerts/" + alertId + "/issue", input, 200);
    assertEquals("EQUIPMENT", db.queryForObject("SELECT category FROM field_issues WHERE tenant_id=? AND id=?", String.class, tenant, issue.path("id").asText()));
    assertTrue(call(admin, "POST", "/field-work/alerts/" + alertId + "/issue", input, 200).path("duplicate").asBoolean());
    assertEquals(issue.path("id").asText(), alerts(farm).get(0).path("issueId").asText());
    call(other, "POST", "/field-work/alerts/" + alertId + "/issue", input, 404);
  }

  @Test void missingFirstReportHonorsConfiguredIntervalAndConcurrentChecksDoNotDuplicate() throws Exception {
    var device = device("HTTP_PUSH"); String id = device.get("id"), farm = device.get("farm");
    db.update("UPDATE asset_profiles SET interval_seconds=900 WHERE tenant_id=? AND device_id=?", tenant, id);
    db.update("UPDATE audit_events SET occurred_at=? WHERE tenant_id=? AND resource_id=? AND action='SAVE_ASSET'", java.sql.Timestamp.from(Instant.now().minusSeconds(600)), tenant, id);
    assertEquals(0, alerts(farm).size(), "900-second devices have a 2700-second grace period");
    db.update("UPDATE audit_events SET occurred_at=? WHERE tenant_id=? AND resource_id=? AND action='SAVE_ASSET'", java.sql.Timestamp.from(Instant.now().minusSeconds(3000)), tenant, id);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> monitor.scanFarm(tenant, farm));
      var second = workers.submit(() -> monitor.scanFarm(tenant, farm));
      first.get(10, java.util.concurrent.TimeUnit.SECONDS); second.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(1, count(id, "OFFLINE", true));
    assertEquals(2700, alerts(farm).get(0).path("thresholdSeconds").asInt());
    assertTrue(alerts(farm).get(0).path("message").asText().contains("仍未收到"));
  }

  @Test void dashboardReadsSkipBusyDeviceAndScheduledScanCatchesUpAfterCommit() throws Exception {
    var device = device("HTTP_PUSH"); String id = device.get("id"), farm = device.get("farm");
    age(id, 600);
    // A separate connection models a sampler/control transaction that stays open
    // until both HTTP reads finish. Waiting for its lock would make the reads fail.
    try (var writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try (var lock = writer.prepareStatement("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE")) {
        lock.setString(1, tenant); lock.setString(2, id);
        try (var locked = lock.executeQuery()) { assertTrue(locked.next()); }
      }
      assertEquals(0, alerts(farm).size(), "a busy device keeps its last committed alert state");
      var workspace = call(admin, "GET", "/farms/" + farm + "/workspace?days=30", null, 200);
      assertEquals(0, workspace.path("alerts").size());
      assertEquals(1, workspace.path("devices").size(), "skipping a scan must not hide the device");
      assertEquals(0, count(id, "OFFLINE", true), "a skipped scan must not invent an alert");
      writer.commit();
    }

    monitor.scanTenant(tenant); // Same entry point as the next scheduled health scan.
    assertEquals(1, count(id, "OFFLINE", true), "the next scan must not lose the deferred device");
    String incident = alerts(farm).get(0).path("id").asText();
    call(admin, "PATCH", "/alerts/" + incident,
      Map.of("status", "ACKNOWLEDGED", "note", "等待采集事务完成"), 200);

    try (var writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try (var lock = writer.prepareStatement("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE")) {
        lock.setString(1, tenant); lock.setString(2, id);
        try (var locked = lock.executeQuery()) { assertTrue(locked.next()); }
      }
      var saved = alerts(farm);
      assertEquals(1, saved.size());
      assertEquals(incident, saved.get(0).path("id").asText());
      assertEquals("ACKNOWLEDGED", saved.get(0).path("status").asText(), "contention must not erase acknowledgement");
      writer.commit();
    }
    monitor.scanTenant(tenant);
    assertEquals(1, count(id, "OFFLINE", true), "a retry must reuse the existing incident");
  }

  long count(String id, String metric, boolean history) {
    return db.queryForObject("SELECT COUNT(*) FROM device_alerts WHERE tenant_id=? AND device_id=? AND metric=?" + (history ? "" : " AND status<>'RESOLVED'"), Long.class, tenant, id, metric);
  }
  void age(String id, int seconds) {
    db.update("UPDATE asset_profiles SET last_received_at=? WHERE tenant_id=? AND device_id=?", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(seconds), tenant, id);
    db.update("UPDATE audit_events SET occurred_at=? WHERE tenant_id=? AND resource_id=? AND action='SAVE_ASSET'", java.sql.Timestamp.from(Instant.now().minusSeconds(seconds)), tenant, id);
  }
  JsonNode alerts(String farm) throws Exception { return call(admin, "GET", "/alerts?farmId=" + farm, null, 200); }
  Map<String, Object> reading(String metric, int value) { return Map.of("metric", metric, "value", value); }
  void ingest(String id, Instant at, List<Map<String, Object>> readings) throws Exception {
    String key = call(admin, "POST", "/assets/" + id + "/credentials", null, 200).path("key").asText();
    call(null, "POST", "/ingest/telemetry", Map.of("messageId", UUID.randomUUID().toString(), "measuredAt", at.toString(), "readings", readings), 200, "X-Device-Key", key);
  }
  Map<String, String> device(String protocol) throws Exception {
    String farm = call(admin, "POST", "/farm-workspaces", Map.of("name", "虚构告警验收-" + UUID.randomUUID(), "description", "测试", "region", "虚构区域", "farmType", "FIELD"), 200).path("id").asText();
    String plot = call(admin, "POST", "/plots", Map.of("farmId", farm, "name", "虚构地块", "areaMu", 10, "crop", "水稻"), 200).path("ID").asText();
    var input = new LinkedHashMap<String, Object>();
    input.put("farmId", farm); input.put("plotId", plot); input.put("name", "虚构土壤设备"); input.put("code", "T-" + UUID.randomUUID());
    input.put("deviceType", "SOIL"); input.put("protocol", protocol); input.put("lifecycle", "ACTIVE");
    input.put("model", "测试"); input.put("notes", "虚构验收"); input.put("intervalSeconds", 60); input.put("revision", 0);
    input.put("channels", List.of(Map.of("metric", "SOIL_MOISTURE", "lowerLimit", 20, "upperLimit", 60), Map.of("metric", "TEMPERATURE", "upperLimit", 40)));
    String id = call(admin, "POST", "/assets", input, 200).path("id").asText();
    return Map.of("id", id, "farm", farm);
  }
  String login(String tenant, String user) throws Exception {
    return call(null, "POST", "/auth/login", Map.of("tenantCode", tenant, "username", user, "password", "Test-Only-Password-429!"), 200).path("token").asText();
  }
  JsonNode call(String token, String method, String path, Object body, int expected, String... headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path)).header("Content-Type", "application/json").timeout(Duration.ofSeconds(30));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), method + " " + path + " " + response.body());
    return json.readTree(response.body());
  }
}
