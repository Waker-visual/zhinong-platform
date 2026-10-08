package app.zhinong.ai;

import app.zhinong.ai.LlmGateway.ToolCall;
import app.zhinong.business.Store;
import app.zhinong.fieldwork.FieldWorkService;
import app.zhinong.security.Identity;
import app.zhinong.simulation.SimulationStore;
import app.zhinong.workspace.FarmWorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统智能助手：/api/ai/status 供前端探测点亮；/api/ai/ask 基于 Function
 * Calling（工具调用）回答整个系统的问题——模型自主决定查哪个数据工具，
 * 代码执行真实查询后回填，再总结回答；/api/ai/insights 今日经营建议。
 * 大模型不可用时自动回退规则模板，保证演示页面始终有内容。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

  private final FarmWorkspaceService farmWorkspace;
  private final FieldWorkService fieldWork;
  private final Store store;
  private final SimulationStore simulations;
  private final LlmGateway llm;
  private final ObjectMapper json;

  public AiController(
    FarmWorkspaceService farmWorkspace,
    FieldWorkService fieldWork,
    Store store,
    SimulationStore simulations,
    LlmGateway llm,
    ObjectMapper json
  ) {
    this.farmWorkspace = farmWorkspace;
    this.fieldWork = fieldWork;
    this.store = store;
    this.simulations = simulations;
    this.llm = llm;
    this.json = json;
  }

  public record AskInput(
    @NotBlank String farmId,
    @NotBlank @Size(max = 300) String question,
    List<Map<String, Object>> history
  ) {}

  public record InsightInput(@NotBlank String farmId) {}

  public record LlmConfigInput(
    @Size(max = 300) String url,
    @Size(max = 300) String apiKey,
    @Size(max = 100) String model
  ) {}

  @GetMapping("/status")
  public Map<String, Object> status() {
    return Map.of("enabled", true, "llm", llm.cloudEnabled());
  }

  /** 查看当前模型配置（脱敏：不返回密钥原文）。管理员可用。 */
  @GetMapping("/config")
  public ResponseEntity<Map<String, Object>> llmConfig() {
    if (!isManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅管理员可查看模型配置"));
    }
    return ResponseEntity.ok(
      Map.of(
        "url", llm.url(),
        "model", llm.model(),
        "apiKeySet", llm.apiKeySet(),
        "cloudEnabled", llm.cloudEnabled()
      )
    );
  }

  /** 保存模型配置：立即生效并持久化，之后重启自动加载，无需再注入环境变量。管理员可用。 */
  @PostMapping("/config")
  public ResponseEntity<Map<String, Object>> saveLlmConfig(@RequestBody @Valid LlmConfigInput input) {
    if (!isManager()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅管理员可配置模型"));
    }
    llm.saveConfig(input.url(), input.apiKey(), input.model());
    return ResponseEntity.ok(
      Map.of("ok", true, "url", llm.url(), "model", llm.model(), "cloudEnabled", llm.cloudEnabled())
    );
  }

  /** 平台管理员或农场管理员（演示账号 ADMIN）均可管理模型配置。 */
  private boolean isManager() {
    String role = Identity.current().role();
    return "PLATFORM_ADMIN".equals(role) || "ADMIN".equals(role);
  }

  @PostMapping("/ask")
  public Map<String, Object> ask(@RequestBody @Valid AskInput input) {
    String q = input.question();
    String farmId = input.farmId();
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(
      Map.of(
        "role",
        "system",
        "content",
        "你是智禾农场平台的农场智能助手，用户是农场管理员。今天是" +
        java.time.LocalDate.now() +
        "（" +
        java.time.LocalDate.now().getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINESE) +
        "），回答涉及日期、时间、星期、今天等一律以此为准。" +
        "引用业务数据中的日期（如任务到期日、记录日期）时，直接使用工具返回的原始值（YYYY-MM-DD），不要改写或换算成其他写法。" +
        "系统内所有业务数据（农场档案、经营概况、地块、种植计划、生产记录、监测设备、农事任务、现场问题、成员、操作日志、经营模拟、租户）都可以通过提供的工具实时查询，回答问题时先自主选择合适的工具获取数据，再基于工具返回结果回答。" +
        "当用户明确要求安排农事任务、更新任务进度或上报现场问题时，可调用对应写操作工具（create_task/update_task_progress/report_issue）执行；执行前确认参数完整，执行后如实汇报结果。不要主动执行用户未明确要求的写操作。" +
        "工具返回的 JSON 若含 error 字段，说明该操作失败（如参数不合法、无权限、任务状态不允许），必须在回答中如实说明失败原因并给出解决建议，绝不能把失败说成成功。" +
        "如果是农事知识、方法类问题，可以结合农业常识直接回答。" +
        "用户在前几轮对话中已提供的信息（如地块、任务类型、执行人、到期日、作业方式等）视为已确认，后续轮次直接沿用，不要重复询问。" +
        "不得编造数据、日期或数字；数据中没有的信息，明确说明“暂无相关数据”。" +
        "回答用简体中文，简明自然，像经验丰富的农场管理者，不超过 200 字，可以分 2–3 条要点。"
      )
    );
    // 多轮记忆：把前端传来的历史问答拼进上下文（仅文本，不含工具中间结果）
    if (input.history() != null) {
      int used = 0;
      for (Map<String, Object> h : input.history()) {
        if (used >= 12) break;
        Object role = h.get("role");
        Object content = h.get("content");
        if (content == null || String.valueOf(content).isBlank()) continue;
        if (!"user".equals(role) && !"assistant".equals(role)) continue;
        messages.add(Map.of("role", role, "content", String.valueOf(content)));
        used++;
      }
    }
    messages.add(Map.of("role", "user", "content", q));

    String answer = null;
    List<Map<String, Object>> tools = toolDefinitions();
    java.util.LinkedHashSet<String> executedTools = new java.util.LinkedHashSet<>();
    long started = System.currentTimeMillis();
    int maxRounds = 3;
    for (int round = 0; round < maxRounds && answer == null; round++) {
      var resp = llm.complete(messages, tools).orElse(null);
      if (resp == null) break; // 大模型不可用，走规则兜底
      if (!resp.toolCalls().isEmpty()) {
        // 执行工具调用并回填结果，供下一轮模型总结
        Map<String, Object> asst = new LinkedHashMap<>();
        asst.put("role", "assistant");
        asst.put("content", null);
        List<Map<String, Object>> tcs = new ArrayList<>();
        List<Map<String, Object>> toolMsgs = new ArrayList<>();
        for (ToolCall tc : resp.toolCalls()) {
          tcs.add(
            Map.of(
              "id",
              tc.id(),
              "type",
              "function",
              "function",
              Map.of("name", tc.name(), "arguments", tc.arguments())
            )
          );
          toolMsgs.add(
            Map.of("role", "tool", "tool_call_id", tc.id(), "content", executeTool(tc.name(), tc.arguments(), farmId))
          );
          executedTools.add(tc.name());
        }
        asst.put("tool_calls", tcs);
        messages.add(asst);
        messages.addAll(toolMsgs);
        continue;
      }
      if (resp.content() != null && !resp.content().isBlank()) {
        answer = resp.content().trim();
        break;
      }
      break;
    }
    String mode = "llm";
    if (answer == null) {
      answer = ruleAnswer(farmId, q);
      mode = "rule";
    }
    long elapsedMs = System.currentTimeMillis() - started;
    return Map.of(
      "answer",
      answer,
      "mode",
      mode,
      "sources",
      List.of("农场实时数据", "今日农事与现场问题"),
      "toolsCalled",
      java.util.List.copyOf(executedTools),
      "elapsedMs",
      elapsedMs
    );
  }

  @PostMapping("/insights")
  public Map<String, Object> insights(@RequestBody @Valid InsightInput input) {
    String ctx = contextText(input.farmId());
    var llmAnswer =
      llm
        .chat(
          "你是智禾农场平台的农场经营分析助手。基于【农场实时数据】生成今天的经营安排建议，"
            + "简明自然，像经验丰富的农场管理者，不超过 120 字，可以分 2–3 条要点，不得编造数据。",
          "请给出今天的经营安排建议。\n\n【农场实时数据】\n" + ctx
        );
    String answer;
    String mode;
    if (llmAnswer.isPresent()) {
      answer = llmAnswer.get();
      mode = "llm";
    } else {
      answer = ruleInsight(input.farmId());
      mode = "rule";
    }
    return Map.of("items", List.of(answer), "mode", mode);
  }

  // ---------- Function Calling：工具清单与执行 ----------

  private List<Map<String, Object>> toolDefinitions() {
    return List.of(
      tool(
        "get_farm_summary",
        "获取农场经营概况：总面积(亩)、累计产量(kg)、任务总数与已完成数、设备告警数、成员数、地块数、设备数"
      ),
      tool("get_farm_profile", "获取农场档案详情：农场名称、地址、土地性质、联系人、描述等档案字段"),
      tool("get_plots", "获取当前农场全部地块：名称、作物、面积(亩)"),
      tool("get_plantings", "获取当前农场种植计划：地块、作物、品种、面积、状态、起止日期"),
      tool(
        "get_production",
        "获取生产记录：地块、日期、产量(kg)，可传 limit 限制返回条数",
        "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"description\":\"返回条数上限，默认30\"}},\"additionalProperties\":false}"
      ),
      tool("get_devices", "获取监测设备状态：名称、类型、数据新鲜度(fresh/stale)、告警数、监测指标"),
      tool("get_pending_tasks", "获取待办或进行中的农事任务：标题、状态、到期日、地块、受阻原因"),
      tool("get_open_issues", "获取未解决的现场问题：标题、状态、严重度、类别、地块"),
      tool(
        "get_audit_logs",
        "获取最近操作日志：操作人、动作、时间，可传 limit 限制条数",
        "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"description\":\"返回条数上限，默认10\"}},\"additionalProperties\":false}"
      ),
      tool("get_crew", "获取农场成员完整名单：姓名、角色（含查看者）"),
      tool("get_farms", "获取当前租户下全部农场列表：id、名称、描述（管理者查看所有农场时用）"),
      tool("get_all_farm_summaries", "获取当前租户下所有农场的经营概况聚合对比：每个农场的面积、产量、任务完成情况、告警、成员、地块、设备数（管理者跨农场视角）"),
      tool("get_simulations", "获取经营模拟历史：方案的标题、运行时间、模型版本、id"),
      tool(
        "get_simulation_result",
        "获取某次经营模拟的完整结果：三种资源情景(资源正常/无人机缺位/人工补位)的产量对比、推荐方案、损失归因、费用等，需传模拟 id（来自 get_simulations）",
        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"模拟运行 id\"}},\"required\":[\"id\"],\"additionalProperties\":false}"
      ),
      tool("get_tenants", "获取平台启用租户列表（仅平台管理员可用）"),
      tool(
        "create_task",
        "安排农事任务（写操作，仅农场管理员）：创建一条待执行任务。参数：plotId(地块id)、title(任务标题)、taskType(SOWING播种|IRRIGATION灌溉|FERTILIZING施肥|HARVEST采收|INSPECTION巡查|PROTECTION植保)、dueDate(到期日 YYYY-MM-DD)、assigneeId(执行人id，先用get_crew查询)、method(UNCONFIRMED|DRONE|MANUAL|SERVICE)、note(说明)。仅在用户明确要求安排任务时调用",
        "{\"type\":\"object\",\"properties\":{\"plotId\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"taskType\":{\"type\":\"string\",\"enum\":[\"SOWING\",\"IRRIGATION\",\"FERTILIZING\",\"HARVEST\",\"INSPECTION\",\"PROTECTION\"]},\"dueDate\":{\"type\":\"string\"},\"assigneeId\":{\"type\":\"string\"},\"method\":{\"type\":\"string\",\"enum\":[\"UNCONFIRMED\",\"DRONE\",\"MANUAL\",\"SERVICE\"]},\"note\":{\"type\":\"string\"}},\"required\":[\"plotId\",\"title\",\"taskType\",\"dueDate\",\"assigneeId\",\"method\",\"note\"],\"additionalProperties\":false}"
      ),
      tool(
        "update_task_progress",
        "更新农事任务进度（写操作，仅管理员/操作员）：改变任务状态并记录说明。参数：taskId(任务id，先用get_pending_tasks查询)、status(RUNNING进行中|BLOCKED受阻|COMPLETED完成|CANCELLED取消)、note(说明)、method(UNCONFIRMED|DRONE|MANUAL|SERVICE)、actualAreaMu(实际作业面积，亩)。仅在用户明确要求更新任务时调用",
        "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"RUNNING\",\"BLOCKED\",\"COMPLETED\",\"CANCELLED\"]},\"note\":{\"type\":\"string\"},\"method\":{\"type\":\"string\",\"enum\":[\"UNCONFIRMED\",\"DRONE\",\"MANUAL\",\"SERVICE\"]},\"actualAreaMu\":{\"type\":\"number\"}},\"required\":[\"taskId\",\"status\",\"note\",\"method\",\"actualAreaMu\"],\"additionalProperties\":false}"
      ),
      tool(
        "report_issue",
        "上报现场问题（写操作，仅管理员/操作员）：记录地块现场的虫害/水情/设备等问题。参数：plotId(地块id)、category(PEST虫害|WATER水情|EQUIPMENT设备|OTHER其他)、severity(NORMAL一般|HIGH严重)、description(问题描述)。仅在用户明确要求上报问题时调用",
        "{\"type\":\"object\",\"properties\":{\"plotId\":{\"type\":\"string\"},\"category\":{\"type\":\"string\",\"enum\":[\"PEST\",\"WATER\",\"EQUIPMENT\",\"OTHER\"]},\"severity\":{\"type\":\"string\",\"enum\":[\"NORMAL\",\"HIGH\"]},\"description\":{\"type\":\"string\"}},\"required\":[\"plotId\",\"category\",\"severity\",\"description\"],\"additionalProperties\":false}"
      )
    );
  }

  private Map<String, Object> tool(String name, String desc) {
    return tool(name, desc, "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
  }

  private Map<String, Object> tool(String name, String desc, String paramsSchema) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("name", name);
    f.put("description", desc);
    try {
      f.put("parameters", json.readTree(paramsSchema));
    } catch (Exception e) {
      f.put("parameters", json.createObjectNode());
    }
    return Map.of("type", "function", "function", f);
  }

  /** 执行模型选择的工具，返回紧凑 JSON 文本（自动带租户隔离与脱敏）。 */
  private String executeTool(String name, String argsJson, String farmId) {
    try {
      JsonNode args = argsJson == null || argsJson.isBlank()
        ? json.createObjectNode()
        : json.readTree(argsJson);
      String result = switch (name) {
        case "get_farm_summary" -> farmSummary(farmId);
        case "get_farm_profile" -> farmProfile(farmId);
        case "get_plots" -> json(plotsOf(farmId));
        case "get_plantings" -> json(plantingsOf(farmId));
        case "get_production" -> json(productionOf(farmId, argInt(args, "limit", 30)));
        case "get_devices" -> json(devicesOf(farmId));
        case "get_pending_tasks" -> json(tasksOf(farmId));
        case "get_open_issues" -> json(issuesOf(farmId));
        case "get_audit_logs" -> json(auditLogsOf(argInt(args, "limit", 10)));
        case "get_crew" -> json(crewOf(farmId));
        case "get_farms" -> json(farmsOf());
        case "get_all_farm_summaries" -> allFarmSummaries();
        case "get_simulations" -> json(simulationsOf(farmId));
        case "get_simulation_result" -> simulationResultOf(argString(args, "id"));
        case "get_tenants" -> "PLATFORM_ADMIN".equals(Identity.current().role())
            ? json(tenantsOf())
            : "[]";
        case "create_task" -> createTask(args);
        case "update_task_progress" -> updateTaskProgress(args);
        case "report_issue" -> reportIssue(args);
        default -> "{\"error\":\"unknown tool\"}";
      };
      return result;
    } catch (Exception e) {
      return "{\"error\":\"tool failed\"}";
    }
  }

  private int argInt(JsonNode args, String key, int def) {
    JsonNode v = args.path(key);
    return v.isInt() ? v.asInt(def) : def;
  }

  private String argString(JsonNode args, String key) {
    JsonNode v = args.path(key);
    return v.isTextual() ? v.asText("") : "";
  }

  // ---------- 写操作工具（仅供用户明确要求时调用，权限受控、失败回显原因） ----------

  private String createTask(JsonNode args) {
    if (!"ADMIN".equals(Identity.current().role())) {
      return "{\"error\":\"仅农场管理员（ADMIN）可安排农事任务，当前角色无权限\"}";
    }
    try {
      FieldWorkService.PlanInput plan = new FieldWorkService.PlanInput(
        argString(args, "plotId"),
        argString(args, "title"),
        argString(args, "taskType"),
        java.time.LocalDate.parse(argString(args, "dueDate")),
        argString(args, "assigneeId"),
        argString(args, "method"),
        argString(args, "note")
      );
      return json(fieldWork.create(plan, null));
    } catch (Exception e) {
      return "{\"error\":\"创建任务失败：" + (e.getMessage() == null ? "参数不完整" : e.getMessage()) + "\"}";
    }
  }

  private String updateTaskProgress(JsonNode args) {
    String role = Identity.current().role();
    if (!"ADMIN".equals(role) && !"OPERATOR".equals(role)) {
      return "{\"error\":\"仅农场管理员或操作员可更新任务进度，当前角色无权限\"}";
    }
    try {
      FieldWorkService.ProgressInput progress = new FieldWorkService.ProgressInput(
        argString(args, "status"),
        argString(args, "note"),
        argString(args, "method"),
        args.path("actualAreaMu").isNumber()
          ? new java.math.BigDecimal(args.path("actualAreaMu").asText())
          : java.math.BigDecimal.ZERO
      );
      return json(fieldWork.progress(argString(args, "taskId"), progress));
    } catch (Exception e) {
      return "{\"error\":\"更新任务失败：" + (e.getMessage() == null ? "参数不完整" : e.getMessage()) + "\"}";
    }
  }

  private String reportIssue(JsonNode args) {
    String role = Identity.current().role();
    if (!"ADMIN".equals(role) && !"OPERATOR".equals(role)) {
      return "{\"error\":\"仅农场管理员或操作员可上报现场问题，当前角色无权限\"}";
    }
    try {
      FieldWorkService.IssueInput issue = new FieldWorkService.IssueInput(
        argString(args, "plotId"),
        argString(args, "category"),
        argString(args, "severity"),
        argString(args, "description")
      );
      return json(fieldWork.report(issue));
    } catch (Exception e) {
      return "{\"error\":\"上报问题失败：" + (e.getMessage() == null ? "参数不完整" : e.getMessage()) + "\"}";
    }
  }

  private String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      return "[]";
    }
  }

  private String farmSummary(String farmId) {
    JsonNode w = json.valueToTree(farmWorkspace.workspace(farmId, 30));
    JsonNode s = w.path("analytics").path("summary");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("farmName", w.path("farm").path("name").asText(""));
    m.put("areaMu", s.path("areaMu").asDouble(0));
    m.put("yieldKg", s.path("yieldKg").asDouble(0));
    m.put("tasks", s.path("tasks").asInt(0));
    m.put("completedTasks", s.path("completedTasks").asInt(0));
    m.put("alertCount", w.path("alerts").size());
    m.put("crewSize", crewSize());
    m.put("plotCount", w.path("plots").size());
    m.put("deviceCount", w.path("devices").size());
    return json(m);
  }

  private List<Map<String, Object>> plotsOf(String farmId) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : store.list("plots")) {
      if (!farmId.equals(String.valueOf(row.get("FARM_ID")))) continue;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", row.getOrDefault("ID", ""));
      item.put("name", row.getOrDefault("NAME", ""));
      item.put("crop", row.getOrDefault("CROP", ""));
      item.put("areaMu", num(row.get("AREA_MU")));
      out.add(item);
    }
    return out;
  }

  private List<Map<String, Object>> plantingsOf(String farmId) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : store.list("plantings")) {
      String plotName = plotName(farmId, String.valueOf(row.getOrDefault("PLOT_ID", "")));
      if (plotName.isEmpty()) continue;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("plot", plotName);
      item.put("crop", row.getOrDefault("CROP", ""));
      item.put("variety", row.getOrDefault("VARIETY", ""));
      item.put("areaMu", num(row.get("AREA_MU")));
      item.put("status", row.getOrDefault("STATUS", ""));
      item.put("startDate", row.getOrDefault("START_DATE", ""));
      item.put("endDate", row.getOrDefault("END_DATE", ""));
      out.add(item);
    }
    return out;
  }

  private List<Map<String, Object>> productionOf(String farmId, int limit) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : store.list("production")) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("plot", plotName(farmId, String.valueOf(row.getOrDefault("PLOT_ID", ""))));
      item.put("date", row.getOrDefault("RECORD_DATE", ""));
      item.put("yieldKg", num(row.get("YIELD_KG")));
      out.add(item);
    }
    return out.size() > limit ? out.subList(0, limit) : out;
  }

  private List<Map<String, Object>> devicesOf(String farmId) {
    JsonNode w = json.valueToTree(farmWorkspace.workspace(farmId, 30));
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode d : w.path("devices")) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", d.path("name").asText(""));
      row.put("type", d.path("typeName").asText(d.path("type").asText("")));
      row.put("freshness", d.path("freshness").asText(""));
      row.put("alertCount", d.path("alertCount").asInt(0));
      row.put("metric", d.path("metric").asText(""));
      row.put("unit", d.path("unit").asText(""));
      out.add(row);
    }
    return out;
  }

  private List<Map<String, Object>> tasksOf(String farmId) {
    JsonNode o = json.valueToTree(fieldWork.overview(farmId));
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode t : o.path("tasks")) {
      // overview() 返回数据库列名（大写）
      String status = t.path("STATUS").asText("");
      if (!"PENDING".equals(status) && !"RUNNING".equals(status)) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", t.path("ID").asText(""));
      row.put("title", t.path("TITLE").asText(""));
      row.put("status", status);
      row.put("dueDate", t.path("DUE_DATE").asText(""));
      row.put("plotName", t.path("PLOT_NAME").asText(""));
      row.put("blockedReason", t.path("BLOCKED_REASON").asText(""));
      out.add(row);
    }
    return out;
  }

  private List<Map<String, Object>> issuesOf(String farmId) {
    JsonNode o = json.valueToTree(fieldWork.overview(farmId));
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode i : o.path("issues")) {
      String status = i.path("STATUS").asText("");
      if ("RESOLVED".equals(status)) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("title", i.path("TITLE").asText(""));
      row.put("status", status);
      row.put("severity", i.path("SEVERITY").asText(""));
      row.put("category", i.path("CATEGORY").asText(""));
      row.put("plotName", i.path("PLOT_NAME").asText(""));
      out.add(row);
    }
    return out;
  }

  private List<Map<String, Object>> auditLogsOf(int limit) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : store.list("audit_events")) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("actor", row.getOrDefault("ACTOR", ""));
      item.put("action", row.getOrDefault("ACTION", ""));
      item.put("time", row.getOrDefault("OCCURRED_AT", ""));
      out.add(item);
    }
    return out.size() > limit ? out.subList(0, limit) : out;
  }

  private List<Map<String, Object>> crewOf(String farmId) {
    // 完整成员名单（含 VIEWER 查看者），不依赖任务分配名单（后者仅含 ADMIN/OPERATOR）
    JsonNode arr = json.valueToTree(
      store
        .db()
        .queryForList(
          "SELECT id,display_name,role FROM members WHERE tenant_id=? AND enabled=TRUE ORDER BY display_name",
          Identity.tenant()
        )
    );
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode m : arr) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", m.path("ID").asText(""));
      row.put("name", m.path("DISPLAY_NAME").asText(""));
      row.put("role", m.path("ROLE").asText(""));
      out.add(row);
    }
    return out;
  }

  private int crewSize() {
    var rows = store
      .db()
      .queryForList(
        "SELECT COUNT(*) AS c FROM members WHERE tenant_id=? AND enabled=TRUE",
        Identity.tenant()
      );
    if (rows.isEmpty()) return 0;
    Object c = rows.get(0).get("C");
    return c instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(c));
  }

  private List<Map<String, Object>> farmsOf() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> f : store.list("farms")) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", f.getOrDefault("ID", ""));
      row.put("name", f.getOrDefault("NAME", ""));
      row.put("description", f.getOrDefault("DESCRIPTION", ""));
      out.add(row);
    }
    return out;
  }

  /** 聚合当前租户下所有农场的经营概况，供管理者跨农场对比。 */
  private String allFarmSummaries() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> f : farmsOf()) {
      String id = String.valueOf(f.get("id"));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("farmId", id);
      row.put("farmName", f.getOrDefault("name", ""));
      try {
        JsonNode w = json.valueToTree(farmWorkspace.workspace(id, 30));
        JsonNode s = w.path("analytics").path("summary");
        JsonNode o = json.valueToTree(fieldWork.overview(id));
        row.put("areaMu", s.path("areaMu").asDouble(0));
        row.put("yieldKg", s.path("yieldKg").asDouble(0));
        row.put("tasks", s.path("tasks").asInt(0));
        row.put("completedTasks", s.path("completedTasks").asInt(0));
        row.put("alertCount", w.path("alerts").size());
        row.put("crewSize", crewSize());
        row.put("plotCount", w.path("plots").size());
        row.put("deviceCount", w.path("devices").size());
      } catch (Exception e) {
        row.put("error", "查询失败");
      }
      out.add(row);
    }
    return json(out);
  }

  private String farmProfile(String farmId) {
    try {
      return json.writeValueAsString(store.get("farms", farmId));
    } catch (Exception e) {
      return "{\"error\":\"farm profile unavailable\"}";
    }
  }

  private List<Map<String, Object>> simulationsOf(String farmId) {
    return simulations.list(farmId);
  }

  private String simulationResultOf(String id) {
    if (id == null || id.isBlank()) return "{\"error\":\"missing simulation id\"}";
    try {
      return json.writeValueAsString(simulations.get(id));
    } catch (Exception e) {
      return "{\"error\":\"simulation not found or unavailable\"}";
    }
  }

  private List<Map<String, Object>> tenantsOf() {
    return store
      .db()
      .queryForList(
        "SELECT name AS \"name\",code AS \"code\",enabled AS \"enabled\" FROM tenants WHERE enabled=TRUE ORDER BY code"
      );
  }

  // ---------- 非工具路径：insights 上下文与规则兜底 ----------

  private String contextText(String farmId) {
    Map<String, Object> ws = farmWorkspace.workspace(farmId, 30);
    JsonNode w = json.valueToTree(ws);
    JsonNode s = w.path("analytics").path("summary");
    JsonNode o = json.valueToTree(fieldWork.overview(farmId));
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("farmName", w.path("farm").path("name").asText(""));
    ctx.put("today", o.path("today").asText(""));
    ctx.put(
      "summary",
      Map.of(
        "areaMu",
        s.path("areaMu").asDouble(0),
        "yieldKg",
        s.path("yieldKg").asDouble(0),
        "tasks",
        s.path("tasks").asInt(0),
        "completedTasks",
        s.path("completedTasks").asInt(0)
      )
    );
    ctx.put("plots", plotsOf(farmId));
    ctx.put("devices", devicesOf(farmId));
    ctx.put("alertCount", w.path("alerts").size());
    ctx.put("pendingTasks", tasksOf(farmId));
    ctx.put("openIssues", issuesOf(farmId));
    ctx.put("crew", crewOf(farmId));
    ctx.put("auditLog", auditLogsOf(10));
    try {
      return json.writeValueAsString(ctx);
    } catch (Exception e) {
      return "{}";
    }
  }

  private String ruleAnswer(String farmId, String question) {
    String q = question == null ? "" : question;
    String name = farmName(farmId);
    if (q.contains("地块") || q.contains("田")) {
      List<Map<String, Object>> rows = plotsOf(farmId);
      if (rows.isEmpty()) return name + " 暂无地块数据。";
      double total = rows.stream().mapToDouble(r -> (double) r.get("areaMu")).sum();
      StringBuilder sb = new StringBuilder(name + " 共 " + rows.size() + " 块地、合计 " + fmt(total) + " 亩：");
      for (Map<String, Object> r : rows) {
        sb.append(r.get("name")).append("（").append(r.get("crop")).append(" ").append(fmt((double) r.get("areaMu"))).append(" 亩）");
      }
      return sb.toString();
    }
    if (q.contains("种植") || q.contains("计划") || q.contains("播种")) {
      List<Map<String, Object>> rows = plantingsOf(farmId);
      if (rows.isEmpty()) return name + " 暂无种植计划，可先添加。";
      StringBuilder sb = new StringBuilder(name + " 当前种植计划：");
      for (Map<String, Object> r : rows) {
        sb.append(r.get("crop")).append("（").append(r.get("variety")).append("·").append(r.get("status")).append("）");
      }
      return sb.toString();
    }
    if (q.contains("产量") || q.contains("收成") || q.contains("收获")) {
      JsonNode s = json
        .valueToTree(farmWorkspace.workspace(farmId, 30))
        .path("analytics")
        .path("summary");
      double yield = s.path("yieldKg").asDouble(0);
      int tasksTotal = s.path("tasks").asInt(0);
      int done = s.path("completedTasks").asInt(0);
      int rate = tasksTotal > 0 ? done * 100 / tasksTotal : 0;
      return name + " 近 30 天登记产量 " + fmt(yield) + " kg，农事完成进度 " + rate + "%（" + done + "/" + tasksTotal + " 项）。";
    }
    if (q.contains("日志") || q.contains("记录")) {
      List<Map<String, Object>> logs = auditLogsOf(5);
      if (logs.isEmpty()) return name + " 暂无操作日志。";
      StringBuilder sb = new StringBuilder(name + " 最近操作：");
      for (Map<String, Object> r : logs) {
        sb.append(r.get("actor")).append(" ").append(r.get("action")).append("；");
      }
      return sb.toString();
    }
    if (q.contains("成员") || q.contains("人员") || q.contains("权限")) {
      List<Map<String, Object>> crew = crewOf(farmId);
      if (crew.isEmpty()) return name + " 暂无成员信息。";
      StringBuilder sb = new StringBuilder(name + " 成员：");
      for (Map<String, Object> m : crew) {
        sb.append(m.get("name")).append("（").append(m.get("role")).append("）");
      }
      return sb.toString();
    }
    Map<String, Object> ctx = baseContext(farmId);
    List<Map<String, Object>> tasks = casts(ctx.get("pendingTasks"));
    int alerts = (Integer) ctx.get("alertCount");
    List<Map<String, Object>> issues = casts(ctx.get("openIssues"));
    if (q.contains("灌溉") || q.contains("浇水") || q.contains("水")) {
      List<Map<String, Object>> devices = casts(ctx.get("devices"));
      long fresh = devices.stream().filter(d -> "FRESH".equals(d.get("freshness"))).count();
      long alertDev = devices.stream().filter(d -> (int) d.get("alertCount") > 0).count();
      return (
        name +
        " 当前共 " +
        devices.size() +
        " 台监测设备，" +
        fresh +
        " 台正常上报" +
        (alertDev > 0 ? "，" + alertDev + " 台有告警待处理" : "") +
        "。建议优先巡检告警设备对应地块，再按地块墒情安排灌溉。"
      );
    }
    if (q.contains("任务") || q.contains("待办") || q.contains("紧急") || q.contains("安排")) {
      if (tasks.isEmpty()) return "当前没有进行中的待办农事，可安排常规巡田与记录。";
      long overdue = tasks
        .stream()
        .filter(t -> t.get("dueDate") != null && t.get("dueDate").toString().compareTo(String.valueOf(ctx.get("today"))) < 0)
        .count();
      Map<String, Object> first = tasks.get(0);
      return (
        name +
        " 现有 " +
        tasks.size() +
        " 项待办农事" +
        (overdue > 0 ? "，其中 " + overdue + " 项已逾期（最早 " + first.get("dueDate") + "）" : "") +
        "。建议优先处理逾期与受阻任务，再按到期日顺序推进。"
      );
    }
    if (q.contains("风险") || q.contains("告警") || q.contains("问题") || q.contains("异常")) {
      if (alerts == 0 && issues.isEmpty()) return "当前无设备告警、无未解决现场问题，经营状态正常。";
      StringBuilder sb = new StringBuilder(name + " 当前有 " + alerts + " 项设备告警");
      if (!issues.isEmpty()) sb.append("、").append(issues.size()).append(" 项未解决现场问题");
      sb.append("。建议优先处理告警设备对应地块，再跟进现场问题。");
      return sb.toString();
    }
    return (
      name +
      " 当前经营状态：" +
      tasks.size() +
      " 项待办、" +
      alerts +
      " 项设备告警、" +
      issues.size() +
      " 项现场问题。" +
      (alerts > 0 ? "请优先处理告警与逾期事项。" : "可按计划推进常规农事。")
    );
  }

  private String ruleInsight(String farmId) {
    Map<String, Object> ctx = baseContext(farmId);
    List<Map<String, Object>> tasks = casts(ctx.get("pendingTasks"));
    int alerts = (Integer) ctx.get("alertCount");
    List<Map<String, Object>> issues = casts(ctx.get("openIssues"));
    List<String> tips = new ArrayList<>();
    long overdue = tasks
      .stream()
      .filter(t -> t.get("dueDate") != null && t.get("dueDate").toString().compareTo(String.valueOf(ctx.get("today"))) < 0)
      .count();
    if (overdue > 0) tips.add(overdue + " 项农事已逾期，建议今天优先安排处理");
    if (!tasks.isEmpty() && tasks.stream().anyMatch(t -> t.get("blockedReason") != null && !t.get("blockedReason").toString().isBlank()))
      tips.add("部分任务资源受阻，需要协调人员或设备补位");
    if (alerts > 0) tips.add(alerts + " 项设备告警待处理，建议尽快确认");
    if (!issues.isEmpty()) tips.add(issues.size() + " 项现场问题待跟进");
    if (tips.isEmpty()) tips.add("今日无紧急事项，可按计划推进常规农事与巡田检查");
    return String.join("；", tips) + "。";
  }

  private Map<String, Object> baseContext(String farmId) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    JsonNode w = json.valueToTree(farmWorkspace.workspace(farmId, 30));
    JsonNode o = json.valueToTree(fieldWork.overview(farmId));
    ctx.put("today", o.path("today").asText(""));
    ctx.put("pendingTasks", tasksOf(farmId));
    ctx.put("openIssues", issuesOf(farmId));
    ctx.put("devices", devicesOf(farmId));
    ctx.put("alertCount", w.path("alerts").size());
    return ctx;
  }

  private String plotName(String farmId, String plotId) {
    for (Map<String, Object> p : store.list("plots")) {
      if (plotId.equals(String.valueOf(p.get("ID"))) && farmId.equals(String.valueOf(p.get("FARM_ID")))) {
        return String.valueOf(p.getOrDefault("NAME", ""));
      }
    }
    return "";
  }

  private String farmName(String farmId) {
    return json.valueToTree(farmWorkspace.workspace(farmId, 30)).path("farm").path("name").asText("");
  }

  private double num(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0d;
  }

  private String fmt(double v) {
    return String.format("%,.1f", v);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> casts(Object value) {
    return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
  }
}
