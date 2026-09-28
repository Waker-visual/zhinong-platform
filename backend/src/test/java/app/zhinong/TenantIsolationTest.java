package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:tenant-tests;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=false",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
class TenantIsolationTest {

  @LocalServerPort
  int port;

  @Autowired
  ObjectMapper json;

  final HttpClient client = HttpClient.newHttpClient();
  String a, b, operator, viewer, platform;

  @BeforeEach
  void identities() throws Exception {
    a = login("demo-a", "admin");
    b = login("demo-b", "admin");
    operator = login("demo-a", "operator");
    viewer = login("demo-a", "viewer");
    platform = login("platform", "platform");
  }

  HttpResponse<String> request(
    String token,
    String method,
    String path,
    Object body,
    String... extra
  ) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path));
    builder.header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (int i = 0; i < extra.length; i += 2) builder.header(extra[i], extra[i + 1]);
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

  String login(String tenant, String user) throws Exception {
    return data(
      request(
        null,
        "POST",
        "/auth/login",
        Map.of("tenantCode", tenant, "username", user, "password", "Test-Only-Password-429!")
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
        "/farms",
        Map.of("name", "测试农场-" + UUID.randomUUID(), "description", "测试独立记录")
      ),
      200
    )
      .get("ID")
      .asText();
  }

  String plot(String token, String farm) throws Exception {
    return data(
      request(
        token,
        "POST",
        "/plots",
        Map.of("farmId", farm, "name", "测试田", "areaMu", 25, "crop", "水稻")
      ),
      200
    )
      .get("ID")
      .asText();
  }

  Map<String, Object> task(String plot) {
    return Map.of(
      "plotId",
      plot,
      "title",
      "巡查任务",
      "taskType",
      "INSPECTION",
      "dueDate",
      "2027-01-01",
      "note",
      ""
    );
  }

  @Test
  void unauthenticatedAndInvalidTokensAreRejected() throws Exception {
    data(request(null, "GET", "/farms", null), 401);
    data(request("tampered", "GET", "/farms", null), 401);
    data(
      request(
        null,
        "POST",
        "/auth/login",
        Map.of("tenantCode", "demo-a", "username", "admin", "password", "wrong")
      ),
      401
    );
  }

  @Test
  void listsAndSummaryAreTenantScoped() throws Exception {
    String id = farm(a);
    JsonNode list = data(request(b, "GET", "/farms", null), 200);
    for (var row : list) assertNotEquals(id, row.get("ID").asText());
    long before = data(request(b, "GET", "/dashboard", null), 200).get("farms").asLong();
    farm(a);
    assertEquals(before, data(request(b, "GET", "/dashboard", null), 200).get("farms").asLong());
  }

  @Test
  void foreignTenantHeadersAreRejectedAndContextCleared() throws Exception {
    data(request(a, "GET", "/farms", null, "X-Tenant-Id", "forged"), 403);
    data(request(a, "GET", "/farms", null, "tenantId", "forged"), 403);
    data(request(null, "GET", "/farms", null), 401);
    data(request(b, "GET", "/farms", null), 200);
  }

  @Test
  void tenantCannotBeInjectedInBody() throws Exception {
    data(
      request(
        a,
        "POST",
        "/farms",
        Map.of("name", "不合法", "description", "", "tenantId", "forged")
      ),
      400
    );
  }

  @Test
  void crossTenantMutationAndRelationsAreRejected() throws Exception {
    String foreign = farm(b);
    data(request(a, "PUT", "/farms/" + foreign, Map.of("name", "越权", "description", "")), 404);
    data(request(a, "DELETE", "/farms/" + foreign, null), 404);
    data(
      request(
        a,
        "POST",
        "/plots",
        Map.of("farmId", foreign, "name", "越权田", "areaMu", 10, "crop", "水稻")
      ),
      404
    );
    String foreignPlot = plot(b, foreign);
    data(request(a, "POST", "/tasks", task(foreignPlot)), 404);
    data(
      request(
        a,
        "POST",
        "/production",
        Map.of("plotId", foreignPlot, "recordDate", "2026-01-01", "yieldKg", 20, "note", "")
      ),
      404
    );
  }

  @Test
  void viewerCannotWriteAndOperatorCannotAdminister() throws Exception {
    data(request(viewer, "POST", "/farms", Map.of("name", "越权", "description", "")), 403);
    data(request(operator, "POST", "/farms", Map.of("name", "越权", "description", "")), 403);
    data(request(viewer, "GET", "/members", null), 403);
    data(request(operator, "GET", "/platform/tenants", null), 403);
    data(request(viewer, "GET", "/dashboard", null), 200);
  }

  @Test
  void platformCannotReadTenantBusiness() throws Exception {
    data(request(platform, "GET", "/farms", null), 403);
    data(request(platform, "GET", "/dashboard", null), 403);
    data(request(platform, "GET", "/platform/tenants", null), 200);
  }

  @Test
  void taskStateMachineAndAuditAreReal() throws Exception {
    String plot = plot(a, farm(a));
    String id = data(request(a, "POST", "/tasks", task(plot)), 200).get("ID").asText();
    data(request(b, "PATCH", "/tasks/" + id + "/status", Map.of("status", "RUNNING")), 404);
    data(request(viewer, "PATCH", "/tasks/" + id + "/status", Map.of("status", "RUNNING")), 403);
    data(
      request(operator, "PATCH", "/tasks/" + id + "/status", Map.of("status", "COMPLETED")),
      409
    );
    data(request(operator, "PATCH", "/tasks/" + id + "/status", Map.of("status", "RUNNING")), 200);
    data(
      request(operator, "PATCH", "/tasks/" + id + "/status", Map.of("status", "COMPLETED")),
      200
    );
    data(request(operator, "PATCH", "/tasks/" + id + "/status", Map.of("status", "RUNNING")), 409);
    var audit = data(request(a, "GET", "/audit", null), 200);
    assertTrue(audit.toString().contains(id));
    assertFalse(data(request(b, "GET", "/audit", null), 200).toString().contains(id));
  }

  @Test
  void plantingValidatesAreaDatesAndOverlap() throws Exception {
    String plot = plot(a, farm(a));
    var input = new HashMap<String, Object>(
      Map.of(
        "plotId",
        plot,
        "crop",
        "水稻",
        "variety",
        "示范",
        "areaMu",
        30,
        "startDate",
        "2027-01-01",
        "endDate",
        "2027-03-01"
      )
    );
    data(request(a, "POST", "/plantings", input), 400);
    input.put("areaMu", 20);
    input.put("endDate", "2026-01-01");
    data(request(a, "POST", "/plantings", input), 400);
    input.put("endDate", "2027-03-01");
    String id = data(request(a, "POST", "/plantings", input), 200).get("ID").asText();
    data(request(a, "POST", "/plantings", input), 409);
    data(
      request(operator, "PATCH", "/plantings/" + id + "/status", Map.of("status", "ACTIVE")),
      200
    );
    data(
      request(operator, "PATCH", "/plantings/" + id + "/status", Map.of("status", "FINISHED")),
      200
    );
    data(request(a, "POST", "/plantings", input), 200);
  }

  @Test
  void simulatedAndManualObservationsHaveDistinctSources() throws Exception {
    String farm = farm(a);
    String id = data(
      request(
        a,
        "POST",
        "/devices",
        Map.of("farmId", farm, "name", "模拟点", "metric", "TEMPERATURE", "adapter", "SIMULATED")
      ),
      200
    )
      .get("ID")
      .asText();
    data(request(b, "POST", "/devices/" + id + "/sample", null), 404);
    JsonNode result = data(request(operator, "POST", "/devices/" + id + "/sample", null), 200);
    assertEquals("SIMULATED", result.get("SOURCE").asText());
    data(
      request(
        a,
        "POST",
        "/devices/" + id + "/observations",
        Map.of("value", 20, "measuredAt", "2026-01-01T08:00:00")
      ),
      409
    );
    String manual = data(
      request(
        a,
        "POST",
        "/devices",
        Map.of("farmId", farm, "name", "手工点", "metric", "HUMIDITY", "adapter", "MANUAL")
      ),
      200
    )
      .get("ID")
      .asText();
    data(request(a, "POST", "/devices/" + manual + "/sample", null), 409);
    data(
      request(
        operator,
        "POST",
        "/devices/" + manual + "/observations",
        Map.of("value", 101, "measuredAt", "2026-01-01T08:00:00")
      ),
      400
    );
    result = data(
      request(
        operator,
        "POST",
        "/devices/" + manual + "/observations",
        Map.of("value", 55, "measuredAt", "2026-01-01T08:00:00")
      ),
      200
    );
    assertEquals("MANUAL", result.get("SOURCE").asText());
  }

  @Test
  void logoutRevokesTokenImmediately() throws Exception {
    data(request(a, "POST", "/auth/logout", null), 200);
    data(request(a, "GET", "/auth/me", null), 401);
  }

  @Test
  void disabledTenantInvalidatesExistingSession() throws Exception {
    String code = "test-" + UUID.randomUUID().toString().substring(0, 8);
    String id = data(
      request(
        platform,
        "POST",
        "/platform/tenants",
        Map.of("code", code, "name", "隔离测试租户", "adminPassword", "Test-Only-Password-429!")
      ),
      200
    )
      .get("id")
      .asText();
    String token = login(code, "admin");
    data(request(token, "GET", "/farms", null), 200);
    data(request(platform, "PATCH", "/platform/tenants/" + id, Map.of("enabled", false)), 200);
    data(request(token, "GET", "/farms", null), 401);
  }

  @Test
  void memberCreationDoesNotExposeHashesAndDisableRevokesSessions() throws Exception {
    String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
    String id = data(
      request(
        a,
        "POST",
        "/members",
        Map.of(
          "username",
          username,
          "displayName",
          "新成员",
          "password",
          "Test-Only-Password-429!",
          "role",
          "VIEWER"
        )
      ),
      200
    )
      .get("id")
      .asText();
    String token = login("demo-a", username);
    assertFalse(data(request(a, "GET", "/members", null), 200).toString().contains("PASSWORD"));
    data(request(b, "PATCH", "/members/" + id, Map.of("enabled", false)), 404);
    data(request(a, "PATCH", "/members/" + id, Map.of("enabled", false)), 200);
    data(request(token, "GET", "/farms", null), 401);
  }

  @Test
  void productionPersistsAndAggregateChanges() throws Exception {
    String plot = plot(a, farm(a));
    double before = data(request(a, "GET", "/dashboard", null), 200).get("yieldKg").asDouble();
    data(
      request(
        operator,
        "POST",
        "/production",
        Map.of("plotId", plot, "recordDate", "2026-01-01", "yieldKg", 88.5, "note", "实测记录")
      ),
      200
    );
    assertEquals(
      before + 88.5,
      data(request(a, "GET", "/dashboard", null), 200).get("yieldKg").asDouble(),
      0.01
    );
    data(request(a, "DELETE", "/plots/" + plot, null), 409);
  }

  @Test
  void duplicateFarmRollsBackWithoutPhantomAudit() throws Exception {
    String name = "唯一名称-" + UUID.randomUUID();
    var input = Map.of("name", name, "description", "");
    data(request(a, "POST", "/farms", input), 200);
    int before = data(request(a, "GET", "/audit", null), 200).size();
    data(request(a, "POST", "/farms", input), 409);
    assertEquals(before, data(request(a, "GET", "/audit", null), 200).size());
    data(request(b, "POST", "/farms", input), 200);
  }
}
