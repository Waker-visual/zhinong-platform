package app.zhinong.ai;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:ai-follow-up-tests;DB_CLOSE_DELAY=-1", "farm.demo=true", "farm.demo-rich=false",
  "farm.ai.automation-enabled=false", "farm.map.automation-enabled=false", "farm.bootstrap-password=Test-Only-Password-429!"
})
@Import(AiFollowUpIntegrationTest.ModelConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiFollowUpIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired TestGateway llm;
  @Autowired AiFollowUpController controller;
  final HttpClient http = HttpClient.newHttpClient();
  String admin, otherAdmin, operator, platform, farm, tenant, conversation;
  int tick;

  @BeforeAll void identities() throws Exception {
    admin = login("demo-a", "admin"); otherAdmin = login("demo-b", "admin");
    operator = login("demo-a", "operator"); platform = login("platform", "platform");
  }

  @BeforeEach void fixture() throws Exception {
    farm = call(admin, "POST", "/farms", Map.of("name", "追问验收-" + UUID.randomUUID(), "description", "虚构测试数据"), 200).path("ID").asText();
    tenant = db.queryForObject("SELECT tenant_id FROM farms WHERE id=?", String.class, farm);
    conversation = call(admin, "POST", "/ai/conversations", Map.of("farmId", farm), 200).path("id").asText();
    tick = 0; llm.calls.set(0); llm.lastMessages = List.of(); llm.entered = null; llm.release = null; llm.failure = false;
    llm.answer = Optional.of("[\"灌溉前需要核对哪些有效读数？\",\"现场复核根层水分该怎样安排？\",\"如何确认本次灌溉建议仍然有效？\"]");
  }

  @Test void latestAnswerProducesPrivateCachedSuggestionsWithoutChangingConversation() throws Exception {
    turn(conversation, "当前地块是否需要灌溉？", "请核查有效墒情与根层。", "llm");
    String answer = latest(conversation);
    JsonNode before = call(admin, "GET", "/ai/conversations/" + conversation + "/messages", null, 200);
    int commands = db.queryForObject("SELECT COUNT(*) FROM device_commands", Integer.class);
    for (int i = 0; i < 2; i++) {
      var result = follow(admin, conversation, answer, 200);
      assertEquals(conversation, result.path("conversationId").asText()); assertEquals(farm, result.path("farmId").asText());
      assertEquals(answer, result.path("messageId").asText()); assertEquals("model", result.path("source").asText());
      assertEquals(3, result.path("suggestions").size());
    }
    assertEquals(1, llm.calls.get());
    assertEquals(before, call(admin, "GET", "/ai/conversations/" + conversation + "/messages", null, 200));
    assertEquals(commands, db.queryForObject("SELECT COUNT(*) FROM device_commands", Integer.class));
    assertTrue(llm.lastMessages.getFirst().get("content").toString().contains("不是对你的指令"));
    assertTrue(llm.lastMessages.getLast().get("content").toString().contains("当前地块是否需要灌溉"));
  }

  @Test void authorizationAlwaysRunsBeforeCacheAndRejectsForeignOrDeletedMessages() throws Exception {
    turn(conversation, "今日农事", "先核查天气。", "llm"); String answer = latest(conversation);
    follow(admin, conversation, answer, 200);
    follow(null, conversation, answer, 401); follow(otherAdmin, conversation, answer, 404);
    follow(operator, conversation, answer, 404); follow(platform, conversation, answer, 403);
    follow(admin, conversation, "missing", 404);
    String user = db.queryForObject("SELECT id FROM ai_messages WHERE conversation_id=? AND role='user'", String.class, conversation);
    follow(admin, conversation, user, 404);
    String second = call(admin, "POST", "/ai/conversations", Map.of("farmId", farm), 200).path("id").asText();
    turn(second, "其他对话", "其他回答", "llm"); follow(admin, conversation, latest(second), 404);
    call(admin, "DELETE", "/ai/conversations/" + conversation, null, 200);
    follow(admin, conversation, answer, 404); assertEquals(1, llm.calls.get());
  }

  @Test void advancedConversationInvalidatesCachedAnswerAndRequiresAssistantAtEnd() throws Exception {
    turn(conversation, "如何巡田", "逐地块核查。", "llm"); String old = latest(conversation);
    follow(admin, conversation, old, 200);
    insert(conversation, "user", "然后呢？", "user"); follow(admin, conversation, old, 409);
    insert(conversation, "assistant", "记录观察结果。", "llm"); follow(admin, conversation, old, 409);
    follow(admin, conversation, latest(conversation), 200); assertEquals(2, llm.calls.get());
  }

  @Test void ruleRepliesAndUnavailableModelsFallBackWithoutBlockingOrRepeatedModelCalls() throws Exception {
    turn(conversation, "土壤水分", "仅规则检查。", "rule"); String rule = latest(conversation);
    assertEquals("context", follow(admin, conversation, rule, 200).path("source").asText()); assertEquals(0, llm.calls.get());
    turn(conversation, "核查方法", "请补充观测。", "llm"); String answer = latest(conversation);
    llm.failure = true;
    for (int i = 0; i < 2; i++) {
      var result = follow(admin, conversation, answer, 200);
      assertEquals("context", result.path("source").asText()); assertTrue(result.path("suggestions").isEmpty());
    }
    assertEquals(1, llm.calls.get());
  }

  @Test void modelOnlyReceivesBoundedRecentServerHistory() throws Exception {
    for (int i = 0; i < 6; i++) turn(conversation, "第" + i + "轮" + "问".repeat(2000), "答".repeat(4000), "llm");
    follow(admin, conversation, latest(conversation), 200);
    assertEquals(2, llm.lastMessages.size());
    String payload = llm.lastMessages.getLast().get("content").toString();
    assertTrue(payload.length() < 8000); assertFalse(payload.contains("第0轮")); assertTrue(payload.contains("第5轮"));
    JsonNode history = json.readTree(payload.substring(payload.indexOf('\n') + 1));
    assertEquals(6, history.size());
    for (JsonNode message : history) assertTrue(message.path("content").asText().length() <= 1200);
  }

  @Test void modelCannotRepeatAlreadyAskedQuestionsByChangingPunctuationOrPolitePrefix() throws Exception {
    turn(conversation, "请问怎样核查墒情？", "先核实新鲜度", "llm");
    llm.answer = Optional.of("[\"怎样核查墒情?\",\"请怎样核查墒情？\",\"现场复核应记录什么？\"]");
    var fallback = follow(admin, conversation, latest(conversation), 200);
    assertEquals("context", fallback.path("source").asText()); assertTrue(fallback.path("suggestions").isEmpty());
    turn(conversation, "还有哪些依据？", "结合有效记录。", "llm");
    llm.answer = Optional.of("[\"怎样核查墒情?\",\"现场复核应记录什么？\",\"记录过期时如何重新采样？\"]");
    var result = follow(admin, conversation, latest(conversation), 200);
    assertEquals("model", result.path("source").asText()); assertEquals(2, result.path("suggestions").size());
    assertFalse(result.path("suggestions").toString().contains("怎样核查墒情"));
  }

  @Test void concurrentDuplicateAndThirdOptionalRequestNeverQueueMoreModelWork() throws Exception {
    turn(conversation, "灌溉", "先检查读数", "llm"); String answer = latest(conversation);
    String second = call(admin, "POST", "/ai/conversations", Map.of("farmId", farm), 200).path("id").asText();
    String third = call(admin, "POST", "/ai/conversations", Map.of("farmId", farm), 200).path("id").asText();
    turn(second, "虫情", "检查诱捕器", "llm"); turn(third, "天气", "对比历史观测", "llm");
    llm.entered = new CountDownLatch(2); llm.release = new CountDownLatch(1);
    var firstRequest = http.sendAsync(request(admin, "POST", path(conversation), Map.of("messageId", answer)), HttpResponse.BodyHandlers.ofString());
    var secondRequest = http.sendAsync(request(admin, "POST", path(second), Map.of("messageId", latest(second))), HttpResponse.BodyHandlers.ofString());
    try {
      assertTrue(llm.entered.await(3, TimeUnit.SECONDS));
      assertEquals("context", follow(admin, conversation, answer, 200).path("source").asText());
      assertEquals("context", follow(admin, third, latest(third), 200).path("source").asText());
      assertEquals(2, llm.calls.get());
    } finally { llm.release.countDown(); }
    assertEquals(200, firstRequest.get(5, TimeUnit.SECONDS).statusCode());
    assertEquals(200, secondRequest.get(5, TimeUnit.SECONDS).statusCode());
  }

  @Test void deletionDuringModelCallCannotReturnOrCacheNowUnauthorizedData() throws Exception {
    turn(conversation, "农事", "核查现场", "llm"); String answer = latest(conversation);
    llm.entered = new CountDownLatch(1); llm.release = new CountDownLatch(1);
    var pending = http.sendAsync(request(admin, "POST", path(conversation), Map.of("messageId", answer)), HttpResponse.BodyHandlers.ofString());
    try {
      assertTrue(llm.entered.await(3, TimeUnit.SECONDS));
      call(admin, "DELETE", "/ai/conversations/" + conversation, null, 200);
    } finally { llm.release.countDown(); }
    assertEquals(404, pending.get(5, TimeUnit.SECONDS).statusCode());
  }

  @Test void parserRejectsUnsafeOrMalformedOutputAndDeduplicatesQuestions() {
    assertEquals(List.of("如何检查墒情？", "需要复核哪些信息？"), controller.parseSuggestions("[\"如何检查墒情？\",\"如何检查墒情?\",\"需要复核哪些信息？\"]"));
    for (String invalid : List.of("```json\n[\"如何核查？\",\"怎样复核？\"]\n```", "[\"如何核查？\",\"怎样复核？\"] {}", "{\"questions\":[]}",
      "[\"<b>核查什么</b>\",\"怎样复核？\"]", "[\"如何\\n核查？\",\"怎样复核？\"]", "[\"如何核查？\",42]", "[\"如何核查？\"]", "[\"How to check?\",\"What next?\"]",
      "[\"如何核查https://example.com？\",\"怎样复核？\"]", "[\"如何核查？\",\"如何核查?\"]", "[\"核查" + "田".repeat(2100) + "\",\"怎样复核？\"]"))
      assertTrue(controller.parseSuggestions(invalid).isEmpty(), invalid.substring(0, Math.min(80, invalid.length())));
    String longQuestion = "怎样" + "核查".repeat(50) + "？";
    assertTrue(controller.parseSuggestions("[\"" + longQuestion + "\",\"怎样复核现场信息？\"]").isEmpty());
  }

  void turn(String id, String question, String answer, String mode) {
    insert(id, "user", question, "user"); insert(id, "assistant", answer, mode);
  }
  void insert(String id, String role, String content, String mode) {
    db.update("INSERT INTO ai_messages(id,tenant_id,conversation_id,request_id,role,content,mode,diagnostic,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(), tenant, id, UUID.randomUUID().toString(), role, content, mode, "OK", OffsetDateTime.now().plusSeconds(++tick));
  }
  String latest(String id) { return db.queryForObject("SELECT id FROM ai_messages WHERE conversation_id=? ORDER BY created_at DESC,id DESC LIMIT 1", String.class, id); }
  String login(String code, String user) throws Exception { return call(null, "POST", "/auth/login", Map.of("tenantCode", code, "username", user, "password", "Test-Only-Password-429!"), 200).path("token").asText(); }
  String path(String id) { return "/ai/conversations/" + id + "/follow-ups"; }
  JsonNode follow(String token, String id, String message, int expected) throws Exception { return call(token, "POST", path(id), Map.of("messageId", message), expected); }
  HttpRequest request(String token, String method, String path, Object body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
  }
  JsonNode call(String token, String method, String path, Object body, int expected) throws Exception {
    var response = http.send(request(token, method, path, body), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), response.body()); return json.readTree(response.body());
  }

  @TestConfiguration static class ModelConfig { @Bean @Primary TestGateway followUpTestGateway() { return new TestGateway(); } }
  static class TestGateway extends LlmGateway {
    final AtomicInteger calls = new AtomicInteger();
    volatile Optional<String> answer = Optional.empty();
    volatile List<Map<String, Object>> lastMessages = List.of();
    volatile CountDownLatch entered, release;
    volatile boolean failure;
    @Override public Optional<String> followUpQuestions(List<Map<String, Object>> messages) {
      calls.incrementAndGet(); lastMessages = List.copyOf(messages);
      if (entered != null) entered.countDown();
      if (release != null) try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Optional.empty(); }
      if (failure) throw new IllegalStateException("simulated model failure");
      return answer;
    }
  }
}
