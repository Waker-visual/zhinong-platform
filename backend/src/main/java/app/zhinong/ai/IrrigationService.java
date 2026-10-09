package app.zhinong.ai;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.database.DatabaseTime;
import app.zhinong.security.Identity;
import app.zhinong.workspace.AssetService;
import app.zhinong.workspace.DeviceCommandService;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IrrigationService {
  private final Store store;private final JdbcTemplate db;private final AssetService assets;private final DeviceCommandService commands;
  public IrrigationService(Store store,JdbcTemplate db,AssetService assets,DeviceCommandService commands) {this.store=store;this.db=db;this.assets=assets;this.commands=commands;}
  public record Policy(@NotBlank String farmId,@NotBlank String plotId,@NotBlank String sensorId,@NotBlank String pumpId,
    @NotBlank @Pattern(regexp="MANUAL|AUTO") String mode,@NotNull @DecimalMin("5") @DecimalMax("80") BigDecimal thresholdValue,
    @Min(10) @Max(300) int durationSeconds,@Min(30) @Max(1440) int cooldownMinutes,@Min(1) @Max(6) int dailyLimit,@Min(0) int revision) {}

  public Map<String,Object> workspace(String farmId) {
    store.get("farms",farmId);String tenant=Identity.tenant();
    var plots=db.queryForList("SELECT id,name,crop FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY name",tenant,farmId);
    var devices=db.queryForList("""
      SELECT d.id,d.name,a.plot_id AS "plotId",a.device_type AS "deviceType",a.protocol,a.control_enabled AS "controlEnabled"
      FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
      WHERE d.tenant_id=? AND d.farm_id=? AND a.lifecycle='ACTIVE' AND (a.device_type='PUMP' OR EXISTS(
        SELECT 1 FROM device_channels c WHERE c.tenant_id=d.tenant_id AND c.device_id=d.id AND c.metric='SOIL_MOISTURE'))
      ORDER BY d.name
      """,tenant,farmId);
    return Map.of("plots",plots,"devices",devices,"policies",db.queryForList("SELECT * FROM ai_irrigation_policies WHERE tenant_id=? AND farm_id=? ORDER BY plot_id",tenant,farmId),
      "runs",db.queryForList("SELECT r.*,p.name AS \"plotName\",d.name AS \"pumpName\" FROM ai_irrigation_runs r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id JOIN devices d ON d.tenant_id=r.tenant_id AND d.id=r.pump_id WHERE r.tenant_id=? AND r.farm_id=? ORDER BY r.created_at DESC LIMIT 50",tenant,farmId),
      "executionScope","SIMULATED_ONLY");
  }
  public Map<String,Object> save(Policy p) {
    Identity.require("ADMIN");store.lock("farms",p.farmId());
    var plot=store.get("plots",p.plotId());if(!p.farmId().equals(plot.get("FARM_ID"))) throw new ApiException(400,"地块不属于当前农场");
    var sensor=assets.detail(p.sensorId());var pump=assets.detail(p.pumpId());
    if(!p.farmId().equals(sensor.get("farmId")) || !p.farmId().equals(pump.get("farmId")) || !p.plotId().equals(sensor.get("plotId"))) throw new ApiException(400,"土壤测点必须绑定此地块，水泵必须属于当前农场");
    if(!"PUMP".equals(pump.get("deviceType")) || !Boolean.TRUE.equals(pump.get("controlEnabled"))) throw new ApiException(400,"请选择已启用控制的水泵");
    if(!"SIMULATED".equals(pump.get("protocol")) || !"SIMULATED".equals(sensor.get("protocol"))) throw new ApiException(409,"当前定时灌溉仅支持模拟测点和模拟水泵；实体网关需先实现本地限时停泵协议");
    if(db.queryForObject("SELECT COUNT(*) FROM device_channels WHERE tenant_id=? AND device_id=? AND metric='SOIL_MOISTURE'",Long.class,Identity.tenant(),p.sensorId())==0) throw new ApiException(400,"所选测点没有土壤水分指标");
    var old=db.queryForList("SELECT revision FROM ai_irrigation_policies WHERE tenant_id=? AND plot_id=? FOR UPDATE",Identity.tenant(),p.plotId());
    int revision=old.isEmpty()?0:((Number)old.getFirst().get("REVISION")).intValue();
    if(revision!=p.revision()) throw new ApiException(409,"策略已被修改，请刷新后重试");
    db.update(store.sql().upsert("ai_irrigation_policies","tenant_id,plot_id","tenant_id,farm_id,plot_id,sensor_id,pump_id,mode,threshold_value,duration_seconds,cooldown_minutes,daily_limit,owner_id,revision,updated_at,last_result"),
      Identity.tenant(),p.farmId(),p.plotId(),p.sensorId(),p.pumpId(),p.mode(),p.thresholdValue(),p.durationSeconds(),p.cooldownMinutes(),p.dailyLimit(),Identity.current().memberId(),revision+1,OffsetDateTime.now(),"策略已保存，等待检查");
    db.update("UPDATE ai_irrigation_runs SET status='CANCELLED',result_note='策略变更，建议已失效' WHERE tenant_id=? AND plot_id=? AND status='PROPOSED'",Identity.tenant(),p.plotId());
    stopPlot(p.plotId(),"策略变更，停止原灌溉");store.audit("AI_IRRIGATION_POLICY",p.plotId());return workspace(p.farmId());
  }
  private Map<String,Object> policy(String plot) {
    store.get("plots",plot);
    var rows=db.queryForList("SELECT * FROM ai_irrigation_policies WHERE tenant_id=? AND plot_id=? FOR UPDATE",Identity.tenant(),plot);
    if(rows.isEmpty()) throw new ApiException(409,"请先由管理员配置此地块的灌溉策略");return rows.getFirst();
  }
  private int number(Map<String,Object> p,String key) {return ((Number)p.get(key)).intValue();}
  private BigDecimal eligible(Map<String,Object> p) {
    String tenant=Identity.tenant(),pumpId=p.get("PUMP_ID").toString(),sensorId=p.get("SENSOR_ID").toString(),plot=p.get("PLOT_ID").toString();
    if(db.queryForObject("SELECT COUNT(*) FROM members m JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.id=? AND m.enabled=TRUE AND t.enabled=TRUE AND m.role='ADMIN'",Long.class,tenant,p.get("OWNER_ID"))==0) throw new ApiException(409,"策略授权人或租户已停用，请由管理员重新保存策略");
    var pump=assets.detail(pumpId);var sensor=assets.detail(sensorId);
    if(!p.get("FARM_ID").equals(pump.get("farmId")) || !p.get("FARM_ID").equals(sensor.get("farmId")) || !plot.equals(sensor.get("plotId"))
        || !"SIMULATED".equals(pump.get("protocol")) || !"SIMULATED".equals(sensor.get("protocol")) || !Boolean.TRUE.equals(pump.get("controlEnabled"))
        || !"ACTIVE".equals(pump.get("lifecycle")) || !"ACTIVE".equals(sensor.get("lifecycle"))) throw new ApiException(409,"设备、地块绑定或模拟控制状态已变化");
    if(db.queryForObject("SELECT COUNT(*) FROM plantings WHERE tenant_id=? AND plot_id=? AND status='ACTIVE' AND start_date<=? AND end_date>=?",Long.class,tenant,plot,LocalDate.now(),LocalDate.now())!=1) throw new ApiException(409,"需要且只能有一项处于有效日期内的在种计划");
    // This academic dryland rule cannot infer paddy water depth from volumetric soil moisture.
    if(db.queryForObject("SELECT COUNT(*) FROM plantings WHERE tenant_id=? AND plot_id=? AND status='ACTIVE' AND start_date<=? AND end_date>=? AND (crop LIKE '%稻%' OR crop LIKE '%水生%')",Long.class,tenant,plot,LocalDate.now(),LocalDate.now())>0) throw new ApiException(409,"水稻与水生作物需水位控制策略，不能使用本墒情规则");
    var reading=assets.latest(tenant,sensorId,"SOIL_MOISTURE");
    if(!fresh(reading)) throw new ApiException(409,"土壤测点超过15分钟未更新或缺少读数，暂停灌溉");
    var moisture=new BigDecimal(reading.get("value").toString());
    if(moisture.compareTo(BigDecimal.ZERO)<0 || moisture.compareTo(BigDecimal.valueOf(100))>0 || moisture.compareTo(new BigDecimal(p.get("THRESHOLD_VALUE").toString()))>=0) throw new ApiException(409,"土壤水分未低于启灌阈值，无需启动");
    for(String metric:List.of("REMOTE_ENABLED","FAULT","PUMP_RUNNING")) {
      var latest=assets.latest(tenant,pumpId,metric);
      if(!fresh(latest) || new BigDecimal(latest.get("value").toString()).compareTo(BigDecimal.valueOf(metric.equals("REMOTE_ENABLED")?1:0))!=0) throw new ApiException(409,"水泵必须处于新鲜反馈的远程、无故障、停机状态");
    }
    if(db.queryForObject("SELECT COUNT(*) FROM field_issues WHERE tenant_id=? AND plot_id=? AND severity='HIGH' AND status<>'RESOLVED'",Long.class,tenant,plot)>0) throw new ApiException(409,"地块有高严重度问题，请先排查");
    var now=OffsetDateTime.now();
    if(db.queryForObject("SELECT COUNT(*) FROM ai_irrigation_runs WHERE tenant_id=? AND pump_id=? AND (status='RUNNING' OR started_at>?)",Long.class,tenant,pumpId,now.minusMinutes(number(p,"COOLDOWN_MINUTES")))>0) throw new ApiException(409,"水泵正在运行或仍在灌溉冷却期");
    if(db.queryForObject("SELECT COUNT(*) FROM ai_irrigation_runs WHERE tenant_id=? AND pump_id=? AND started_at>=?",Long.class,tenant,pumpId,LocalDate.now().atStartOfDay().atOffset(now.getOffset()))>=number(p,"DAILY_LIMIT")) throw new ApiException(409,"已达到此水泵今日灌溉次数上限");
    return moisture;
  }
  private boolean fresh(Map<String,Object> r) {if(r==null) return false;var t=DatabaseTime.offset(r.get("time")).toInstant();return t.isAfter(Instant.now().minusSeconds(900)) && !t.isAfter(Instant.now().plusSeconds(60));}
  public Map<String,Object> propose(String plot,boolean automatic) {
    Identity.require("ADMIN","OPERATOR");var p=policy(plot);lockTenant();store.lock("devices",p.get("PUMP_ID").toString());
    var moisture=eligible(p);String tenant=Identity.tenant();
    db.update("UPDATE ai_irrigation_runs SET status='EXPIRED',result_note='建议超过10分钟有效期' WHERE tenant_id=? AND plot_id=? AND status='PROPOSED' AND expires_at<=?",tenant,plot,OffsetDateTime.now());
    var prior=db.queryForList("SELECT * FROM ai_irrigation_runs WHERE tenant_id=? AND plot_id=? AND status='PROPOSED'",tenant,plot);
    Map<String,Object> run;
    if(!prior.isEmpty()) run=prior.getFirst();else {
      String id=UUID.randomUUID().toString();var now=OffsetDateTime.now();
      db.update("""
        INSERT INTO ai_irrigation_runs(id,tenant_id,farm_id,plot_id,sensor_id,pump_id,policy_revision,status,reason,duration_seconds,moisture_value,requested_by,created_at,expires_at)
        VALUES(?,?,?,?,?,?,?,'PROPOSED',?,?,?,?,?,?)
        """,id,tenant,p.get("FARM_ID"),plot,p.get("SENSOR_ID"),p.get("PUMP_ID"),p.get("REVISION"),
        "土壤水分 "+moisture+"%，低于配置阈值 "+p.get("THRESHOLD_VALUE")+"%；仅模拟灌溉，尚未接入未来天气预报。",p.get("DURATION_SECONDS"),moisture,automatic?"AI_AUTOMATION":Identity.current().username(),now,now.plusMinutes(10));
      store.audit("AI_IRRIGATION_PROPOSE",id);run=run(id,false);
    }
    if(automatic && "AUTO".equals(p.get("MODE"))) return approve(run.get("ID").toString(),true);
    return run;
  }
  private Map<String,Object> run(String id,boolean lock) {
    var rows=db.queryForList("SELECT * FROM ai_irrigation_runs WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),Identity.tenant(),id);
    if(rows.isEmpty()) throw ApiException.missing();return rows.getFirst();
  }
  private void lockTenant() {
    if(db.queryForList("SELECT id FROM tenants WHERE id=? AND enabled=TRUE FOR UPDATE",Identity.tenant()).isEmpty()) throw new ApiException(403,"当前租户已停用");
  }
  public Map<String,Object> approve(String id,boolean automatic) {
    Identity.require("ADMIN","OPERATOR");var initial=run(id,false);var p=policy(initial.get("PLOT_ID").toString());
    lockTenant();store.lock("devices",initial.get("PUMP_ID").toString());var r=run(id,true);
    if(Set.of("CANCELLED","EXPIRED").contains(r.get("STATUS"))) throw new ApiException(409,"建议已取消或过期，请重新生成");
    if(!"PROPOSED".equals(r.get("STATUS"))) return r;
    if(!DatabaseTime.offset(r.get("EXPIRES_AT")).isAfter(OffsetDateTime.now())) throw new ApiException(409,"建议已过期，请重新生成");
    if(number(r,"POLICY_REVISION")!=number(p,"REVISION")) throw new ApiException(409,"策略已更改，请重新生成建议");
    if(automatic && !"AUTO".equals(p.get("MODE"))) throw new ApiException(409,"自动模式已关闭");
    eligible(p);
    var command=commands.create(r.get("PUMP_ID").toString(),new DeviceCommandService.Input("ai-start-"+id,"PUMP_START",null,"AI定时模拟灌溉 "+r.get("DURATION_SECONDS")+" 秒"));
    if(!"SUCCEEDED".equals(command.get("status"))) throw new ApiException(409,"模拟设备未确认启动");
    var now=OffsetDateTime.now();
    db.update("UPDATE ai_irrigation_runs SET status='RUNNING',approved_by=?,started_at=?,stop_at=?,command_id=?,result_note='模拟灌溉执行中' WHERE tenant_id=? AND id=?",automatic?"AI_AUTOMATION":Identity.current().username(),now,now.plusSeconds(number(r,"DURATION_SECONDS")),command.get("id"),Identity.tenant(),id);
    store.audit(automatic?"AI_IRRIGATION_AUTO":"AI_IRRIGATION_APPROVE",id);return run(id,false);
  }
  public Map<String,Object> cancel(String id) {
    Identity.require("ADMIN","OPERATOR");var r=run(id,false);policy(r.get("PLOT_ID").toString());
    store.lock("devices",r.get("PUMP_ID").toString());r=run(id,true);
    if("RUNNING".equals(r.get("STATUS"))) finish(Identity.tenant(),id,"人工停止");
    else db.update("UPDATE ai_irrigation_runs SET status='CANCELLED',result_note='人工取消' WHERE tenant_id=? AND id=? AND status='PROPOSED'",Identity.tenant(),id);
    store.audit("AI_IRRIGATION_CANCEL",id);return run(id,false);
  }
  private void stopPlot(String plot,String reason) {
    var runs=db.queryForList("SELECT id FROM ai_irrigation_runs WHERE tenant_id=? AND plot_id=? AND status='RUNNING'",Identity.tenant(),plot);
    runs.forEach(r -> finish(Identity.tenant(),r.get("ID").toString(),reason));
  }
  public void finish(String tenant,String id,String reason) {
    // Pump lock precedes run lock everywhere, preventing approval/ticker lock inversion.
    var rows=db.queryForList("SELECT pump_id FROM ai_irrigation_runs WHERE tenant_id=? AND id=? AND status='RUNNING'",tenant,id);
    if(rows.isEmpty()) return;
    db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE",tenant,rows.getFirst().get("PUMP_ID"));
    String command=commands.stopScheduledSimulation(tenant,id);if(command.isEmpty()) return;
    db.update("UPDATE ai_irrigation_runs SET status='COMPLETED',stop_command_id=?,finished_at=?,result_note=? WHERE tenant_id=? AND id=? AND status='RUNNING'",command,OffsetDateTime.now(),reason+"；模拟停泵已确认",tenant,id);
  }
  public void evaluate(String plot) {propose(plot,true);}

  /** Read-only, tenant+farm scoped: the still-valid PROPOSED runs for a farm, used by the agent
   * stream (Task 6) to surface an approval activity. Never writes anything; approving or cancelling
   * still goes exclusively through {@link #approve} / {@link #cancel}. */
  public List<Map<String,Object>> pendingProposals(String farmId) {
    store.get("farms",farmId);String tenant=Identity.tenant();
    return db.queryForList("""
      SELECT r.id,r.plot_id AS "plotId",p.name AS "plotName",r.reason,r.duration_seconds AS "durationSeconds"
      FROM ai_irrigation_runs r JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
      WHERE r.tenant_id=? AND r.farm_id=? AND r.status='PROPOSED' AND r.expires_at>? ORDER BY r.created_at DESC
      """,tenant,farmId,OffsetDateTime.now());
  }
}
