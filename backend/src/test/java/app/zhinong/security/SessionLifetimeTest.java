package app.zhinong.security;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:session-lifetime-tests;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=false",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
class SessionLifetimeTest {

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  final HttpClient client = HttpClient.newHttpClient();

  HttpResponse<String> post(String path, Object body) throws Exception {
    return client.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  int me(String token) throws Exception {
    return client.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
        .header("Authorization", "Bearer " + token)
        .build(),
      HttpResponse.BodyHandlers.discarding()
    ).statusCode();
  }

  String login() throws Exception {
    var response = post("/auth/login", Map.of("tenantCode", "demo-a", "username", "viewer", "password", "Test-Only-Password-429!"));
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body()).path("token").asText();
  }

  void place(String token, Instant expiresAt, Instant createdAt) {
    db.update(
      "UPDATE sessions SET expires_at=?,created_at=? WHERE token_hash=?",
      Timestamp.from(expiresAt),
      createdAt == null ? null : Timestamp.from(createdAt),
      AuthService.digest(token)
    );
  }

  Instant column(String token, String name) {
    return db.queryForObject("SELECT " + name + " FROM sessions WHERE token_hash=?", Timestamp.class, AuthService.digest(token)).toInstant();
  }

  static void near(Instant expected, Instant actual) {
    assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofMinutes(1)) < 0, expected + " vs " + actual);
  }

  @Test
  void activeSessionsSlideButNeverPassTheAbsoluteLimit() throws Exception {
    String token = login();
    Instant now = Instant.now();
    near(now, column(token, "created_at"));

    place(token, now.plus(Duration.ofMinutes(10)), now.minus(Duration.ofHours(1)));
    assertEquals(200, me(token));
    near(Instant.now().plus(AuthService.IDLE_TIMEOUT), column(token, "expires_at"));

    // A request shortly after renewal does not write again.
    Instant renewed = column(token, "expires_at");
    assertEquals(200, me(token));
    assertEquals(renewed, column(token, "expires_at"));

    Instant created = now.minus(AuthService.ABSOLUTE_TIMEOUT).plus(Duration.ofMinutes(10));
    place(token, now.plus(Duration.ofMinutes(2)), created);
    assertEquals(200, me(token));
    near(created.plus(AuthService.ABSOLUTE_TIMEOUT), column(token, "expires_at"));

    place(token, now.minusSeconds(1), created);
    assertEquals(401, me(token));
  }

  @Test
  void sessionsIssuedBeforeTheUpgradeGetAFixedStart() throws Exception {
    String token = login();
    Instant now = Instant.now();
    Instant expires = now.plus(Duration.ofMinutes(30));
    place(token, expires, null);
    assertEquals(200, me(token));
    near(expires.minus(AuthService.IDLE_TIMEOUT), column(token, "created_at"));
    near(Instant.now().plus(AuthService.IDLE_TIMEOUT), column(token, "expires_at"));
  }

  @Test
  void repeatedWrongPasswordsAreDelayedPerAccount() throws Exception {
    var attempt = Map.of("tenantCode", "demo-a", "username", "throttle-probe", "password", "Wrong-Password-000");
    for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS; i++) assertEquals(401, post("/auth/login", attempt).statusCode());
    var blocked = post("/auth/login", attempt);
    assertEquals(429, blocked.statusCode());
    assertTrue(json.readTree(blocked.body()).path("message").asText().contains("秒后再试"));
    // A different account is not affected by the probe.
    assertEquals(200, post("/auth/login", Map.of("tenantCode", "demo-a", "username", "viewer", "password", "Test-Only-Password-429!")).statusCode());
  }
}
