package app.zhinong.ai;
import app.zhinong.api.ApiException;
import app.zhinong.database.DatabaseTime;
import app.zhinong.security.ScheduledIdentity;
import app.zhinong.workspace.AssetService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class IrrigationTicker {
  private final JdbcTemplate db;private final IrrigationService service;private final ScheduledIdentity identities;private final AssetService assets;
  @Value("${farm.ai.automation-enabled:true}") boolean enabled;
  public IrrigationTicker(JdbcTemplate db,IrrigationService service,ScheduledIdentity identities,AssetService assets) {this.db=db;this.service=service;this.identities=identities;this.assets=assets;}
  @Scheduled(fixedDelay=5000,initialDelay=15000) public void scheduled() {if(enabled) tick();}
  public void tick() {
    for(var tenant:db.queryForList("SELECT id FROM tenants")) tickTenant(tenant.get("ID").toString());
  }
  private void tickTenant(String tenant) {
    // Stop recovery is unconditional: it must work even when an account/tenant was disabled.
    for(var r:db.queryForList("SELECT * FROM ai_irrigation_runs WHERE tenant_id=? AND status='RUNNING'",tenant)) {
      String id=r.get("ID").toString();
      try {
        String reason=stopReason(r);
        if(reason!=null) service.finish(tenant,id,reason);
      } catch(Exception ex) {db.update("UPDATE ai_irrigation_runs SET result_note='自动停止检查失败，将重试；请检查设备状态' WHERE tenant_id=? AND id=? AND status='RUNNING'",tenant,id);}
    }
    db.update("UPDATE ai_irrigation_runs SET status='EXPIRED',result_note='建议已过期' WHERE tenant_id=? AND status='PROPOSED' AND expires_at<=?",tenant,OffsetDateTime.now());
    for(var p:db.queryForList("SELECT plot_id FROM ai_irrigation_policies WHERE tenant_id=? AND mode='AUTO' AND (last_check_at IS NULL OR last_check_at<?)",tenant,OffsetDateTime.now().minusSeconds(60))) {
      String plot=p.get("PLOT_ID").toString(),result;
      try { boolean ran=identities.asPolicyOwner(tenant,plot,() -> service.evaluate(plot));result=ran?"已按策略生成并执行模拟灌溉":"策略授权人或租户已停用，自动模式暂停"; }
      catch(ApiException ex) {result=ex.getMessage();}
      catch(Exception ex) {result="本次检查失败，未启动灌溉；下次重试";}
      db.update("UPDATE ai_irrigation_policies SET last_check_at=?,last_result=? WHERE tenant_id=? AND plot_id=?",OffsetDateTime.now(),result.substring(0,Math.min(300,result.length())),tenant,plot);
    }
  }
  private String stopReason(Map<String,Object> r) {
    String tenant=r.get("TENANT_ID").toString(),pump=r.get("PUMP_ID").toString();
    if(!DatabaseTime.offset(r.get("STOP_AT")).isAfter(OffsetDateTime.now())) return "达到运行时限";
    if(db.queryForObject("SELECT COUNT(*) FROM asset_profiles WHERE tenant_id=? AND device_id=? AND lifecycle='ACTIVE' AND protocol='SIMULATED' AND control_enabled=TRUE",Long.class,tenant,pump)==0) return "水泵配置或控制状态已改变";
    if(db.queryForObject("SELECT COUNT(*) FROM field_issues WHERE tenant_id=? AND plot_id=? AND severity='HIGH' AND status<>'RESOLVED'",Long.class,tenant,r.get("PLOT_ID"))>0) return "出现高严重度现场问题";
    if(db.queryForObject("SELECT COUNT(*) FROM ai_irrigation_policies p JOIN tenants t ON t.id=p.tenant_id JOIN members m ON m.tenant_id=p.tenant_id AND m.id=p.owner_id WHERE p.tenant_id=? AND p.plot_id=? AND p.revision=? AND t.enabled=TRUE AND m.enabled=TRUE AND m.role='ADMIN'",Long.class,tenant,r.get("PLOT_ID"),r.get("POLICY_REVISION"))==0) return "策略或授权已失效";
    for(String metric:List.of("REMOTE_ENABLED","FAULT","PUMP_RUNNING")) {
      var reading=assets.latest(tenant,pump,metric);
      if(reading==null || DatabaseTime.offset(reading.get("time")).toInstant().isBefore(Instant.now().minusSeconds(180))) return "运行反馈过期";
      if(DatabaseTime.offset(reading.get("time")).toInstant().isAfter(Instant.now().plusSeconds(60))) return "设备反馈时间异常";
      if(new java.math.BigDecimal(reading.get("value").toString()).compareTo(java.math.BigDecimal.valueOf(metric.equals("FAULT")?0:1))!=0) return "设备已停机、故障或退出远程模式";
    }
    var soil=assets.latest(tenant,r.get("SENSOR_ID").toString(),"SOIL_MOISTURE");
    if(soil==null || DatabaseTime.offset(soil.get("time")).toInstant().isBefore(Instant.now().minusSeconds(900))) return "土壤测点失联";
    var threshold=db.queryForObject("SELECT threshold_value FROM ai_irrigation_policies WHERE tenant_id=? AND plot_id=?",java.math.BigDecimal.class,tenant,r.get("PLOT_ID"));
    if(((Number)soil.get("value")).doubleValue()>=threshold.doubleValue()+5) return "土壤水分达到停止阈值";
    return null;
  }
}
