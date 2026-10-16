package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.ai.LlmGateway;
import app.zhinong.business.FarmClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A server running in UTC at 01:00 Beijing time: the server's own date is still yesterday, the
 * farm's is today. Runs in the separate "server-utc" surefire execution (-Duser.timezone=UTC,
 * see pom.xml); the farm clock is fixed at 2026-03-15 01:00 +08:00 = 2026-03-14 17:00 UTC.
 */
@Tag("server-utc")
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:business-calendar-utc;DB_CLOSE_DELAY=-1",
    "farm.demo=true",
    "farm.demo-rich=false",
    "farm.ai.automation-enabled=false",
    "farm.map.automation-enabled=false",
    "farm.bootstrap-password=Test-Only-Password-429!",
  }
)
@Import(BusinessCalendarUtcIntegrationTest.FixedFarmTime.class)
class BusinessCalendarUtcIntegrationTest {
  static final String PASSWORD = "Test-Only-Password-429!";
  static final Instant BEIJING_1AM = Instant.parse("2026-03-14T17:00:00Z");
  static final LocalDate FARM_TODAY = LocalDate.parse("2026-03-15");
  static final LocalDate SERVER_TODAY = LocalDate.parse("2026-03-14");

  @TestConfiguration
  static class FixedFarmTime {
    @Bean Clock farmClockSource() { return Clock.fixed(BEIJING_1AM, ZoneOffset.UTC); }
    @Bean @Primary CapturingLlm capturingLlm() { return new CapturingLlm(); }
  }

  /** Records system prompts and answers nothing, so every AI path takes its rule fallback offline. */
  static class CapturingLlm extends LlmGateway {
    final List<String> systemPrompts = new CopyOnWriteArrayList<>();
    @Override public Optional<LlmResponse> complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
      for (var message : messages) if ("system".equals(message.get("role"))) systemPrompts.add(String.valueOf(message.get("content")));
      return Optional.empty();
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired FarmClock clock;
  @Autowired CapturingLlm llm;
  final HttpClient http = HttpClient.newHttpClient();
  String admin, operator, operatorId, tenant, farm;

  @BeforeEach
  void farm() throws Exception {
    admin = login("admin");
    operator = login("operator");
    tenant = db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'", String.class);
    operatorId = db.queryForObject("SELECT id FROM members WHERE tenant_id=? AND username='operator'", String.class, tenant);
    farm = call(admin, "POST", "/farms", Map.of("name", "虚构日界农场-" + UUID.randomUUID(), "description", "UTC 服务器回归"), 200).path("ID").asText();
  }

  @Test
  void serverDateLagsTheFarmCalendarAtOneInTheMorning() {
    assertEquals(ZoneOffset.UTC, ZoneId.systemDefault().normalized(), "run via the server-utc surefire execution (-Duser.timezone=UTC)");
    assertEquals(SERVER_TODAY, LocalDate.ofInstant(BEIJING_1AM, ZoneId.systemDefault()), "the old LocalDate.now() answer");
    assertEquals(ZoneId.of("Asia/Shanghai"), clock.zone());
    assertEquals(FARM_TODAY, clock.today());
    assertEquals(LocalTime.of(1, 0), clock.time());
    assertEquals(OffsetDateTime.parse("2026-03-15T00:00+08:00"), clock.startOf(FARM_TODAY));
  }

