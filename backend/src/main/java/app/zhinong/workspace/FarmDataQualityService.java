package app.zhinong.workspace;

import app.zhinong.business.FarmClock;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only reconciliation. Findings never rewrite historical records or infer missing harvests. */
@Service
public class FarmDataQualityService {
  private final Store store;
  private final FarmClock clock;
  public FarmDataQualityService(Store store, FarmClock clock) { this.store=store; this.clock=clock; }

  @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
  public Map<String,Object> check(String farmId) {
    Identity.require("ADMIN");
    var farm=store.get("farms",farmId);
    String tenant=Identity.tenant();
    var checks=new ArrayList<Map<String,Object>>();
    // A tracking row is the complete canonical association, including intentionally cleared fields.
    String planting="CASE WHEN x.production_id IS NULL THEN l.planting_id ELSE x.planting_id END";
    String task="CASE WHEN x.production_id IS NULL THEN l.task_id ELSE x.task_id END";
    String harvest="""
      FROM production r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
      LEFT JOIN production_tracking x ON x.tenant_id=r.tenant_id AND x.production_id=r.id
      LEFT JOIN production_lineage l ON l.tenant_id=r.tenant_id AND l.production_id=r.id
      """;
    String effective=" r.tenant_id=? AND p.farm_id=? AND (x.status IS NULL OR x.status<>'VOID') ";
    add(checks,"FUTURE_PRODUCTION","生产日期晚于农场今日","ERROR","条",
      "SELECT r.id AS \"id\",p.name AS \"plotName\",r.record_date AS \"date\" "+harvest+
      " WHERE "+effective+" AND r.record_date>?",tenant,farmId,clock.today());
    add(checks,"PRODUCTION_ASSOCIATION","生产与种植、采收任务关联不一致","ERROR","条",
      "SELECT r.id AS \"id\",p.name AS \"plotName\",r.record_date AS \"date\" "+harvest+
      " LEFT JOIN plantings s ON s.tenant_id=r.tenant_id AND s.id=("+planting+")"+
      " LEFT JOIN farm_tasks t ON t.tenant_id=r.tenant_id AND t.id=("+task+")"+
      " LEFT JOIN task_fieldwork w ON w.tenant_id=t.tenant_id AND w.task_id=t.id"+
      " WHERE "+effective+" AND (( ("+planting+") IS NOT NULL AND (s.id IS NULL OR s.plot_id<>r.plot_id OR r.record_date<s.start_date OR r.record_date>s.end_date))"+
      " OR (("+task+") IS NOT NULL AND (("+planting+") IS NULL OR t.id IS NULL OR t.plot_id<>r.plot_id OR t.task_type<>'HARVEST' OR t.status<>'COMPLETED'"+
      " OR t.due_date<s.start_date OR t.due_date>s.end_date OR (w.task_id IS NOT NULL AND (w.completed_at IS NULL OR TRIM(w.completion_note)='' OR w.method='UNCONFIRMED' OR w.actual_area_mu<=0 OR w.actual_area_mu>s.area_mu)))))",tenant,farmId);
    add(checks,"SUSPECTED_PRODUCTION_DUPLICATES","同地块同日同内容的疑似重复生产记录","WARNING","组",
      "SELECT MIN(r.id) AS \"id\",p.name AS \"plotName\",r.record_date AS \"date\",r.yield_kg AS \"yieldKg\",COUNT(*) AS \"records\" "+harvest+
      " WHERE "+effective+" GROUP BY r.plot_id,p.name,r.record_date,r.yield_kg,r.note,"+planting+","+task+" HAVING COUNT(*)>1",tenant,farmId);
    add(checks,"UNLINKED_PRODUCTION","未关联种植计划的兼容生产记录","INFO","条",
      "SELECT r.id AS \"id\",p.name AS \"plotName\",r.record_date AS \"date\" "+harvest+
      " WHERE "+effective+" AND ("+planting+") IS NULL AND ("+task+") IS NULL",tenant,farmId);
    add(checks,"OVERLAPPING_PLANTINGS","同地块未结束种植计划时间重叠","ERROR","对",
      """
      SELECT s.id AS "id",b.id AS "otherId",p.name AS "plotName"
      FROM plantings s JOIN plots p ON p.tenant_id=s.tenant_id AND p.id=s.plot_id
      JOIN plantings b ON b.tenant_id=s.tenant_id AND b.plot_id=s.plot_id AND b.id>s.id
      WHERE s.tenant_id=? AND p.farm_id=? AND s.status<>'FINISHED' AND b.status<>'FINISHED'
        AND s.start_date<=b.end_date AND b.start_date<=s.end_date
      """,tenant,farmId);
    add(checks,"PLANTING_AREA","未结束种植面积超过地块面积","ERROR","条",
      """
      SELECT s.id AS "id",p.name AS "plotName",s.area_mu AS "areaMu",p.area_mu AS "plotAreaMu"
      FROM plantings s JOIN plots p ON p.tenant_id=s.tenant_id AND p.id=s.plot_id
      WHERE s.tenant_id=? AND p.farm_id=? AND s.status<>'FINISHED' AND s.area_mu>p.area_mu
      """,tenant,farmId);
    add(checks,"TASK_RECEIPT","已完成任务的跟踪回执不完整","ERROR","条",
      """
      SELECT t.id AS "id",t.title AS "title",p.name AS "plotName"
      FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
      JOIN task_fieldwork w ON w.tenant_id=t.tenant_id AND w.task_id=t.id
      WHERE t.tenant_id=? AND p.farm_id=? AND t.status='COMPLETED'
        AND (w.completed_at IS NULL OR TRIM(w.completion_note)='' OR w.method='UNCONFIRMED'
          OR w.actual_area_mu<=0 OR w.actual_area_mu>p.area_mu)
      """,tenant,farmId);
    add(checks,"ISSUE_TASK_LINK","现场问题与任务关联或关闭状态不一致","ERROR","条",
      """
      SELECT i.id AS "id",p.name AS "plotName",i.status AS "status",i.task_id AS "taskId"
      FROM field_issues i JOIN plots p ON p.tenant_id=i.tenant_id AND p.id=i.plot_id
      LEFT JOIN farm_tasks t ON t.tenant_id=i.tenant_id AND t.id=i.task_id
      WHERE i.tenant_id=? AND p.farm_id=? AND ((i.status='ASSIGNED' AND i.task_id IS NULL)
        OR (i.task_id IS NOT NULL AND (t.id IS NULL OR t.plot_id<>i.plot_id OR (i.status='RESOLVED' AND t.status<>'COMPLETED'))))
      """,tenant,farmId);
    add(checks,"DEVICE_PLOT_SCOPE","设备与地块所属农场不一致","ERROR","条",
      """
      SELECT d.id AS "id",d.name AS "deviceName",p.name AS "plotName"
      FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
      JOIN plots p ON p.tenant_id=a.tenant_id AND p.id=a.plot_id
      WHERE d.tenant_id=? AND d.farm_id=? AND p.farm_id<>d.farm_id
      """,tenant,farmId);
    add(checks,"DECISION_PLOT_SCOPE","决策快照与当前地块所属农场不一致","ERROR","条",
      """
      SELECT a.id AS "id",p.name AS "plotName"
      FROM ai_decisions a JOIN plots p ON p.tenant_id=a.tenant_id AND p.id=a.plot_id
      WHERE a.tenant_id=? AND a.farm_id=? AND p.farm_id<>a.farm_id
      """,tenant,farmId);
    var summary=new LinkedHashMap<String,Object>();
    for(String severity:List.of("ERROR","WARNING","INFO")) summary.put(severity.toLowerCase(Locale.ROOT)+"Count",
      checks.stream().filter(c->severity.equals(c.get("severity"))).mapToLong(c->((Number)c.get("count")).longValue()).sum());
    summary.put("checkedRules",checks.size());
    var result=new LinkedHashMap<String,Object>();
    result.put("farmId",farmId);result.put("farmName",farm.get("NAME"));result.put("checkedAt",clock.now());
    result.put("timeZone",clock.zone().getId());result.put("today",clock.today());result.put("ruleVersion","data-chain-v1");
    result.put("summary",summary);result.put("checks",checks);
    result.put("notice","只读核对，不修改历史数据。计数按各项规则累计，同一记录可能命中多项；每项最多展示20个样本。疑似重复可能是分批采收，需核对后人工更正或作废。兼容记录缺少关联不等于错误。本报告不证明农业数据真实性。");
    return result;
  }

  private void add(List<Map<String,Object>> checks,String code,String label,String severity,String unit,String query,Object...args) {
    long count=store.db().queryForObject("SELECT COUNT(*) FROM ("+query+") checked_rows",Long.class,args);
    var row=new LinkedHashMap<String,Object>();row.put("code",code);row.put("label",label);row.put("severity",severity);
    row.put("count",count);row.put("countUnit",unit);row.put("samples",store.db().queryForList(query+" ORDER BY 1 LIMIT 20",args));
    checks.add(row);
  }
}
