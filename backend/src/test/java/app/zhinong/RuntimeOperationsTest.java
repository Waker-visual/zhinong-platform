package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:runtime-ops-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=false", "farm.bootstrap-password=Test-Only-Password-429!"
})
@AutoConfigureObservability
@Import(RuntimeOperationsTest.FailureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeOperationsTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  final HttpClient client = HttpClient.newHttpClient();
  String admin, platform;

  @BeforeAll void login() throws Exception {
    admin = login("demo-a", "admin");
    platform = login("platform", "platform");
  }

  @Test void runtimeMetricsAreRestrictedAndSensitiveActuatorsAreNotExposed() throws Exception {
    assertEquals(401, get("/api/ops/metrics", null).statusCode());
    assertEquals(403, get("/api/ops/metrics", admin).statusCode());
    assertEquals(403, get("/api/%6fps/metrics", admin).statusCode());
    var health = get("/api/ops/health/readiness", platform);
    assertEquals(200, health.statusCode());
    assertEquals("UP", json.readTree(health.body()).path("status").asText());
    assertFalse(health.body().contains("jdbc:"));
    var metrics = get("/api/ops/metrics", platform);
    assertEquals(200, metrics.statusCode());
    assertTrue(metrics.body().contains("http.server.requests"));
    assertTrue(metrics.body().contains("jdbc.connections.active"));
    assertEquals(200, get("/api/ops/metrics/http.server.requests", platform).statusCode());
    for (String hidden : new String[]{"env", "configprops", "heapdump", "logfile", "shutdown"}) {
      assertEquals(404, get("/api/ops/" + hidden, platform).statusCode());
    }
    assertEquals(403, get("/api/farms", platform).statusCode());
  }

  @Test void legacyReadinessAndDatabaseDeadlineRemainExplicit() throws Exception {
    var health = get("/api/health", null);
    assertEquals(200, health.statusCode());
    assertTrue(json.readTree(health.body()).path("ready").asBoolean());
    assertEquals(10, db.getQueryTimeout());
  }

  @Test void databaseResourceFailureIsRetryableAndDoesNotExposeConnectionDetails() throws Exception {
    var response = get("/api/test/runtime-unavailable", admin);
    assertEquals(503, response.statusCode());
    assertEquals("2", response.headers().firstValue("Retry-After").orElseThrow());
    assertEquals("数据服务暂时繁忙，请稍后重试", json.readTree(response.body()).path("message").asText());
    assertFalse(response.body().contains("private diagnostic"));
  }

  @Test void cumulativeTransactionTimeoutIsRetryableAndDoesNotExposeSqlDetails() throws Exception {
    var response = get("/api/test/runtime-transaction-timeout", admin);
    assertEquals(503, response.statusCode());
    assertEquals("2", response.headers().firstValue("Retry-After").orElseThrow());
    assertEquals("数据服务暂时繁忙，请稍后重试", json.readTree(response.body()).path("message").asText());
    assertFalse(response.body().contains("private diagnostic"));
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private String login(String tenant, String user) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
        "tenantCode", tenant, "username", user, "password", "Test-Only-Password-429!")))).build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return json.readTree(response.body()).path("token").asText();
  }

  @TestConfiguration static class FailureConfiguration {
    @Bean FailureController failureController() { return new FailureController(); }
  }
  @RestController static class FailureController {
    @GetMapping("/api/test/runtime-unavailable") Map<String, Object> unavailable() {
      throw new CannotGetJdbcConnectionException("private diagnostic");
    }
    @GetMapping("/api/test/runtime-transaction-timeout") Map<String, Object> transactionTimeout() {
      throw new TransactionTimedOutException("private diagnostic");
    }
  }
}
