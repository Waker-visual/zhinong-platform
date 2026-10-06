package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.bootstrap.WorkspaceData;
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

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:workspace-tests;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=true",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
class WorkspaceIntegrationTest {

  @LocalServerPort
  int port;

  @Autowired
  ObjectMapper json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  WorkspaceData fixtures;

  final HttpClient client = HttpClient.newHttpClient();
  String a, b, viewer, operator, platform;

  @BeforeEach
  void loginRoles() throws Exception {
    a = login("demo-a", "admin");
    b = login("demo-b", "admin");
    viewer = login("demo-a", "viewer");
    operator = login("demo-a", "operator");
    platform = login("platform", "platform");
  }

  HttpResponse<String> request(
    String token,
    String method,
    String path,
    Object body,
    String... headers
  ) throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api" + path)
    ).timeout(java.time.Duration.ofSeconds(30));
    builder.header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    builder.method(
      method,
      body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
    );
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode data(HttpResponse<String> response, int status) throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  String login(String tenant, String username) throws Exception {
    return data(
      request(
        null,
        "POST",
        "/auth/login",
        Map.of("tenantCode", tenant, "username", username, "password", "Test-Only-Password-429!")
      ),
      200
    )
      .get("token")
      .asText();
  }

  String farm(String token) throws Exception {
    return data(
      request(
        token,
        "POST",
        "/farm-workspaces",
        Map.of(
          "name",
          "测试农场-" + UUID.randomUUID(),
          "description",
          "独立测试记录",
          "region",
          "测试区域",
          "farmType",
          "FIELD"
        )
      ),
      200
    )
      .get("id")
      .asText();
  }

  String plot(String token, String farm) throws Exception {
    return data(
      request(
        token,
        "POST",
        "/plots",
        Map.of("farmId", farm, "name", "测试地块", "areaMu", 25, "crop", "水稻")
      ),
      200
    )
      .get("ID")
      .asText();
  }

  LinkedHashMap<String, Object> asset(String farm, String plot, String protocol) {
    var body = new LinkedHashMap<String, Object>();
    body.put("farmId", farm);
    body.put("name", "多指标测试设备");
    body.put("code", "T-" + UUID.randomUUID());
    body.put("deviceType", "SOIL");
    body.put("protocol", protocol);
    body.put("lifecycle", "ACTIVE");
    body.put("plotId", plot);
    body.put("planX", 150);
    body.put("planY", 160);
    body.put("model", "通用测试型号");
    body.put("notes", "测试记录");
    body.put("intervalSeconds", 900);
    body.put("revision", 0);
    body.put(
      "channels",
      List.of(
        Map.of("metric", "SOIL_MOISTURE", "lowerLimit", 20, "upperLimit", 60),
        Map.of("metric", "TEMPERATURE", "upperLimit", 40)
      )
    );
    return body;
  }

  Map<String, Object> measurement(double value, Instant at) {
    return Map.of(
      "measuredAt",
      at.toString(),
      "readings",
      List.of(Map.of("metric", "SOIL_MOISTURE", "value", value))
    );
  }

  @Test
  void richFixtureHasSavedGeometryDetailedHistoryAndDatabaseDerivedCharts() throws Exception {
    var farms = data(request(a, "GET", "/farm-workspaces", null), 200);
    JsonNode rich = null;
    for (var f : farms)
      if (f.get("plotCount").asInt() == 6 && f.get("demo").asBoolean()) {
        rich = f;
        break;
      }
    assertNotNull(rich);
    var workspace = data(
      request(a, "GET", "/farms/" + rich.get("id").asText() + "/workspace?days=180", null),
      200
    );
    assertEquals(6, workspace.get("plots").size());
    assertEquals(8, workspace.get("devices").size());
    assertTrue(workspace.get("plots").get(0).get("boundary").size() >= 4);
    assertEquals(
      rich.get("areaMu").decimalValue(),
      workspace.at("/analytics/summary/areaMu").decimalValue()
    );
    assertTrue(workspace.at("/analytics/productionTrend").size() > 2);
    var device = workspace.get("devices").get(0);
    var metric = device.get("channels").get(0).get("metric").asText();
    var history = data(
      request(
        a,
        "GET",
        "/assets/" + device.get("id").asText() + "/history?metric=" + metric + "&hours=720",
        null
      ),
      200
    );
    assertTrue(history.get("points").size() > 200);
    assertEquals("SIMULATED", history.get("sources").get(0).get("source").asText());
  }

  @Test
  void operationsOverviewSlicesReportingMoistureAndWorkWithinTenant() throws Exception {
    String rich = null;
    for (var f : data(request(a, "GET", "/farm-workspaces", null), 200))
      if (f.get("plotCount").asInt() == 6 && f.get("demo").asBoolean()) {
        rich = f.get("id").asText();
        break;
      }
    assertNotNull(rich);
    var overview = data(
      request(viewer, "GET", "/farms/" + rich + "/operations?hours=168", null),
      200
    );
    assertEquals(100, overview.get("slices").size());
    int observed = 0;
    boolean moisture = false;
    for (var slice : overview.get("slices")) {
      if (!slice.get("health").isNull()) {
        observed++;
        int health = slice.get("health").asInt();
        assertTrue(health >= 0 && health <= 100, slice.toString());
      }
      if (!slice.get("moisture").isNull()) moisture = true;
    }
    assertTrue(observed > 50);
    assertTrue(moisture);
    assertEquals(28, overview.at("/matrix/days").size());
    assertEquals(6, overview.at("/matrix/plots").size());
    boolean belowLimit = false;
    int monitored = 0;
    for (var plot : overview.at("/matrix/plots")) {
      assertEquals(28, plot.get("cells").size());
      if (plot.get("lowDays").asInt() > 0) belowLimit = true;
      if (plot.get("sensors").asInt() > 0) monitored++;
      // 没有土壤水分设备的地块不会出现读数
      else for (var cell : plot.get("cells")) assertEquals("NONE", cell.asText());
    }
    assertTrue(monitored > 0 && monitored < 6);
    // 演示设备 03 最近几小时的土壤水分低于它配置的下限 20
    assertTrue(belowLimit);
    int active = overview.at("/summary/activeDevices").asInt();
    assertTrue(active > 0 && active < 8, "维护中的设备不计入");
    assertTrue(overview.at("/summary/freshDevices").asInt() <= active);
    assertTrue(overview.at("/pipeline/protection/open").isNumber());
    data(request(b, "GET", "/farms/" + rich + "/operations", null), 404);
    data(request(platform, "GET", "/farms/" + rich + "/operations", null), 403);
    data(request(a, "GET", "/farms/" + rich + "/operations?hours=5", null), 400);
  }

  @Test
  void crossTenantResourcesAndCrossFarmAssociationsAreRejected() throws Exception {
    String fa = farm(a),
      pa = plot(a, fa),
      fb = farm(b),
      pb = plot(b, fb);
    var created = data(request(a, "POST", "/assets", asset(fa, pa, "SIMULATED")), 200);
    String id = created.get("id").asText();
    data(request(b, "GET", "/farms/" + fa + "/workspace", null), 404);
    data(request(b, "GET", "/assets/" + id, null), 404);
    data(request(b, "GET", "/assets/" + id + "/history?metric=SOIL_MOISTURE", null), 404);
    data(request(a, "POST", "/assets", asset(fa, pb, "SIMULATED")), 404);
    String anotherFarm = farm(a),
      otherPlot = plot(a, anotherFarm);
    data(request(a, "POST", "/assets", asset(fa, otherPlot, "SIMULATED")), 400);
    data(
      request(
        b,
        "PUT",
        "/farms/" + fb + "/layout",
        Map.of(
          "revision",
          0,
          "shapes",
          List.of(Map.of("plotId", pa, "boundary", List.of())),
          "positions",
          List.of()
        )
      ),
      404
    );
    data(request(b, "POST", "/assets/" + id + "/collect", null), 404);
  }

  @Test
  void mapEditsPersistAndRejectStaleVersionsInvalidPolygonsAndOutOfBoundsPoints() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm);
    String id = data(request(a, "POST", "/assets", asset(farm, plot, "SIMULATED")), 200)
      .get("id")
      .asText();
    int revision = data(request(a, "GET", "/farms/" + farm + "/workspace", null), 200)
      .at("/farm/layoutRevision")
      .asInt();
    var body = Map.of(
      "revision",
      revision,
      "shapes",
      List.of(
        Map.of(
          "plotId",
          plot,
          "boundary",
          List.of(List.of(100, 100), List.of(400, 100), List.of(400, 350), List.of(100, 350))
        )
      ),
      "positions",
      List.of(Map.of("deviceId", id, "x", 210, "y", 220))
    );
    data(request(a, "PUT", "/farms/" + farm + "/layout", body), 200);
    var read = data(request(a, "GET", "/farms/" + farm + "/workspace", null), 200);
    assertEquals(4, read.get("plots").get(0).get("boundary").size());
    assertEquals(210, read.get("devices").get(0).get("planX").intValue());
    data(request(a, "PUT", "/farms/" + farm + "/layout", body), 409);
    var invalid = Map.of(
      "revision",
      revision + 1,
      "shapes",
      List.of(
        Map.of(
          "plotId",
          plot,
          "boundary",
          List.of(List.of(100, 100), List.of(400, 400), List.of(100, 400), List.of(400, 100))
        )
      ),
      "positions",
      List.of()
    );
    data(request(a, "PUT", "/farms/" + farm + "/layout", invalid), 400);
    data(
      request(
        a,
        "PUT",
        "/farms/" + farm + "/layout",
        Map.of(
          "revision",
          revision + 1,
          "shapes",
          List.of(),
          "positions",
          List.of(Map.of("deviceId", id, "x", 1001, "y", 20))
        )
      ),
      400
    );
    assertEquals(
      revision + 1,
      data(request(a, "GET", "/farms/" + farm + "/workspace", null), 200)
        .at("/farm/layoutRevision")
        .asInt()
    );
  }

  @Test
  void rolesProtectGeometryConfigurationCredentialsAndManualWrites() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm);
    var input = asset(farm, plot, "HTTP_PUSH");
    String id = data(request(a, "POST", "/assets", input), 200).get("id").asText();
    data(request(viewer, "POST", "/assets", input), 403);
    data(request(operator, "POST", "/assets", input), 403);
    data(
      request(
        viewer,
        "PUT",
        "/farms/" + farm + "/layout",
        Map.of("revision", 0, "shapes", List.of(), "positions", List.of())
      ),
      403
    );
    data(request(viewer, "POST", "/assets/" + id + "/credentials", null), 403);
    data(request(operator, "POST", "/assets/" + id + "/credentials", null), 403);
    data(request(platform, "GET", "/farm-workspaces", null), 403);
    data(request(platform, "GET", "/assets", null), 403);
    data(request(viewer, "GET", "/assets/" + id, null), 200);
  }

  @Test
  void deviceConfigurationChecksThresholdsDuplicateCodesAndRevision() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm);
    var input = asset(farm, plot, "SIMULATED");
    var created = data(request(a, "POST", "/assets", input), 200);
    String id = created.get("id").asText();
    data(request(a, "POST", "/assets", input), 409);
    input.put("revision", created.get("revision").intValue());
    input.put("notes", "更新安装说明");
    data(request(a, "PUT", "/assets/" + id, input), 200);
    data(request(a, "PUT", "/assets/" + id, input), 409);
    var bad = asset(farm, plot, "SIMULATED");
    bad.put(
      "channels",
      List.of(Map.of("metric", "SOIL_MOISTURE", "lowerLimit", 60, "upperLimit", 20))
    );
    data(request(a, "POST", "/assets", bad), 400);
    bad = asset(farm, plot, "SIMULATED");
    bad.put("planY", null);
    data(request(a, "POST", "/assets", bad), 400);
    bad = asset(farm, plot, "SIMULATED");
    bad.put("tenantId", "spoof");
    data(request(a, "POST", "/assets", bad), 400);
  }

  @Test
  void multiMetricSamplingAndDisableAreEnforcedByNewAndLegacyRoutes() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm);
    var input = asset(farm, plot, "SIMULATED");
    var created = data(request(a, "POST", "/assets", input), 200);
    String id = created.get("id").asText();
    var sampled = data(request(operator, "POST", "/assets/" + id + "/collect", null), 200);
    assertEquals(2, sampled.get("channels").size());
    for (var channel : sampled.get("channels"))
      assertEquals("SIMULATED", channel.at("/latest/source").asText());
    data(request(viewer, "POST", "/assets/" + id + "/collect", null), 403);
    input.put("revision", sampled.get("revision").intValue());
    input.put("lifecycle", "DISABLED");
    data(request(a, "PUT", "/assets/" + id, input), 200);
    data(request(a, "POST", "/assets/" + id + "/collect", null), 409);
    data(request(a, "POST", "/devices/" + id + "/sample", null), 409);
  }

  @Test
  void alertsSupportAcknowledgementRecoveryAndIgnoreLateHistoricalData() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm);
    String id = data(request(a, "POST", "/assets", asset(farm, plot, "MANUAL")), 200)
      .get("id")
      .asText();
    data(
      request(
        operator,
        "POST",
        "/assets/" + id + "/readings",
        measurement(10, Instant.now().minusSeconds(10))
      ),
      200
    );
    var alerts = data(request(a, "GET", "/assets/" + id, null), 200).get("alerts");
    assertEquals(1, alerts.size());
    String alert = alerts.get(0).get("id").asText();
    data(
      request(
        viewer,
        "PATCH",
        "/alerts/" + alert,
        Map.of("status", "ACKNOWLEDGED", "note", "测试")
      ),
      403
    );
    data(
      request(
        operator,
        "PATCH",
        "/alerts/" + alert,
        Map.of("status", "ACKNOWLEDGED", "note", "已安排巡查")
      ),
      200
    );
    data(
      request(
        operator,
        "POST",
        "/assets/" + id + "/readings",
        measurement(35, Instant.now().minusSeconds(3600))
      ),
      200
    );
    assertEquals(
      "ACKNOWLEDGED",
      data(request(a, "GET", "/assets/" + id, null), 200)
        .get("alerts")
        .get(0)
        .get("status")
        .asText()
    );
    data(
      request(
        operator,
        "POST",
        "/assets/" + id + "/readings",
        measurement(35, Instant.now().minusSeconds(1))
      ),
      200
    );
    assertEquals(
      "RESOLVED",
      data(request(a, "GET", "/assets/" + id, null), 200)
        .get("alerts")
        .get(0)
        .get("status")
        .asText()
    );
    long count = db.queryForObject(
      "SELECT COUNT(*) FROM telemetry_readings WHERE device_id=?",
      Long.class,
      id
    );
    data(
      request(
        a,
        "POST",
        "/assets/" + id + "/readings",
        Map.of(
          "measuredAt",
          Instant.now().minusSeconds(1).toString(),
          "readings",
          List.of(
            Map.of("metric", "TEMPERATURE", "value", 20),
            Map.of("metric", "SOIL_MOISTURE", "value", 101)
          )
        )
      ),
      400
    );
    assertEquals(
      count,
      db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE device_id=?", Long.class, id)
    );
  }

  @Test
  void httpIngestionUsesBoundCredentialsDeduplicatesAndSupportsRotation() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm);
    String id = data(request(a, "POST", "/assets", asset(farm, plot, "HTTP_PUSH")), 200)
      .get("id")
      .asText();
    String key = data(request(a, "POST", "/assets/" + id + "/credentials", null), 200)
      .get("key")
      .asText();
    assertFalse(request(a, "GET", "/assets/" + id, null).body().contains(key));
    assertFalse(request(a, "GET", "/assets", null).body().contains("credential_hash"));
    var body = new LinkedHashMap<String, Object>(measurement(40, Instant.now().minusSeconds(1)));
    body.put("messageId", "test-001");
    data(request(null, "POST", "/ingest/telemetry", body), 401);
    var first = data(request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", key), 200);
    assertFalse(first.get("duplicate").asBoolean());
    assertTrue(
      data(request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", key), 200)
        .get("duplicate")
        .asBoolean()
    );
    assertEquals(
      1,
      db.queryForObject(
        "SELECT COUNT(*) FROM telemetry_readings WHERE device_id=?",
        Integer.class,
        id
      )
    );
    data(
      request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", key, "X-Tenant-Id", "other"),
      403
    );
    body.put("readings", List.of(Map.of("metric", "SOIL_MOISTURE", "value", 41)));
    data(request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", key), 409);
    String next = data(request(a, "POST", "/assets/" + id + "/credentials", null), 200)
      .get("key")
      .asText();
    assertNotEquals(key, next);
    body.put("messageId", "test-002");
    data(request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", key), 401);
    data(request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", next), 200);
    data(
      request(
        a,
        "POST",
        "/devices/" + id + "/observations",
        Map.of("value", 20, "measuredAt", java.time.LocalDateTime.now().minusSeconds(1).toString())
      ),
      409
    );
    body.put("tenantId", "spoof");
    data(request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", next), 400);
  }

  @Test
  void stoppedTenantRejectsDeviceKeysAndRestoresWithoutChangingData() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm);
    String id = data(request(a, "POST", "/assets", asset(farm, plot, "HTTP_PUSH")), 200)
      .get("id")
      .asText();
    String key = data(request(a, "POST", "/assets/" + id + "/credentials", null), 200)
      .get("key")
      .asText();
    var body = new LinkedHashMap<String, Object>(measurement(40, Instant.now().minusSeconds(1)));
    body.put("messageId", "stopped");
    db.update("UPDATE tenants SET enabled=FALSE WHERE code='demo-a'");
    try {
      data(request(null, "POST", "/ingest/telemetry", body, "X-Device-Key", key), 401);
    } finally {
      db.update("UPDATE tenants SET enabled=TRUE WHERE code='demo-a'");
    }
    assertEquals(
      0,
      db.queryForObject(
        "SELECT COUNT(*) FROM telemetry_readings WHERE device_id=?",
        Integer.class,
        id
      )
    );
  }

  @Test
  void chartAggregationChangesWithPersistedProductionAndRemainsFarmScoped() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm),
      other = farm(a),
      otherPlot = plot(a, other);
    data(
      request(
        a,
        "POST",
        "/production",
        Map.of(
          "plotId",
          plot,
          "recordDate",
          java.time.LocalDate.now().toString(),
          "yieldKg",
          1234,
          "note",
          "测试产量"
        )
      ),
      200
    );
    data(
      request(
        a,
        "POST",
        "/production",
        Map.of(
          "plotId",
          otherPlot,
          "recordDate",
          java.time.LocalDate.now().toString(),
          "yieldKg",
          9999,
          "note",
          "另一农场"
        )
      ),
      200
    );
    var analytics = data(request(a, "GET", "/farms/" + farm + "/analytics?days=30", null), 200);
    assertEquals(1234, analytics.at("/summary/yieldKg").intValue());
    assertEquals(25, analytics.get("cropArea").get(0).get("value").intValue());
    data(request(a, "GET", "/farms/" + farm + "/analytics?days=0", null), 400);
  }

  @Test
  void linkedPlotsCannotMoveAcrossFarmsAndHistoryRangeIsBounded() throws Exception {
    String farm = farm(a),
      plot = plot(a, farm),
      other = farm(a);
    String id = data(request(a, "POST", "/assets", asset(farm, plot, "MANUAL")), 200)
      .get("id")
      .asText();
    data(
      request(
        a,
        "PUT",
        "/plots/" + plot,
        Map.of("farmId", other, "name", "迁移地块", "areaMu", 25, "crop", "水稻")
      ),
      409
    );
    data(request(a, "GET", "/assets/" + id + "/history?metric=SOIL_MOISTURE&hours=721", null), 400);
    data(request(a, "GET", "/assets/" + id + "/history?metric=PH", null), 400);
  }

  @Test
  void additiveDemoSetupIsIdempotent() {
    long farms = db.queryForObject("SELECT COUNT(*) FROM farms", Long.class),
      readings = db.queryForObject("SELECT COUNT(*) FROM telemetry_readings", Long.class);
    fixtures.run(null);
    assertEquals(farms, db.queryForObject("SELECT COUNT(*) FROM farms", Long.class));
    assertEquals(
      readings,
      db.queryForObject("SELECT COUNT(*) FROM telemetry_readings", Long.class)
    );
  }
}
