package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.bootstrap.ControlDemoData;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:control-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=true", "farm.bootstrap-password=Test-Only-Password-429!"
})
class FarmControlIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired ControlDemoData fixtures;
  final HttpClient client = HttpClient.newHttpClient();
  String admin, other, operator, viewer, platform;

  @BeforeEach void login() throws Exception {
    admin = login("demo-a", "admin"); other = login("demo-b", "admin");
    operator = login("demo-a", "operator"); viewer = login("demo-a", "viewer"); platform = login("platform", "platform");
  }
  String login(String tenant, String user) throws Exception {
    return call(null, "POST", "/auth/login", Map.of("tenantCode", tenant, "username", user, "password", "Test-Only-Password-429!"), 200).get("token").asText();
  }
  JsonNode call(String token, String method, String path, Object body, int expected, String... headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
      .header("Content-Type", "application/json").timeout(java.time.Duration.ofSeconds(30));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), method + " " + path + " " + response.body());
    return json.readTree(response.body());
  }
  String farm(String token) throws Exception {
    return call(token, "POST", "/farm-workspaces", Map.of("name", "虚构验收-" + UUID.randomUUID(),
      "description", "本地测试", "region", "虚构区域", "farmType", "FIELD"), 200).get("id").asText();
  }
  LinkedHashMap<String, Object> asset(String farm, String type, String protocol) {
    var input = new LinkedHashMap<String, Object>();
    input.put("farmId", farm); input.put("name", "虚构" + type); input.put("code", "TEST-" + UUID.randomUUID());
    input.put("deviceType", type); input.put("protocol", protocol); input.put("lifecycle", "ACTIVE");
    input.put("model", "测试型号"); input.put("notes", "虚构设备"); input.put("intervalSeconds", 60); input.put("revision", 0);
    input.put("channels", MetricCatalog.PRESETS.get(type).stream().map(m -> Map.of("metric", m)).toList());
    input.put("controlEnabled", Set.of("GATE", "PUMP").contains(type) && !protocol.equals("MANUAL"));
    return input;
  }
  String device(String token, String type, String protocol) throws Exception {
    return call(token, "POST", "/assets", asset(farm(token), type, protocol), 200).get("id").asText();
  }
  String key(String id) throws Exception { return call(admin, "POST", "/assets/" + id + "/credentials", null, 200).get("key").asText(); }
  JsonNode read(String id) throws Exception { return call(admin, "GET", "/assets/" + id, null, 200); }
  Map<String, Object> command(String action, Integer value) {
    var input = new LinkedHashMap<String, Object>();
    input.put("requestId", UUID.randomUUID().toString()); input.put("action", action); input.put("value", value); input.put("note", "本地联动验收");
    return input;
  }
  Map<String, Object> reading(String metric, Number value) { return Map.of("metric", metric, "value", value); }
  Map<String, Object> batch(Instant time, List<Map<String, Object>> readings) {
    return Map.of("messageId", UUID.randomUUID().toString(), "measuredAt", time.toString(), "readings", readings);
  }
  void interlocks(String key, String type) throws Exception {
    call(null, "POST", "/ingest/telemetry", batch(Instant.now().minusSeconds(1), List.of(
      reading("REMOTE_ENABLED", 1), reading("FAULT", 0), reading(type.equals("GATE") ? "GATE_OPENING" : "PUMP_RUNNING", 0))), 200, "X-Device-Key", key);
  }
  JsonNode channel(JsonNode asset, String code) {
    for (var channel : asset.get("channels")) if (channel.get("metric").asText().equals(code)) return channel;
    throw new AssertionError("Missing channel " + code);
  }
  LinkedHashMap<String, Object> edit(JsonNode device) {
    var result = new LinkedHashMap<String, Object>();
    for (String field : List.of("farmId", "name", "code", "deviceType", "protocol", "lifecycle", "model", "notes",
        "intervalSeconds", "revision", "plotId", "planX", "planY", "locationMode", "latitude", "longitude", "controlEnabled")) {
      result.put(field, json.convertValue(device.get(field), Object.class));
    }
    var channels = new ArrayList<Map<String, Object>>();
    for (var c : device.get("channels")) {
      var row = new LinkedHashMap<String, Object>();
      for (String field : List.of("metric", "lowerLimit", "upperLimit")) row.put(field, json.convertValue(c.get(field), Object.class));
      channels.add(row);
    }
    result.put("channels", channels);
    return result;
  }

  @Test void virtualScenarioIsAdditiveAndContainsNoCredentials() throws Exception {
    long before = db.queryForObject("SELECT COUNT(*) FROM devices", Long.class);
    fixtures.run(null);
    assertEquals(before, db.queryForObject("SELECT COUNT(*) FROM devices", Long.class));
    var scenarios = db.queryForList("SELECT id FROM farms WHERE name='青禾设备联动演示场'", String.class);
    assertEquals(2, scenarios.size());
    for (String farm : scenarios) {
      String tenantCode = db.queryForObject("SELECT t.code FROM farms f JOIN tenants t ON t.id=f.tenant_id WHERE f.id=?", String.class, farm);
      assertEquals(tenantCode.equals("demo-a") ? 11 : 9,
        db.queryForObject("SELECT COUNT(*) FROM devices WHERE farm_id=?", Integer.class, farm));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM asset_profiles p JOIN devices d ON d.id=p.device_id WHERE d.farm_id=? AND p.credential_hash IS NOT NULL", Integer.class, farm));
    }
    var catalog = call(viewer, "GET", "/assets/catalog", null, 200);
    boolean pump = false, pest = false;
    for (var preset : catalog.get("presets")) {
      if (preset.get("deviceType").asText().equals("PUMP")) pump = preset.get("metrics").size() >= 10;
      if (preset.get("deviceType").asText().equals("PEST")) pest = preset.get("metrics").size() > 0;
    }
    assertTrue(pump && pest);
  }

  @Test void simulatedControlsUseFeedbackAndEnforceRoleAndTenantBoundaries() throws Exception {
    String id = device(admin, "GATE", "SIMULATED");
    var input = command("SET_OPENING", 45);
    call(admin, "POST", "/assets/" + id + "/commands", input, 409); // No fresh feedback yet.
    call(operator, "POST", "/assets/" + id + "/collect", null, 200);
    call(viewer, "POST", "/assets/" + id + "/commands", input, 403);
    call(other, "POST", "/assets/" + id + "/commands", input, 404);
    call(platform, "GET", "/assets/" + id + "/commands", null, 403);
    var result = call(operator, "POST", "/assets/" + id + "/commands", input, 200);
    assertEquals("SUCCEEDED", result.get("status").asText());
    assertEquals(result.get("id"), call(operator, "POST", "/assets/" + id + "/commands", input, 200).get("id"));
    input.put("value", 55);
    call(operator, "POST", "/assets/" + id + "/commands", input, 409);
    assertEquals(45, channel(read(id), "GATE_OPENING").at("/latest/value").asInt());
    call(admin, "POST", "/assets/" + id + "/collect", null, 200);
    assertEquals(45, channel(read(id), "GATE_OPENING").at("/latest/value").asInt());
    call(admin, "POST", "/devices/" + id + "/sample", null, 409);
    call(viewer, "GET", "/assets/" + id + "/commands", null, 200);
    call(other, "GET", "/assets/" + id + "/commands", null, 404);
    call(admin, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 101), 400);
  }

  @Test void pumpParametersRequireAdminAndStopWorksWithoutFreshFeedback() throws Exception {
    String id = device(admin, "PUMP", "SIMULATED");
    call(admin, "POST", "/assets/" + id + "/collect", null, 200);
    call(operator, "POST", "/assets/" + id + "/commands", command("SET_FREQUENCY", 40), 403);
    call(admin, "POST", "/assets/" + id + "/commands", command("SET_FREQUENCY", 40), 200);
    call(operator, "POST", "/assets/" + id + "/commands", command("PUMP_START", null), 200);
    assertEquals(1, channel(read(id), "PUMP_RUNNING").at("/latest/value").asInt());
    assertEquals(40, channel(read(id), "PUMP_FREQUENCY").at("/latest/value").asInt());
    db.update("UPDATE telemetry_readings SET measured_at=DATEADD('HOUR',-2,measured_at) WHERE device_id=?", id);
    call(admin, "POST", "/assets/" + id + "/commands", command("PUMP_START", null), 409);
    call(operator, "POST", "/assets/" + id + "/commands", command("EMERGENCY_STOP", null), 200);
    assertEquals(0, channel(read(id), "PUMP_RUNNING").at("/latest/value").asInt());
    assertEquals(0, channel(read(id), "STANDBY_RUNNING").at("/latest/value").asInt());
  }

  @Test void httpDeliveryAndReceiptsAreDeviceScopedIdempotentAndDoNotInventTelemetry() throws Exception {
    String id = device(admin, "GATE", "HTTP_PUSH"), key = key(id);
    String stranger = device(admin, "GATE", "HTTP_PUSH"), wrongKey = key(stranger);
    interlocks(key, "GATE");
    var created = call(operator, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 70), 200);
    String commandId = created.get("id").asText();
    assertEquals("PENDING", created.get("status").asText());
    var receipt = Map.of("status", "SUCCEEDED", "note", "本地测试网关回执");
    call(null, "POST", "/ingest/commands/" + commandId + "/receipt", receipt, 409, "X-Device-Key", key);
    call(null, "POST", "/ingest/commands/poll", null, 401);
    call(null, "POST", "/ingest/commands/poll", null, 403, "X-Device-Key", key, "X-Tenant-Id", "spoof");
    assertEquals(0, call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", wrongKey).get("commands").size());
    var delivery = call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", key);
    assertEquals(commandId, delivery.at("/commands/0/id").asText());
    assertFalse(delivery.at("/commands/0").has("actor"));
    assertEquals(delivery, call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", key));
    call(null, "POST", "/ingest/commands/" + commandId + "/receipt", receipt, 404, "X-Device-Key", wrongKey);
    call(null, "POST", "/ingest/commands/" + commandId + "/receipt", receipt, 200, "X-Device-Key", key);
    assertTrue(call(null, "POST", "/ingest/commands/" + commandId + "/receipt", receipt, 200, "X-Device-Key", key).get("duplicate").asBoolean());
    call(null, "POST", "/ingest/commands/" + commandId + "/receipt", Map.of("status", "FAILED", "note", "冲突回执"), 409, "X-Device-Key", key);
    assertEquals(0, channel(read(id), "GATE_OPENING").at("/latest/value").asInt(), "回执不代表传感器反馈");
    assertEquals(0, call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", key).get("commands").size());
  }

  @Test void commandsExpireAndRotationDisablingAndCancellationInvalidateQueuedWork() throws Exception {
    String id = device(admin, "GATE", "HTTP_PUSH"), key = key(id);
    interlocks(key, "GATE");
    String first = call(admin, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 10), 200).get("id").asText();
    call(admin, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 20), 409);
    call(operator, "POST", "/assets/" + id + "/commands/" + first + "/cancel", null, 403);
    call(admin, "POST", "/assets/" + id + "/commands/" + first + "/cancel", null, 200);
    String expired = call(admin, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 20), 200).get("id").asText();
    db.update("UPDATE device_commands SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?", expired);
    assertEquals(0, call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", key).get("commands").size());
    assertEquals("EXPIRED", db.queryForObject("SELECT status FROM device_commands WHERE id=?", String.class, expired));
    call(null, "POST", "/ingest/commands/" + expired + "/receipt", Map.of("status", "SUCCEEDED", "note", "迟到回执"), 409, "X-Device-Key", key);
    String rotated = call(admin, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 30), 200).get("id").asText();
    String nextKey = key(id);
    call(null, "POST", "/ingest/commands/poll", null, 401, "X-Device-Key", key);
    assertEquals("CANCELLED", db.queryForObject("SELECT status FROM device_commands WHERE id=?", String.class, rotated));
    call(admin, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 40), 200);
    var input = edit(read(id)); input.put("lifecycle", "DISABLED");
    call(admin, "PUT", "/assets/" + id, input, 200);
    call(null, "POST", "/ingest/commands/poll", null, 401, "X-Device-Key", nextKey);
    input = edit(read(id)); input.put("lifecycle", "ACTIVE"); call(admin, "PUT", "/assets/" + id, input, 200);
    assertEquals(0, call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", nextKey).get("commands").size());
  }

  @Test void explicitUnitsZeroAndReorderedRetriesAreHandledWithoutFakeFreshness() throws Exception {
    String id = device(admin, "WEATHER", "HTTP_PUSH"), key = key(id);
    var old = batch(Instant.now().minusSeconds(7200), List.of(
      Map.of("metric", "AIR_PRESSURE", "value", 101.3, "unit", "kPa"), reading("RAINFALL", 0)));
    call(null, "POST", "/ingest/telemetry", old, 200, "X-Device-Key", key);
    var stored = read(id);
    assertEquals("STALE", stored.get("freshness").asText());
    assertFalse(stored.get("lastReceivedAt").isNull());
    assertEquals(1013, channel(stored, "AIR_PRESSURE").at("/latest/value").asInt());
    assertEquals(0, channel(stored, "RAINFALL").at("/latest/value").asInt());
    var reordered = new LinkedHashMap<>(old);
    reordered.put("readings", List.of(reading("RAINFALL", 0.0), reading("AIR_PRESSURE", 1013.0)));
    assertTrue(call(null, "POST", "/ingest/telemetry", reordered, 200, "X-Device-Key", key).get("duplicate").asBoolean());
    call(null, "POST", "/ingest/telemetry", batch(Instant.now().minusSeconds(1), List.of(reading("TEMPERATURE", 22))), 200, "X-Device-Key", key);
    assertEquals("FRESH", read(id).get("freshness").asText());
    assertEquals("STALE", channel(read(id), "AIR_PRESSURE").get("freshness").asText());
    assertEquals("NO_DATA", channel(read(id), "HUMIDITY").get("freshness").asText());
    call(null, "POST", "/ingest/telemetry", batch(Instant.now().minusSeconds(1), List.of(Map.of("metric", "AIR_PRESSURE", "value", 100, "unit", "unknown"))), 400, "X-Device-Key", key);
    var missing = new LinkedHashMap<String, Object>(); missing.put("metric", "TEMPERATURE"); missing.put("value", null);
    call(null, "POST", "/ingest/telemetry", batch(Instant.now().minusSeconds(1), List.of(missing)), 400, "X-Device-Key", key);
    var legacyBatch = batch(Instant.now().minusSeconds(1), List.of(reading("TEMPERATURE", 23)));
    call(null, "POST", "/ingest/telemetry", legacyBatch, 200, "X-Device-Key", key);
    db.update("UPDATE ingestion_batches SET payload_hash=? WHERE device_id=? AND message_id=?",
      app.zhinong.workspace.TelemetryService.hash(legacyBatch.get("measuredAt") + "|[Reading[metric=TEMPERATURE, value=23]]"),
      id, legacyBatch.get("messageId"));
    assertTrue(call(null, "POST", "/ingest/telemetry", legacyBatch, 200, "X-Device-Key", key).get("duplicate").asBoolean());
  }

  @Test void unsafeFeedbackAndFractionalSwitchesAreRejected() throws Exception {
    String id = device(admin, "PUMP", "HTTP_PUSH"), key = key(id);
    interlocks(key, "PUMP");
    call(null, "POST", "/ingest/telemetry", batch(Instant.now().minusMillis(100), List.of(reading("PUMP_RUNNING", 0.5))), 400, "X-Device-Key", key);
    call(null, "POST", "/ingest/telemetry", batch(Instant.now().minusMillis(50), List.of(reading("FAULT", 1))), 200, "X-Device-Key", key);
    call(operator, "POST", "/assets/" + id + "/commands", command("PUMP_START", null), 409);
    call(operator, "POST", "/assets/" + id + "/commands", command("EMERGENCY_STOP", null), 200);
  }

  @Test void geographicPointsSurviveCalibrationAndMapChangesInvalidateOldLayouts() throws Exception {
    String farm = farm(admin);
    var input = asset(farm, "SOIL", "SIMULATED");
    input.put("locationMode", "WGS84"); input.put("latitude", 47.2604); input.put("longitude", 132.7303);
    String id = call(admin, "POST", "/assets", input, 200).get("id").asText();
    var oldLayout = Map.of("revision", 1, "shapes", List.of(), "positions", List.of());
    var config = Map.of("mode", "PLAN", "latitude", 47.265, "longitude", 132.735, "widthMeters", 2000,
      "heightMeters", 1600, "locationLabel", "虚构校准位置", "revision", 0);
    call(admin, "PUT", "/farms/" + farm + "/map-config", config, 200);
    assertEquals(47.2604, read(id).get("latitude").asDouble());
    assertTrue(read(id).get("positioned").asBoolean());
    assertTrue(read(id).get("planX").isNull());
    call(admin, "PUT", "/farms/" + farm + "/layout", oldLayout, 409);
    var position = Map.of("deviceId", id, "locationMode", "WGS84", "latitude", 47.261, "longitude", 132.731);
    var layout = Map.of("revision", 2, "shapes", List.of(), "positions", List.of(position));
    call(other, "PUT", "/farms/" + farm + "/layout", layout, 404);
    call(viewer, "PUT", "/farms/" + farm + "/layout", layout, 403);
    call(admin, "PUT", "/farms/" + farm + "/layout", layout, 200);
    assertEquals(47.261, read(id).get("latitude").asDouble());
    var malformed = new LinkedHashMap<>(input); malformed.put("latitude", 91); malformed.put("code", "BAD-" + UUID.randomUUID());
    call(admin, "POST", "/assets", malformed, 400);
    var clear = Map.of("revision", 3, "shapes", List.of(), "positions", List.of(Map.of("deviceId", id, "locationMode", "LOCAL_PLAN")));
    call(admin, "PUT", "/farms/" + farm + "/layout", clear, 200);
    assertFalse(read(id).get("positioned").asBoolean());
    call(admin, "POST", "/assets/" + id + "/collect", null, 200);
    assertFalse(channel(read(id), "SOIL_MOISTURE").get("latest").isNull());
  }

  @Test void tenantSuspensionInvalidatesCommandsEvenAfterReactivation() throws Exception {
    String id = device(admin, "PUMP", "HTTP_PUSH"), key = key(id);
    interlocks(key, "PUMP");
    String commandId = call(admin, "POST", "/assets/" + id + "/commands", command("PUMP_START", null), 200).get("id").asText();
    String tenant = db.queryForObject("SELECT tenant_id FROM devices WHERE id=?", String.class, id);
    call(platform, "PATCH", "/platform/tenants/" + tenant, Map.of("enabled", false), 200);
    try {
      call(null, "POST", "/ingest/commands/poll", null, 401, "X-Device-Key", key);
      assertEquals("CANCELLED", db.queryForObject("SELECT status FROM device_commands WHERE id=?", String.class, commandId));
    } finally {
      call(platform, "PATCH", "/platform/tenants/" + tenant, Map.of("enabled", true), 200);
    }
    assertEquals(0, call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", key).get("commands").size());
  }

  @Test void concurrentRetriesProduceOneCommandAndDisabledControlIsEnforced() throws Exception {
    String id = device(admin, "GATE", "HTTP_PUSH"), key = key(id);
    interlocks(key, "GATE");
    var input = command("SET_OPENING", 40);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> call(admin, "POST", "/assets/" + id + "/commands", input, 200));
      var second = workers.submit(() -> call(admin, "POST", "/assets/" + id + "/commands", input, 200));
      assertEquals(first.get(20, java.util.concurrent.TimeUnit.SECONDS).get("id"), second.get(20, java.util.concurrent.TimeUnit.SECONDS).get("id"));
    }
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM device_commands WHERE device_id=?", Integer.class, id));
    var update = edit(read(id)); update.put("controlEnabled", false);
    call(admin, "PUT", "/assets/" + id, update, 200);
    call(admin, "POST", "/assets/" + id + "/commands", command("SET_OPENING", 50), 409);
    assertEquals(0, call(null, "POST", "/ingest/commands/poll", null, 200, "X-Device-Key", key).get("commands").size());
    var invalid = asset(farm(admin), "PUMP", "SIMULATED");
    invalid.put("channels", List.of(Map.of("metric", "FLOW")));
    call(admin, "POST", "/assets", invalid, 400);
  }

  @Test void schemaCanBeReappliedWithoutLosingDeviceData() throws Exception {
    String id = device(admin, "SOIL", "SIMULATED");
    call(admin, "POST", "/assets/" + id + "/collect", null, 200);
    var previous = channel(read(id), "SOIL_MOISTURE").get("latest");
    var schema = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
      new org.springframework.core.io.ClassPathResource("schema.sql"));
    schema.execute(Objects.requireNonNull(db.getDataSource()));
    assertEquals(previous, channel(read(id), "SOIL_MOISTURE").get("latest"));
  }
}
