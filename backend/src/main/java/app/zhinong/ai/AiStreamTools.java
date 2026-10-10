package app.zhinong.ai;

import app.zhinong.fieldwork.FieldWorkService;
import app.zhinong.workspace.FarmWorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 流式对话工具循环（Task 6）可调用的只读工具：复用 /api/ai/ask（{@link AiController}）已有的查询
 * 模式，但只保留四个安全、只读、按租户+农场限定的工具。farmId 永远取自已鉴权的会话记录
 * （{@code AiStreamService} 里的 {@code prep.farmId()}），绝不使用模型给出的参数——模型的 tool_calls
 * 参数在这里被完全忽略，避免把未经校验的输入传进查询。
 *
 * 工具名、人类可读标签和结果摘要都在这里走固定白名单（{@link #LABELS}、{@link #resultSummary}），
 * {@code AiStreamService} 不会把原始工具参数、SQL 或完整查询结果写入活动摘要或持久化记录。
 */
@Component
public class AiStreamTools {

  /** 工具名 -> 中文标签白名单。不在此表中的名字视为未知工具：只生成错误活动，不执行任何查询。 */
  static final Map<String, String> LABELS = Map.of(
    "get_farm_summary", "查询农场经营概况",
    "get_pending_tasks", "查询待办任务",
    "get_open_issues", "查询现场问题",
    "get_weather_conditions", "查询天气与四情"
  );

  /** 任务状态枚举 -> 中文标签，与 frontend/src/catalog.js 的 labels 保持一致（阶段 E 验收发现模型
   * 直接把 PENDING/ASSIGNED/HIGH 等原始枚举码写进了回答正文）。工具结果绝不把英文枚举码原样交给
   * 模型——模型只会照抄输入，不会自己翻译。 */
  private static final Map<String, String> TASK_STATUS_LABELS = Map.of(
    "PENDING", "待执行",
    "RUNNING", "执行中",
    "COMPLETED", "已完成",
    "CANCELLED", "已取消"
  );

  /** 现场问题状态枚举 -> 中文标签，与 frontend/src/fieldwork/DailyFarm.vue 的 word() 本地映射一致。 */
  private static final Map<String, String> ISSUE_STATUS_LABELS = Map.of(
    "OPEN", "待安排",
    "ASSIGNED", "处理中",
    "RESOLVED", "已复核关闭"
  );

  /** 严重度枚举 -> 中文标签，与 DailyFarm.vue 的 severityOptions 一致。 */
  private static final Map<String, String> SEVERITY_LABELS = Map.of(
    "HIGH", "优先处理",
    "NORMAL", "常规跟进"
  );

  private static String label(Map<String, String> table, String code) {
    if (code == null || code.isBlank()) return code;
    return table.getOrDefault(code, code);
  }

  private final FarmWorkspaceService farmWorkspace;
  private final FieldWorkService fieldWork;
  private final AgronomyAnalysis analysis;
  private final ObjectMapper json;

  public AiStreamTools(FarmWorkspaceService farmWorkspace, FieldWorkService fieldWork, AgronomyAnalysis analysis, ObjectMapper json) {
    this.farmWorkspace = farmWorkspace;
    this.fieldWork = fieldWork;
    this.analysis = analysis;
    this.json = json;
  }

  /** OpenAI 兼容 tools 定义，和 {@link AiController#toolDefinitions()} 使用同样的 JSON Schema 写法。 */
  protected List<Map<String, Object>> definitions() {
    return List.of(
      tool("get_farm_summary", "获取农场经营概况：总面积(亩)、累计产量(kg)、任务总数与已完成数、设备告警数"),
      tool("get_pending_tasks", "获取待办或进行中的农事任务：标题、状态、到期日、地块"),
      tool("get_open_issues", "获取未解决的现场问题：标题、状态、严重度、地块"),
      tool("get_weather_conditions", "获取近7天天气与苗情/墒情/虫情/灾情四情摘要")
    );
  }

  private Map<String, Object> tool(String name, String desc) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("name", name);
    f.put("description", desc);
    try {
      f.put("parameters", json.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    } catch (Exception e) {
      f.put("parameters", json.createObjectNode());
    }
    return Map.of("type", "function", "function", f);
  }

  /** 执行一个白名单工具，farmId 来自已鉴权的会话记录。未知工具名直接抛出，调用方据此生成错误活动。 */
  protected Object run(String name, String farmId) {
    return switch (name) {
      case "get_farm_summary" -> farmSummary(farmId);
      case "get_pending_tasks" -> pendingTasks(farmId);
      case "get_open_issues" -> openIssues(farmId);
      case "get_weather_conditions" -> weatherConditions(farmId);
      default -> throw new IllegalArgumentException("unknown tool: " + name);
    };
  }

  /** 安全的中文结果摘要：只描述数量和类别，不回传任何原始字段。 */
  protected String resultSummary(String name, Object result) {
    return switch (name) {
      case "get_pending_tasks" -> {
        int n = ((List<?>) result).size();
        yield n == 0 ? "暂无待办任务" : "读取到 " + n + " 条待办任务";
      }
      case "get_open_issues" -> {
        int n = ((List<?>) result).size();
        yield n == 0 ? "暂无未处理问题" : "读取到 " + n + " 条未处理问题";
      }
      case "get_farm_summary" -> "已读取农场经营概况";
      case "get_weather_conditions" -> "已读取近7天天气与四情摘要";
      default -> "已完成查询";
    };
  }

  protected String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private Map<String, Object> farmSummary(String farmId) {
    JsonNode w = json.valueToTree(farmWorkspace.workspace(farmId, 30));
    JsonNode s = w.path("analytics").path("summary");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("areaMu", s.path("areaMu").asDouble(0));
    m.put("yieldKg", s.path("yieldKg").asDouble(0));
    m.put("tasks", s.path("tasks").asInt(0));
    m.put("completedTasks", s.path("completedTasks").asInt(0));
    m.put("alertCount", w.path("alerts").size());
    return m;
  }

  private List<Map<String, Object>> pendingTasks(String farmId) {
    JsonNode o = json.valueToTree(fieldWork.overview(farmId));
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode t : o.path("tasks")) {
      String status = t.path("STATUS").asText("");
      if (!"PENDING".equals(status) && !"RUNNING".equals(status)) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("title", t.path("TITLE").asText(""));
      row.put("status", label(TASK_STATUS_LABELS, status));
      row.put("dueDate", t.path("DUE_DATE").asText(""));
      row.put("plotName", t.path("PLOT_NAME").asText(""));
      out.add(row);
    }
    return out;
  }

  private List<Map<String, Object>> openIssues(String farmId) {
    JsonNode o = json.valueToTree(fieldWork.overview(farmId));
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode i : o.path("issues")) {
      String status = i.path("STATUS").asText("");
      if ("RESOLVED".equals(status)) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("title", i.path("TITLE").asText(""));
      row.put("status", label(ISSUE_STATUS_LABELS, status));
      row.put("severity", label(SEVERITY_LABELS, i.path("SEVERITY").asText("")));
      row.put("plotName", i.path("PLOT_NAME").asText(""));
      out.add(row);
    }
    return out;
  }

  private Map<String, Object> weatherConditions(String farmId) {
    Map<String, Object> report = analysis.report(farmId);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("conditions", report.get("conditions"));
    m.put("cautions", report.get("cautions"));
    m.put("weatherNotice", report.get("weatherNotice"));
    return m;
  }
}
