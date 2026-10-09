package app.zhinong.workspace;

import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farms/{farmId}/research-data")
public class ResearchDataController {
  private final Store store;
  public ResearchDataController(Store store) { this.store=store; }
  @GetMapping public Object report(@PathVariable String farmId) {
    store.get("farms",farmId);
    String tenant=Identity.tenant(); var db=store.db();
    var manifests=db.queryForList("SELECT version AS \"version\",history_start AS \"historyStart\",as_of_date AS \"asOfDate\" FROM demo_research_farms WHERE tenant_id=? AND farm_id=?",tenant,farmId);
    if(manifests.isEmpty()) return Map.of("available",false,"note","尚未启用两年研究场景；已有用户经营记录会被保留。");
    var counts=new LinkedHashMap<String,Object>();
    for(String table:List.of("plantings","farm_tasks","production","field_issues")) counts.put(table,
      db.queryForObject("SELECT COUNT(*) FROM "+table+" r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id WHERE r.tenant_id=? AND p.farm_id=?",Long.class,tenant,farmId));
    var checks=new LinkedHashMap<String,Object>();
    checks.put("overlappingSeasons",db.queryForObject("""
      SELECT COUNT(*) FROM plantings a JOIN plantings b ON b.tenant_id=a.tenant_id AND b.plot_id=a.plot_id AND a.id<b.id
      JOIN plots p ON p.tenant_id=a.tenant_id AND p.id=a.plot_id
      WHERE a.tenant_id=? AND p.farm_id=? AND a.start_date<=b.end_date AND b.start_date<=a.end_date
      """,Long.class,tenant,farmId));
    checks.put("harvestLinkProblems",db.queryForObject("""
      SELECT COUNT(*) FROM production r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
      LEFT JOIN production_lineage l ON l.tenant_id=r.tenant_id AND l.production_id=r.id
      LEFT JOIN plantings s ON s.tenant_id=l.tenant_id AND s.id=l.planting_id
      LEFT JOIN farm_tasks t ON t.tenant_id=l.tenant_id AND t.id=l.task_id
      LEFT JOIN task_fieldwork w ON w.tenant_id=t.tenant_id AND w.task_id=t.id
      WHERE r.tenant_id=? AND p.farm_id=? AND (l.production_id IS NULL OR w.task_id IS NULL OR s.plot_id<>r.plot_id OR t.plot_id<>r.plot_id
        OR s.crop<>p.crop OR s.area_mu>p.area_mu OR r.record_date<s.start_date OR r.record_date>s.end_date
        OR t.task_type<>'HARVEST' OR t.status<>'COMPLETED' OR t.due_date<>r.record_date OR w.actual_area_mu<>s.area_mu)
      """,Long.class,tenant,farmId));
    checks.put("futureHarvests",db.queryForObject("SELECT COUNT(*) FROM production r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id WHERE r.tenant_id=? AND p.farm_id=? AND r.record_date>?",Long.class,tenant,farmId,LocalDate.now()));
    checks.put("decreasingCounters",db.queryForObject("""
      SELECT COUNT(*) FROM (SELECT r.measured_value,
        LAG(r.measured_value) OVER(PARTITION BY r.tenant_id,r.device_id,r.metric ORDER BY r.measured_at,r.id) AS previous_value
        FROM telemetry_readings r JOIN devices d ON d.tenant_id=r.tenant_id AND d.id=r.device_id
        WHERE r.tenant_id=? AND d.farm_id=? AND r.metric IN ('ENERGY','WATER_TOTAL') AND r.source='SIMULATED') samples
      WHERE previous_value>measured_value
      """,Long.class,tenant,farmId));
    checks.put("plotsWithoutSoilMonitoring",db.queryForObject("""
      SELECT COUNT(*) FROM plots p WHERE p.tenant_id=? AND p.farm_id=? AND NOT EXISTS(
        SELECT 1 FROM asset_profiles a JOIN device_channels c ON c.tenant_id=a.tenant_id AND c.device_id=a.device_id
        WHERE a.tenant_id=p.tenant_id AND a.plot_id=p.id AND c.metric='SOIL_MOISTURE')
      """,Long.class,tenant,farmId));
    boolean valid=checks.values().stream().allMatch(value->((Number)value).longValue()==0);
    return Map.of("available",true,"synthetic",true,"manifest",manifests.getFirst(),"counts",counts,"checks",checks,"consistent",valid,
      "note","学术合成数据 v2：按种植季构造，产量和监测值为假设值；不能作为真实观测或实际生产效果的证据。");
  }
}
