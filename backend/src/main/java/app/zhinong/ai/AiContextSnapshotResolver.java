package app.zhinong.ai;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
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
    String summary = focusId == null ? "已读取当前页面的限量资料" : "已读取当前页面及所选对象的限量资料";
    return new Snapshot(facts, summary);
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

  public record Snapshot(Map<String, Object> facts, String summary) {}
}
