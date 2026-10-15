package app.zhinong.ai;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves the small, server-owned page context sent to the model. The browser only
 * supplies a page/focus intent; it never supplies business data or a field selector.
 */
@Service
public class AiContextSnapshotResolver {
  private static final Map<String, String> PAGE_LABELS = Map.ofEntries(
    Map.entry("daily", "今日农场"), Map.entry("operations", "运行概览"),
    Map.entry("dashboard", "农场概览"), Map.entry("cameras", "田间实景"),
    Map.entry("devices", "设备台账"), Map.entry("plots", "地块管理"),
    Map.entry("plantings", "种植计划"), Map.entry("tasks", "农事任务"),
    Map.entry("production", "生产记录"), Map.entry("ai", "农场 AI 助手"),
    Map.entry("display", "展示大屏"), Map.entry("simulation", "经营模拟")
  );
  private final Store store;
  private final JdbcTemplate db;

  public AiContextSnapshotResolver(Store store, JdbcTemplate db) {
    this.store = store;
    this.db = db;
  }

  /** Returns null when no page intent was supplied, preserving old clients. */
  public Snapshot resolve(String farmId, String pageId, String focusType, String focusId) {
    if (pageId == null && focusType == null && focusId == null) return null;
    if (pageId == null || (focusType == null) != (focusId == null))
      throw new ApiException(400, "页面资料范围不完整");
    if (!PAGE_LABELS.containsKey(pageId)) throw new ApiException(400, "不支持的页面资料范围");
    var farm = db.queryForList("SELECT id,name FROM farms WHERE tenant_id=? AND id=?", Identity.tenant(), farmId);
    if (farm.isEmpty()) throw ApiException.missing();

    var facts = new LinkedHashMap<String, Object>();
    facts.put("page", PAGE_LABELS.get(pageId));
    facts.put("farm", farm.getFirst().get("NAME"));
    if (focusId != null) facts.put("focus", resolveFocus(farmId, pageId, focusType, focusId));
    if ("daily".equals(pageId) || "operations".equals(pageId)) {
      var counts = db.queryForMap("""
        SELECT
          (SELECT COUNT(*) FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
            WHERE t.tenant_id=? AND p.farm_id=? AND t.status IN ('PENDING','RUNNING')) AS openTasks,
          (SELECT COUNT(*) FROM field_issues i JOIN plots p ON p.tenant_id=i.tenant_id AND p.id=i.plot_id
            WHERE i.tenant_id=? AND p.farm_id=? AND i.status<>'RESOLVED') AS openIssues
        """, Identity.tenant(), farmId, Identity.tenant(), farmId);
      facts.put("openTasks", counts.get("OPENTASKS"));
      facts.put("openIssues", counts.get("OPENISSUES"));
    }
    if ("display".equals(pageId) || "simulation".equals(pageId)) {
      facts.remove("farm");
    }
    return new Snapshot("参考页面：" + PAGE_LABELS.get(pageId), facts, sourceSummary(facts));
  }

  private Map<String, Object> resolveFocus(String farmId, String pageId, String focusType, String focusId) {
    if ("devices".equals(pageId) || "cameras".equals(pageId)) {
      if (!"device".equals(focusType)) throw new ApiException(400, "当前页面不支持该对象");
      var rows = db.queryForList("SELECT id,name,metric,unit,adapter FROM devices WHERE tenant_id=? AND farm_id=? AND id=?", Identity.tenant(), farmId, focusId);
      if (rows.isEmpty()) throw ApiException.missing();
      return rows.getFirst();
    }
    if ("plots".equals(pageId) || "plantings".equals(pageId) || "tasks".equals(pageId) || "production".equals(pageId)) {
      if (!"plot".equals(focusType)) throw new ApiException(400, "当前页面不支持该对象");
      var rows = db.queryForList("SELECT id,name,crop,area_mu AS areaMu FROM plots WHERE tenant_id=? AND farm_id=? AND id=?", Identity.tenant(), farmId, focusId);
      if (rows.isEmpty()) throw ApiException.missing();
      return rows.getFirst();
    }
    throw new ApiException(400, "当前页面不支持对象定位");
  }

  /**
   * 来源记录摘要：随回答持久化、在小窗和完整页回答下方展示，所以只写对象名称和计数，
   * 不写读数、参数或任何原始快照字段。
   */
  private static String sourceSummary(Map<String, Object> facts) {
    var parts = new ArrayList<String>();
    if (facts.get("focus") instanceof Map<?, ?> focus && focus.get("NAME") != null) parts.add("已定位「" + focus.get("NAME") + "」");
    if (facts.containsKey("openTasks")) parts.add("未完成任务 " + facts.get("openTasks") + " 项");
    if (facts.containsKey("openIssues")) parts.add("待处理问题 " + facts.get("openIssues") + " 项");
    String text = parts.isEmpty() ? "已读取当前页面的限量资料" : String.join("，", parts);
    return text.length() > 200 ? text.substring(0, 200) : text;
  }

  /** label/summary 是可展示、可持久化的来源记录；facts 只进入本次模型请求，不落库。 */
  public record Snapshot(String label, Map<String, Object> facts, String summary) {}
}
