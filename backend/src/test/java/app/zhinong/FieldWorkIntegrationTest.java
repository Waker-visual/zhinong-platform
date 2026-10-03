package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

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
    "spring.datasource.url=jdbc:h2:mem:field-work-tests;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=false",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
class FieldWorkIntegrationTest {

  static final String PASSWORD = "Test-Only-Password-429!";

  @LocalServerPort
  int port;

  @Autowired
  ObjectMapper json;

  final HttpClient http = HttpClient.newHttpClient();
  String admin, operator, viewer, other, platform, workerId, otherWorkerId, farm, plot, plot2;

  JsonNode call(
    String token,
    String method,
    String path,
    Object body,
    int expected
  ) throws Exception {
    var request = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api" + path)
    ).header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    var response = http.send(
      request
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(expected, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  String login(String tenant, String user) throws Exception {
    return call(
      null,
      "POST",
      "/auth/login",
      Map.of("tenantCode", tenant, "username", user, "password", PASSWORD),
      200
    )
      .path("token")
      .asText();
  }

  @BeforeEach
  void setup() throws Exception {
    admin = login("demo-a", "admin");
    operator = login("demo-a", "operator");
    viewer = login("demo-a", "viewer");
    other = login("demo-b", "admin");
    platform = login("platform", "platform");
    for (var m : call(admin, "GET", "/members", null, 200))
      if (m.path("USERNAME").asText().equals("operator")) workerId = m
        .path("ID")
        .asText();
    var extra = call(
      admin,
      "POST",
      "/members",
      Map.of(
        "username",
        "extra-" + UUID.randomUUID().toString().substring(0, 8),
        "displayName",
        "另一位操作员",
        "role",
        "OPERATOR",
        "password",
        PASSWORD
      ),
      200
    );
    otherWorkerId = extra.path("id").asText();
    farm = call(
      admin,
      "POST",
      "/farms",
      Map.of(
        "name",
        "家庭农场-" + UUID.randomUUID(),
        "description",
        "验收场景"
      ),
      200
    )
      .path("ID")
      .asText();
    plot = createPlot("水稻田");
    plot2 = createPlot("蔬菜田");
  }

  String createPlot(String name) throws Exception {
    return call(
      admin,
      "POST",
      "/plots",
      Map.of("farmId", farm, "name", name, "areaMu", 20, "crop", name),
      200
    )
      .path("ID")
      .asText();
  }

  Map<String, Object> plan(String assignee) {
    return Map.of(
      "plotId",
      plot,
      "title",
      "植保补位",
      "taskType",
      "PROTECTION",
      "dueDate",
      LocalDate.now().toString(),
      "assigneeId",
      assignee,
      "method",
      "UNCONFIRMED",
      "note",
      "无人机缺位，请确认替代资源"
    );
  }

  Map<String, Object> report() {
    return Map.of(
      "plotId",
      plot,
      "category",
      "PEST",
      "severity",
      "HIGH",
      "description",
      "田边虫害，现场无人机不可用"
    );
  }

  Map<String, Object> progress(String status, String method, double area) {
    return Map.of(
      "status",
      status,
      "note",
      "验收：田间作业情况与记录",
      "method",
      method,
      "actualAreaMu",
      area
    );
  }

  String issue() throws Exception {
    return call(operator, "POST", "/field-work/issues", report(), 200)
      .path("id")
      .asText();
  }

  String task(String issue, String assignee) throws Exception {
    return call(
      admin,
      "POST",
      issue == null
        ? "/field-work/tasks"
        : "/field-work/issues/" + issue + "/task",
      plan(assignee),
      200
    )
      .path("id")
      .asText();
  }

  JsonNode state() throws Exception {
    return call(admin, "GET", "/field-work?farmId=" + farm, null, 200);
  }

  long count(String token, String field) throws Exception {
    return call(token, "GET", "/dashboard", null, 200).path(field).asLong();
  }

  @Test
  void dashboardCountsAttentionItemsWithinTenant() throws Exception {
    long issues = count(admin, "openIssues"),
      blocked = count(admin, "blockedTasks"),
      overdue = count(admin, "overdueTasks"),
      otherIssues = count(other, "openIssues");
    issue();
    String current = task(null, workerId);
    call(
      operator,
      "PATCH",
      "/field-work/tasks/" + current + "/progress",
      progress("BLOCKED", "UNCONFIRMED", 0),
      200
    );
    var late = new HashMap<>(plan(workerId));
    late.put("dueDate", LocalDate.now().minusDays(2).toString());
    String lateTask = call(admin, "POST", "/field-work/tasks", late, 200)
      .path("id")
      .asText();
    call(
      operator,
      "PATCH",
      "/field-work/tasks/" + lateTask + "/progress",
      progress("BLOCKED", "UNCONFIRMED", 0),
      200
    );
    assertEquals(issues + 1, count(admin, "openIssues"));
    assertEquals(blocked + 1, count(admin, "blockedTasks"));
    assertEquals(overdue + 1, count(admin, "overdueTasks"));
    assertEquals(otherIssues, count(other, "openIssues"));
  }

  @Test
  void lifecycleRequiresEvidenceAndOwnerReview() throws Exception {
    String issue = issue(),
      task = task(issue, workerId),
      path = "/field-work/tasks/" + task + "/progress";
    call(operator, "PATCH", path, progress("COMPLETED", "MANUAL", 20), 409);
    call(
      admin,
      "POST",
      "/field-work/issues/" + issue + "/resolve",
      Map.of("note", "提前关闭"),
      409
    );
    call(operator, "PATCH", path, progress("BLOCKED", "UNCONFIRMED", 0), 200);
    assertFalse(
      state().path("tasks").get(0).path("BLOCKED_REASON").asText().isBlank()
    );
    call(
      operator,
      "PATCH",
      path,
      progress("COMPLETED", "UNCONFIRMED", 20),
      400
    );
    call(operator, "PATCH", path, progress("COMPLETED", "MANUAL", 21), 400);
    call(operator, "PATCH", path, progress("COMPLETED", "MANUAL", 0), 400);
    call(operator, "PATCH", path, progress("COMPLETED", "MANUAL", 20), 200);
    assertEquals(
      "ASSIGNED",
      state().path("issues").get(0).path("STATUS").asText()
    );
    call(
      operator,
      "POST",
      "/field-work/issues/" + issue + "/resolve",
      Map.of("note", "操作员关闭"),
      403
    );
    call(
      admin,
      "POST",
      "/field-work/issues/" + issue + "/resolve",
      Map.of("note", "复查虫情已控制，继续观察"),
      200
    );
    assertEquals(
      "RESOLVED",
      state().path("issues").get(0).path("STATUS").asText()
    );
    call(operator, "PATCH", path, progress("COMPLETED", "MANUAL", 20), 409);
    assertEquals(
      3,
      call(
        viewer,
        "GET",
        "/field-work/tasks/" + task + "/logs",
        null,
        200
      ).size()
    );
  }

  @Test
  void crossTenantAndPlatformCannotReadOrWrite() throws Exception {
    String issue = issue(),
      task = task(issue, workerId);
    call(other, "GET", "/field-work?farmId=" + farm, null, 404);
    call(platform, "GET", "/field-work?farmId=" + farm, null, 403);
    call(null, "GET", "/field-work?farmId=" + farm, null, 401);
    call(other, "POST", "/field-work/issues", report(), 404);
    call(
      other,
      "POST",
      "/field-work/issues/" + issue + "/task",
      plan(workerId),
      404
    );
    call(other, "GET", "/field-work/tasks/" + task + "/logs", null, 404);
    call(
      other,
      "PATCH",
      "/field-work/tasks/" + task + "/progress",
      progress("RUNNING", "MANUAL", 0),
      404
    );
    var foreign = call(other, "GET", "/members", null, 200)
      .get(0)
      .path("ID")
      .asText();
    call(admin, "POST", "/field-work/tasks", plan(foreign), 404);
  }

  @Test
  void assignmentCannotBeBypassedThroughLegacyStatus() throws Exception {
    String task = task(null, otherWorkerId);
    call(
      operator,
      "PATCH",
      "/field-work/tasks/" + task + "/progress",
      progress("RUNNING", "MANUAL", 0),
      403
    );
    call(
      operator,
      "PATCH",
      "/tasks/" + task + "/status",
      Map.of("status", "RUNNING"),
      403
    );
    call(
      admin,
      "PATCH",
      "/tasks/" + task + "/status",
      Map.of("status", "RUNNING"),
      409
    );
    call(
      admin,
      "PUT",
      "/field-work/tasks/" + task + "/plan",
      plan(workerId),
      200
    );
    call(
      operator,
      "PATCH",
      "/field-work/tasks/" + task + "/progress",
      progress("RUNNING", "MANUAL", 0),
      200
    );
    call(
      operator,
      "PATCH",
      "/field-work/tasks/" + task + "/progress",
      progress("CANCELLED", "MANUAL", 0),
      403
    );
  }

  @Test
  void viewerReadOnlyAndOperatorCannotDispatch() throws Exception {
    call(viewer, "GET", "/field-work?farmId=" + farm, null, 200);
    call(viewer, "POST", "/field-work/issues", report(), 403);
    call(viewer, "POST", "/field-work/tasks", plan(workerId), 403);
    call(operator, "POST", "/field-work/tasks", plan(workerId), 403);
    String task = task(null, workerId);
    call(
      viewer,
      "PATCH",
      "/field-work/tasks/" + task + "/progress",
      progress("RUNNING", "MANUAL", 0),
      403
    );
    var output = state().toString();
    assertFalse(output.contains("PASSWORD_HASH"));
    assertFalse(output.contains(PASSWORD));
  }

  @Test
  void duplicateDispatchAndWrongPlotAreRejectedCancellationReopens()
    throws Exception {
    String issue = issue();
    var wrong = new HashMap<>(plan(workerId));
    wrong.put("plotId", plot2);
    call(admin, "POST", "/field-work/issues/" + issue + "/task", wrong, 400);
    String task = task(issue, workerId);
    call(
      admin,
      "POST",
      "/field-work/issues/" + issue + "/task",
      plan(workerId),
      409
    );
    call(
      admin,
      "PATCH",
      "/field-work/tasks/" + task + "/progress",
      progress("CANCELLED", "UNCONFIRMED", 0),
      200
    );
    assertEquals("OPEN", state().path("issues").get(0).path("STATUS").asText());
    task(issue, workerId);
  }

  @Test
  void disabledAndReadOnlyAssigneesAreRejected() throws Exception {
    call(
      admin,
      "PATCH",
      "/members/" + otherWorkerId,
      Map.of("enabled", false),
      200
    );
    call(admin, "POST", "/field-work/tasks", plan(otherWorkerId), 400);
    for (var m : call(admin, "GET", "/members", null, 200))
      if (m.path("ROLE").asText().equals("VIEWER")) call(
        admin,
        "POST",
        "/field-work/tasks",
        plan(m.path("ID").asText()),
        400
      );
  }
}
