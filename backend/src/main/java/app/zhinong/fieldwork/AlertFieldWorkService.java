package app.zhinong.fieldwork;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import app.zhinong.workspace.MetricCatalog;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AlertFieldWorkService {
  public record Input(@NotBlank @Pattern(regexp="NORMAL|HIGH") String severity,@NotBlank @Size(max=200) String note) {}
  private final Store store;
  private final FieldWorkService work;
  public AlertFieldWorkService(Store store,FieldWorkService work) {this.store=store;this.work=work;}

  public Map<String,Object> report(String id,Input input) {
    Identity.require("ADMIN","OPERATOR");String tenant=Identity.tenant();
    var rows=store.db().queryForList("SELECT device_id FROM device_alerts WHERE tenant_id=? AND id=?",tenant,id);
    if(rows.isEmpty()) throw ApiException.missing();
    // Same lock order as telemetry: device, then alert. Concurrent clicks create only one issue.
    store.lock("devices",rows.getFirst().get("DEVICE_ID").toString());
    var alert=store.db().queryForMap("SELECT * FROM device_alerts WHERE tenant_id=? AND id=? FOR UPDATE",tenant,id);
    var existing=store.db().queryForList("SELECT issue_id FROM alert_field_issues WHERE tenant_id=? AND alert_id=?",tenant,id);
    if(!existing.isEmpty()) return Map.of("id",existing.getFirst().get("ISSUE_ID"),"duplicate",true);
    if("RESOLVED".equals(alert.get("STATUS"))) throw new ApiException(409,"该告警已恢复或关闭，不再创建新的处理问题");
    var profile=store.db().queryForMap("SELECT plot_id FROM asset_profiles WHERE tenant_id=? AND device_id=?",tenant,alert.get("DEVICE_ID"));
    if(profile.get("PLOT_ID")==null) throw new ApiException(409,"请先为设备关联所属地块，再转为田间问题");
    String metric=alert.get("METRIC").toString();
    String category=metric.equals("PEST_COUNT")?"PEST":metric.startsWith("SOIL_MOISTURE") || Set.of("WATER_LEVEL","FLOW","WATER_TOTAL").contains(metric)?"WATER":
      Set.of("FAULT","EMERGENCY_STOP","VOLTAGE","CURRENT","PUMP_RUNNING","GATE_OPENING").contains(metric)?"EQUIPMENT":"OTHER";
    String description="设备告警："+MetricCatalog.get(metric).name()+"="+alert.get("MEASURED_VALUE")+MetricCatalog.get(metric).unit()+
      "；"+alert.get("MESSAGE")+"；现场说明："+input.note().strip();
    if(description.length()>500) description=description.substring(0,500);
    @SuppressWarnings("unchecked") var issue=(Map<String,Object>)work.report(new FieldWorkService.IssueInput(profile.get("PLOT_ID").toString(),category,input.severity(),description));
    store.db().update("INSERT INTO alert_field_issues(tenant_id,alert_id,issue_id) VALUES(?,?,?)",tenant,id,issue.get("id"));
    store.db().update("UPDATE device_alerts SET status='ACKNOWLEDGED',handled_by=?,handle_note=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
      Identity.current().username(),"已转为田间问题，等待派工与复核",tenant,id);
    store.audit("ALERT_TO_FIELD_ISSUE",id);
    return Map.of("id",issue.get("id"),"duplicate",false);
  }
}
