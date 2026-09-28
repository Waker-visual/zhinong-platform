package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.simulation.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:account-sim-tests;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=true",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
class AccountSimulationIntegrationTest {

  static final String PASSWORD = "Test-Only-Password-429!",
    NEXT = "New-Individual-Password-491!";

  @LocalServerPort
  int port;

  @Autowired
  ObjectMapper json;

  @Autowired
  SimulationEngine engine;

  final HttpClient http = HttpClient.newHttpClient();
  String admin, other, viewer;

  @BeforeEach
  void roles() throws Exception {
    admin = login("demo-a", "admin", PASSWORD);
    other = login("demo-b", "admin", PASSWORD);
    viewer = login("demo-a", "viewer", PASSWORD);
  }

  HttpResponse<String> request(
    String token,
    String method,
    String path,
    Object body
  ) throws Exception {
    var b = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api" + path)
    ).header("Content-Type", "application/json");
    if (token != null) b.header("Authorization", "Bearer " + token);
    return http.send(
      b
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  JsonNode call(
    String token,
    String method,
    String path,
    Object body,
    int expected
  ) throws Exception {
    var r = request(token, method, path, body);
    assertEquals(expected, r.statusCode(), r.body());
    return json.readTree(r.body());
  }

  String login(String tenant, String user, String password) throws Exception {
    return call(
      null,
      "POST",
      "/auth/login",
      Map.of("tenantCode", tenant, "username", user, "password", password),
      200
    )
      .path("token")
      .asText();
  }

  JsonNode member(String token, String role) throws Exception {
    return call(
      token,
      "POST",
      "/members",
      Map.of(
        "username",
        "user-" + UUID.randomUUID().toString().substring(0, 8),
        "displayName",
        "验收成员",
        "role",
        role,
        "password",
        PASSWORD
      ),
      200
    );
  }

  String farm(String token) throws Exception {
    return call(token, "GET", "/farms", null, 200).get(0).path("ID").asText();
  }

  @Test
  void profileAndThemeArePrivateAndValidated() throws Exception {
    var m = member(admin, "OPERATOR");
    String token = login("demo-a", m.path("username").asText(), PASSWORD);
    var profile = call(
      token,
      "PUT",
      "/account/profile",
      Map.of("displayName", "田间值班员", "avatarData", ""),
      200
    );
    assertEquals("田间值班员", profile.path("displayName").asText());
    assertFalse(profile.has("passwordHash"));
    call(
      token,
      "PUT",
      "/account/profile",
      Map.of("displayName", "X", "avatarData", "https://example.com/a.png"),
      400
    );
    call(
      token,
      "PUT",
      "/account/profile",
      Map.of(
        "displayName",
        "X",
        "avatarData",
        "data:image/svg+xml;base64,PHN2Zy8+"
      ),
      400
    );
    call(
      token,
      "PUT",
      "/account/appearance",
      Map.of("themeMode", "DARK", "accent", "BLUE"),
      200
    );
    assertEquals(
      "DARK",
      call(token, "GET", "/account", null, 200).path("themeMode").asText()
    );
    assertEquals(
      "SYSTEM",
      call(other, "GET", "/account", null, 200).path("themeMode").asText()
    );
    call(
      token,
      "PUT",
      "/account/appearance",
      Map.of("themeMode", "HACK", "accent", "BLUE"),
      400
    );
    String platform = login("platform", "platform", PASSWORD);
    call(platform, "GET", "/account", null, 200);
    call(platform, "GET", "/simulations/catalog", null, 403);
  }

  @Test
  void ownPasswordChangeRevokesEverySession() throws Exception {
    var m = member(admin, "VIEWER");
    String user = m.path("username").asText(),
      one = login("demo-a", user, PASSWORD),
      two = login("demo-a", user, PASSWORD);
    call(
      one,
      "POST",
      "/account/password",
      Map.of("currentPassword", "incorrect-old-password", "newPassword", NEXT),
      400
    );
    call(
      one,
      "POST",
      "/account/password",
      Map.of("currentPassword", PASSWORD, "newPassword", "密".repeat(30)),
      400
    );
    call(
      one,
      "POST",
      "/account/password",
      Map.of("currentPassword", PASSWORD, "newPassword", NEXT),
      200
    );
    call(one, "GET", "/account", null, 401);
    call(two, "GET", "/account", null, 401);
    call(
      null,
      "POST",
      "/auth/login",
      Map.of("tenantCode", "demo-a", "username", user, "password", PASSWORD),
      401
    );
    call(login("demo-a", user, NEXT), "GET", "/farms", null, 200);
  }

  @Test
  void adminResetEnforcesScopeAndMandatoryChange() throws Exception {
    var m = member(admin, "OPERATOR");
    String id = m.path("id").asText(),
      user = m.path("username").asText(),
      old = login("demo-a", user, PASSWORD),
      path = "/members/" + id + "/reset-password";
    var body = Map.of("currentPassword", PASSWORD, "newPassword", NEXT);
    call(viewer, "POST", path, body, 403);
    call(other, "POST", path, body, 404);
    call(
      admin,
      "POST",
      path,
      Map.of("currentPassword", "incorrect-old-password", "newPassword", NEXT),
      400
    );
    String adminId = call(admin, "GET", "/account", null, 200)
      .path("id")
      .asText();
    call(admin, "POST", "/members/" + adminId + "/reset-password", body, 400);
    String anotherAdmin = member(admin, "ADMIN").path("id").asText();
    call(
      admin,
      "POST",
      "/members/" + anotherAdmin + "/reset-password",
      body,
      403
    );
    call(admin, "POST", path, body, 200);
    call(old, "GET", "/farms", null, 401);
    String reset = login("demo-a", user, NEXT);
    assertTrue(
      call(reset, "GET", "/account", null, 200)
        .path("mustChangePassword")
        .asBoolean()
    );
    call(reset, "GET", "/farms", null, 403);
    call(
      reset,
      "POST",
      "/account/password",
      Map.of("currentPassword", NEXT, "newPassword", PASSWORD),
      200
    );
    String restored = login("demo-a", user, PASSWORD);
    call(restored, "GET", "/farms", null, 200);
    assertFalse(
      call(restored, "GET", "/account", null, 200)
        .path("mustChangePassword")
        .asBoolean()
    );
  }

  @Test
  void farmFilteringAndMapRevisionAreTenantScoped() throws Exception {
    String f = farm(admin),
      foreign = farm(other);
    for (String resource : List.of(
      "plots",
      "plantings",
      "tasks",
      "production"
    )) {
      call(admin, "GET", "/" + resource + "?farmId=" + foreign, null, 404);
      call(admin, "GET", "/" + resource + "?farmId=" + f, null, 200);
    }
    for (var plot : call(admin, "GET", "/plots?farmId=" + f, null, 200))
      assertEquals(f, plot.path("FARM_ID").asText());
    String path = "/farms/" + f + "/map-config";
    int revision = call(admin, "GET", path, null, 200).path("revision").asInt();
    var body = new LinkedHashMap<String, Object>(
      Map.of(
        "mode",
        "SATELLITE",
        "latitude",
        47.24,
        "longitude",
        132.65,
        "widthMeters",
        1600,
        "heightMeters",
        1120,
        "locationLabel",
        "公开演示位置",
        "revision",
        revision
      )
    );
    call(viewer, "PUT", path, body, 403);
    call(other, "PUT", path, body, 404);
    call(admin, "PUT", path, body, 200);
    call(admin, "PUT", path, body, 409);
    body.put("revision", revision + 1);
    body.put("latitude", 90);
    call(admin, "PUT", path, body, 400);
  }

  SimulationInput input(String farm, int drones, double manual) {
    return new SimulationInput(
      farm,
      "验收对照",
      LocalDate.of(2025, 6, 1),
      90,
      40,
      drones,
      120,
      manual,
      1,
      3,
      10,
      80,
      0.8,
      1000,
      1,
      Map.of()
    );
  }

  @Test
  void simulationsPersistAndCannotCrossTenants() throws Exception {
    String f = farm(admin);
    var input = input(f, 1, 1000);
    call(viewer, "POST", "/simulations", input, 403);
    call(other, "POST", "/simulations", input, 404);
    var created = call(admin, "POST", "/simulations", input, 200);
    String id = created.path("id").asText();
    var stored = call(viewer, "GET", "/simulations/" + id, null, 200);
    assertEquals(created, stored);
    assertEquals(3, stored.path("result").path("scenarios").size());
    call(other, "GET", "/simulations/" + id, null, 404);
    for (var r : call(other, "GET", "/simulations", null, 200))
      assertNotEquals(id, r.path("id").asText());
    assertTrue(
      call(admin, "GET", "/simulations/catalog", null, 200)
          .path("storage")
          .path("weatherRows")
          .asInt() >=
        365
    );
  }

  @Test
  void deterministicModelExplainsMitigationAndNoDroneIsNotAutomaticLoss()
    throws Exception {
    var plots = List.of(
      new SimulationEngine.Plot("test", "测试玉米田", "玉米", 100, "MAIZE")
    );
    var in = input("farm", 1, 1000);
    JsonNode result = json.valueToTree(engine.run(in, plots));
    assertEquals(result, json.valueToTree(engine.run(in, plots)));
    var s = result.path("scenarios");
    assertTrue(
      s.get(1).path("summary").path("pestLossKg").asDouble() >
        s.get(2).path("summary").path("pestLossKg").asDouble()
    );
    assertEquals(
      0,
      s.get(2).path("plots").get(0).path("untreatedMu").asDouble()
    );
    var noDrone = json.valueToTree(engine.run(input("farm", 0, 1000), plots));
    assertTrue(
      noDrone
          .path("scenarios")
          .get(2)
          .path("summary")
          .path("harvestKg")
          .asDouble() >
        noDrone
          .path("scenarios")
          .get(0)
          .path("summary")
          .path("harvestKg")
          .asDouble()
    );
    var noResources = json.valueToTree(engine.run(input("farm", 0, 0), plots));
    assertEquals(
      noResources.path("scenarios").get(0).path("summary"),
      noResources.path("scenarios").get(2).path("summary")
    );
  }

  @Test
  void annualUsesOneCropCycleAndRejectsWeatherOutsideDataset()
    throws Exception {
    String f = farm(admin);
    var annual = new SimulationInput(
      f,
      "年度",
      LocalDate.of(2025, 1, 1),
      365,
      0,
      1,
      120,
      1000,
      7,
      3,
      180,
      45,
      0.8,
      1000,
      1,
      Map.of()
    );
    var result = call(admin, "POST", "/simulations", annual, 200);
    var days = result.path("result").path("scenarios").get(0).path("days");
    assertEquals(365, days.size());
    var invalid = json.valueToTree(input(f, 1, 50));
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put(
      "startDate",
      "2026-06-01"
    );
    call(admin, "POST", "/simulations", invalid, 400);
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put(
      "startDate",
      "2025-06-01"
    ).put("days", 91);
    call(admin, "POST", "/simulations", invalid, 400);
  }

  @Test
  void mapTileGatewayRequiresSessionAndTenantRole() throws Exception {
    call(null, "GET", "/map-tiles/STREET/0/2/0", null, 401);
    call(viewer, "GET", "/map-tiles/STREET/0/2/0", null, 400);
    call(
      login("platform", "platform", PASSWORD),
      "GET",
      "/map-tiles/STREET/0/0/0",
      null,
      403
    );
  }
}