  @Test
  void tasksDueYesterdayInBeijingAreOverdueEverywhere() throws Exception {
    String plot = plot("虚构逾期田");
    long dashboardOverdue = call(admin, "GET", "/dashboard", null, 200).path("overdueTasks").asLong();
    task(plot, "昨日到期", SERVER_TODAY); // The server's "today" is the farm's yesterday.
    task(plot, "今日到期", FARM_TODAY);

    var board = call(admin, "GET", "/field-work?farmId=" + farm, null, 200);
    assertEquals(FARM_TODAY.toString(), board.path("today").asText(), "DailyFarm marks dueDate < today as overdue");
    var overdue = new ArrayList<String>();
    for (var t : board.path("tasks")) if (t.path("DUE_DATE").asText().compareTo(board.path("today").asText()) < 0) overdue.add(t.path("TITLE").asText());
    assertEquals(List.of("昨日到期"), overdue);

    assertEquals(dashboardOverdue + 1, call(admin, "GET", "/dashboard", null, 200).path("overdueTasks").asLong());
    var operations = call(admin, "GET", "/farms/" + farm + "/operations?hours=24", null, 200).path("summary");
    assertEquals(1, operations.path("overdueTasks").asInt());
    assertEquals(FARM_TODAY.toString(), operations.path("today").asText());
    var analysis = call(admin, "GET", "/ai/analysis?farmId=" + farm, null, 200);
    assertEquals(1, analysis.at("/tasks/overdue").asInt());
    assertEquals(OffsetDateTime.parse("2026-03-15T01:00+08:00"), OffsetDateTime.parse(analysis.path("generatedAt").asText()));
    assertEquals(FARM_TODAY.toString(), call(admin, "GET", "/ai/briefings/today?farmId=" + farm, null, 200).path("reportDate").asText());
  }

  @Test
  void plantingWindowsAndProductionRangeUseTheFarmDate() throws Exception {
    String plot = plot("虚构换茬田");
    // Ended on the farm's yesterday (the server's today) and started on the farm's today.
    planting(plot, "旧茬蔬菜", LocalDate.parse("2025-11-01"), SERVER_TODAY);
    planting(plot, "蔬菜", FARM_TODAY, LocalDate.parse("2026-06-30"));
    var plots = call(admin, "GET", "/ai/irrigation?farmId=" + farm, null, 200).path("plots");
    assertEquals(List.of("蔬菜"), json.convertValue(plots.get(0).path("activeCrops"), List.class));

    for (var day : List.of(FARM_TODAY, FARM_TODAY.plusDays(1))) {
      db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(), tenant, plot, day, day.equals(FARM_TODAY) ? 10 : 99, "虚构日界产量");
    }
    var analytics = call(admin, "GET", "/farms/" + farm + "/analytics?days=7", null, 200);
    assertEquals(FARM_TODAY.toString(), analytics.path("to").asText());
    assertEquals(10, analytics.at("/summary/yieldKg").asInt(), "Tomorrow's record is outside the farm's date range");
  }

