package app.zhinong.ai;

import app.zhinong.business.FarmClock;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import app.zhinong.workspace.TelemetryService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded, read-only evidence tools. Farm scope is supplied by the authenticated conversation. */
@Component
public class FarmDecisionSupport {
  private final Store store;
  private final TelemetryService telemetry;
  private final FarmBriefingService briefings;
  private final FarmClock clock;

  public FarmDecisionSupport(Store store, TelemetryService telemetry, FarmBriefingService briefings, FarmClock clock) {
    this.store = store;
    this.telemetry = telemetry;
    this.briefings = briefings;
    this.clock = clock;
  }

  public Map<String, Object> alerts(String farmId) {
    store.get("farms", farmId);
    var rows = telemetry.alerts(farmId, null).stream()
      .filter(row -> !"RESOLVED".equals(row.get("status"))).toList();
    var evidence = rows.stream().limit(30).map(row -> {
      var item = new LinkedHashMap<String, Object>();
      for (String key : List.of("deviceName", "plotName", "metric", "message", "openedAt", "updatedAt",
          "recommendation", "evidence", "thresholdSeconds", "lastReceivedAt", "issueId")) {
        if (row.get(key) != null) item.put(key, row.get(key));
      }
      item.put("status", "ACKNOWLEDGED".equals(row.get("status")) ? "已知晓，待复核" : "待处理");
      item.put("kind", switch (String.valueOf(row.get("kind"))) {
        case "OFFLINE" -> "设备离线";
        case "DATA_STALE" -> "采样数据过期";
        default -> "监测值异常";
      });
      item.put("severity", "HIGH".equals(row.get("severity")) ? "优先处理" : "常规跟进");
      return item;
    }).toList();
    long count = store.db().queryForObject("""
      SELECT COUNT(*) FROM device_alerts a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
      WHERE a.tenant_id=? AND d.farm_id=? AND a.status<>'RESOLVED'
      """, Long.class, Identity.tenant(), farmId);
    return Map.of("activeAlertCount", count, "alerts", evidence, "displayLimit", 30,
      "truncated", count > evidence.size(),
      "notice", "告警来自规则检查。离线或过期读数不可当作当前环境；先恢复采集并现场核验，再决定生产调整。确认知晓不等于已修复。设备指令和派工均需人工确认。");
  }

  public Map<String, Object> productionComparison(String farmId) {
    store.get("farms", farmId);
    String tenant = Identity.tenant();
    // Compare two complete 30-day windows, excluding the incomplete current day.
    LocalDate end = clock.today();
    var result = new LinkedHashMap<String, Object>();
    result.put("currentPeriod", productionPeriod(tenant, farmId, end.minusDays(30), end));
    result.put("previousPeriod", productionPeriod(tenant, farmId, end.minusDays(60), end.minusDays(30)));
    result.put("byPlot", store.db().queryForList("""
      SELECT p.name AS "plotName",COUNT(*) AS "records",SUM(r.yield_kg) AS "yieldKg"
      FROM production r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
      WHERE r.tenant_id=? AND p.farm_id=? AND r.record_date>=? AND r.record_date<?
      GROUP BY p.id,p.name ORDER BY p.name
      """, tenant, farmId, end.minusDays(30), end));
    result.put("notice", "统计仅为已登记收获量，不是单产、收入或利润。无记录不等于零产出；种植面积、作物及收获批次可能不同，不能据此推断经营效率或增产因果。日期上限不包含当天。");
    return result;
  }

  private Map<String, Object> productionPeriod(String tenant, String farmId, LocalDate start, LocalDate end) {
    var row = store.db().queryForMap("""
      SELECT COUNT(*) AS "records",COALESCE(SUM(r.yield_kg),0) AS "yieldKg"
      FROM production r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
      WHERE r.tenant_id=? AND p.farm_id=? AND r.record_date>=? AND r.record_date<?
      """, tenant, farmId, start, end);
    row.put("startDate", start);
    row.put("endDateExclusive", end);
    return row;
  }

  public Map<String, Object> briefing(String farmId) {
    var report = briefings.peekToday(farmId);
    if (!Boolean.TRUE.equals(report.get("available"))) return report;
    var result = new LinkedHashMap<String, Object>();
    for (String key : List.of("available", "farmName", "reportDate", "generatedAt", "updatedAt",
        "summary", "aiSummary", "metrics", "conditions", "evidence", "dataGaps", "notice")) {
      if (report.get(key) != null) result.put(key, report.get(key));
    }
    result.put("source", "LLM".equals(report.get("source")) ? "事实统计与规则建议，另附模型解读" : "事实统计与规则建议");
    result.put("status", "READY".equals(report.get("status")) ? "已保存" : "事实已保存，模型解读尚未完成");
    if (report.get("priorities") instanceof List<?> priorities) {
      result.put("priorities", priorities.stream().filter(Map.class::isInstance).map(value -> {
        var priority = (Map<?, ?>) value;
        var item = new LinkedHashMap<String, Object>();
        for (String key : List.of("title", "recommendation", "evidence")) item.put(key, priority.get(key));
        item.put("priority", switch (String.valueOf(priority.get("priority"))) {
          case "HIGH" -> "优先处理";
          case "MEDIUM" -> "今日跟进";
          default -> "持续关注";
        });
        return item;
      }).toList());
    }
    return result;
  }
}
