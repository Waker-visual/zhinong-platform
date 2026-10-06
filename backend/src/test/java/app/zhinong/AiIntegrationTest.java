package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.ai.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-tests;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=false",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
@Import(AiIntegrationTest.ModelConfig.class)
class AiIntegrationTest {

  @LocalServerPort
  int port;

  @Autowired
  ObjectMapper json;

  // No external requests or writes to the user's model configuration during tests.
  @Autowired
  TestLlmGateway llm;

  final HttpClient client = HttpClient.newHttpClient();
  String admin, otherAdmin, operator, viewer, platform, farmId;

  @BeforeEach
  void identities() throws Exception {
    admin = login("demo-a", "admin");
    otherAdmin = login("demo-b", "admin");
    operator = login("demo-a", "operator");
    viewer = login("demo-a", "viewer");
    platform = login("platform", "platform");
    farmId = call(admin, "POST", "/farms", Map.of("name", "AI验收农场-" + UUID.randomUUID(), "description", "虚构测试数据"), 200)
      .path("ID").asText();
    llm.answer = Optional.empty();
    llm.calls.set(0);
    llm.saved = null;
  }

  @Test
  void globalModelConfigurationIsRestrictedToPlatformAdmin() throws Exception {
    Map<String, String> input = Map.of("url", "https://example.invalid/v1", "model", "test-model");
    call(null, "GET", "/ai/status", null, 401);
    call(null, "GET", "/ai/config", null, 401);
    for (String token : List.of(admin, otherAdmin, operator, viewer)) {
      call(token, "GET", "/ai/config", null, 403);
      call(token, "POST", "/ai/config", input, 403);
    }
    assertNull(llm.saved);
    JsonNode config = call(platform, "GET", "/ai/config", null, 200);
    assertFalse(config.has("apiKey"));
    assertTrue(config.has("apiKeySet"));
    call(platform, "POST", "/ai/config", input, 200);
    assertEquals(Arrays.asList("https://example.invalid/v1", null, "test-model"), llm.saved);
  }

  @Test
  void farmAccessIsCheckedBeforeAnyModelCall() throws Exception {
    // Even a model that answers without requesting tools cannot bypass farm authorization.
    llm.answer = Optional.of(
      new LlmGateway.LlmResponse("虚构演示回答", List.of())
    );
    Map<String, String> ask = Map.of("farmId", farmId, "question", "今天需要做什么？");
    call(null, "POST", "/ai/ask", ask, 401);
    call(otherAdmin, "POST", "/ai/ask", ask, 404);
    call(platform, "POST", "/ai/ask", ask, 403);
    call(admin, "POST", "/ai/ask", Map.of("farmId", "missing", "question", "农场概况"), 404);
    call(otherAdmin, "POST", "/ai/insights", Map.of("farmId", farmId), 404);
    call(platform, "POST", "/ai/insights", Map.of("farmId", farmId), 403);
    assertEquals(0, llm.calls.get());
    assertEquals("llm", call(admin, "POST", "/ai/ask", ask, 200).path("mode").asText());
    assertEquals(1, llm.calls.get());
  }

  @Test
  void tenantUsersReceiveRuleAnswersWhenModelIsUnavailable() throws Exception {
    for (String token : List.of(admin, operator, viewer)) {
      JsonNode answer = call(token, "POST", "/ai/ask", Map.of("farmId", farmId, "question", "农场概况"), 200);
      assertEquals("rule", answer.path("mode").asText());
      assertFalse(answer.path("answer").asText().isBlank());
      JsonNode insights = call(token, "POST", "/ai/insights", Map.of("farmId", farmId), 200);
      assertEquals("rule", insights.path("mode").asText());
      assertFalse(insights.path("items").isEmpty());
    }
  }

  String login(String tenant, String username) throws Exception {
    return call(null, "POST", "/auth/login",
      Map.of("tenantCode", tenant, "username", username, "password", "Test-Only-Password-429!"), 200)
      .path("token").asText();
  }

  JsonNode call(String token, String method, String path, Object body, int expected) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
      .header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  @TestConfiguration
  static class ModelConfig {
    @Bean
    @Primary
    TestLlmGateway testLlmGateway() {
      return new TestLlmGateway();
    }
  }

  static class TestLlmGateway extends LlmGateway {
    volatile Optional<LlmResponse> answer = Optional.empty();
    volatile List<String> saved;
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public Optional<LlmResponse> complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
      calls.incrementAndGet();
      return answer;
    }

    @Override
    public Optional<String> chat(String system, String user) {
      calls.incrementAndGet();
      return answer.map(LlmResponse::content);
    }

    @Override
    public void saveConfig(String url, String key, String model) {
      saved = Arrays.asList(url, key, model);
    }

    @Override
    public String url() { return ""; }

    @Override
    public String model() { return ""; }

    @Override
    public boolean apiKeySet() { return false; }

    @Override
    public boolean cloudEnabled() { return false; }
  }
}