  @Test
  void dailyIrrigationLimitStartsAtBeijingMidnight() throws Exception {
    String plot = plot("虚构灌溉田");
    // A planting well inside its window, so only the daily quota decides (date windows: test above).
    planting(plot, "蔬菜", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-06-30"));
    String sensor = device(plot, "SOIL"), pump = device(plot, "PUMP");
    reading(sensor, "SOIL_MOISTURE", 20); reading(pump, "REMOTE_ENABLED", 1); reading(pump, "FAULT", 0); reading(pump, "PUMP_RUNNING", 0);
    var policy = new LinkedHashMap<String, Object>();
    policy.putAll(Map.of("farmId", farm, "plotId", plot, "sensorId", sensor, "pumpId", pump, "mode", "MANUAL", "thresholdValue", 30,
      "durationSeconds", 60, "cooldownMinutes", 30, "dailyLimit", 1, "revision", 0));
    call(admin, "PUT", "/ai/irrigation/policy", policy, 200);

    // 23:30 yesterday in Beijing is 15:30 UTC on the server's current date: not today's quota.
    completedRun(plot, sensor, pump, OffsetDateTime.parse("2026-03-14T23:30+08:00"));
    assertEquals("PROPOSED", call(operator, "POST", "/ai/irrigation/plots/" + plot + "/propose", null, 200).path("STATUS").asText());

    // 00:10 today in Beijing (outside the 30-minute cooldown) uses the only daily run.
    completedRun(plot, sensor, pump, OffsetDateTime.parse("2026-03-15T00:10+08:00"));
    var refused = call(operator, "POST", "/ai/irrigation/plots/" + plot + "/propose", null, 409);
    assertTrue(refused.path("message").asText().contains("今日灌溉次数上限"), refused.toString());
  }

  @Test
  void assistantPromptsStateTheFarmDate() throws Exception {
    llm.systemPrompts.clear();
    String conversation = call(admin, "POST", "/ai/conversations", Map.of("farmId", farm), 200).path("id").asText();
    call(admin, "POST", "/ai/conversations/" + conversation + "/messages", Map.of("question", "今天先做什么？", "requestId", "utc-calendar"), 200);
    var dated = llm.systemPrompts.stream().filter(p -> p.contains("今天是")).toList();
    assertEquals(1, dated.size(), llm.systemPrompts.toString());
    assertTrue(dated.get(0).contains("今天是2026-03-15。"), dated.get(0));
    assertTrue(dated.stream().noneMatch(p -> p.contains(SERVER_TODAY.toString())));
  }

  String plot(String name) throws Exception {
    return call(admin, "POST", "/plots", Map.of("farmId", farm, "name", name + "-" + UUID.randomUUID(), "crop", "蔬菜", "areaMu", 5), 200).path("ID").asText();
  }

  void task(String plot, String title, LocalDate due) throws Exception {
    call(admin, "POST", "/field-work/tasks", Map.of("plotId", plot, "title", title, "taskType", "INSPECTION", "dueDate", due.toString(),
      "assigneeId", operatorId, "method", "MANUAL", "note", "虚构日界任务"), 200);
  }

  void planting(String plot, String crop, LocalDate start, LocalDate end) {
    db.update("INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,5,?,?,'ACTIVE')",
      UUID.randomUUID().toString(), tenant, plot, crop, "演示品种", start, end);
  }

  String device(String plot, String type) throws Exception {
    var input = new LinkedHashMap<String, Object>();
    input.putAll(Map.of("farmId", farm, "plotId", plot, "name", "虚构" + type, "code", "UTC-" + UUID.randomUUID(), "deviceType", type,
      "protocol", "SIMULATED", "lifecycle", "ACTIVE", "model", "测试", "notes", "虚构", "intervalSeconds", 60));
    input.put("revision", 0);
    input.put("controlEnabled", type.equals("PUMP"));
    input.put("channels", app.zhinong.workspace.MetricCatalog.PRESETS.get(type).stream().map(m -> Map.of("metric", m)).toList());
    return call(admin, "POST", "/assets", input, 200).path("id").asText();
  }

  void reading(String device, String metric, int value) {
    var at = clock.now().minusSeconds(30);
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')",
      UUID.randomUUID().toString(), tenant, device, metric, value, at, at);
  }

  void completedRun(String plot, String sensor, String pump, OffsetDateTime started) {
    db.update("""
      INSERT INTO ai_irrigation_runs(id,tenant_id,farm_id,plot_id,sensor_id,pump_id,policy_revision,status,reason,duration_seconds,
        moisture_value,requested_by,created_at,expires_at,started_at,stop_at,finished_at)
      VALUES(?,?,?,?,?,?,1,'COMPLETED','虚构历史灌溉',60,20,'test',?,?,?,?,?)
      """, UUID.randomUUID().toString(), tenant, farm, plot, sensor, pump,
      started, started.plusMinutes(10), started, started.plusSeconds(60), started.plusSeconds(60));
  }

  String login(String user) throws Exception {
    return call(null, "POST", "/auth/login", Map.of("tenantCode", "demo-a", "username", user, "password", PASSWORD), 200).path("token").asText();
  }

  JsonNode call(String token, String method, String path, Object body, int expected) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path)).header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), method + " " + path + ": " + response.body());
    return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
  }
}
