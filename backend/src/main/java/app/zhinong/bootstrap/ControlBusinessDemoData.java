package app.zhinong.bootstrap;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Additive, fictional business records for the existing device demonstration farm. */
@Component
@Order(45)
public class ControlBusinessDemoData implements ApplicationRunner {
  private final JdbcTemplate db;
  private final boolean enabled;
  public ControlBusinessDemoData(JdbcTemplate db, @Value("${farm.demo:false}") boolean demo,
      @Value("${farm.demo-rich:true}") boolean rich) {
    this.db = db;
    enabled = demo && rich;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!enabled) return;
    for (String tenant : db.queryForList("SELECT id FROM tenants WHERE code IN ('demo-a','demo-b') AND enabled=TRUE", String.class)) {
      db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE",tenant);
      if (db.queryForObject("SELECT COUNT(*) FROM demo_scenarios WHERE tenant_id=? AND scenario='device-business-v1'",Integer.class,tenant)>0) continue;
      var farms=db.queryForList("""
        SELECT f.farm_id FROM farm_profiles f JOIN devices d ON d.tenant_id=f.tenant_id AND d.farm_id=f.farm_id
        JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
        WHERE f.tenant_id=? AND f.demo=TRUE AND a.code='DEMO-CTRL-PUMP'
        """,String.class,tenant);
      var operators=db.queryForList("SELECT id FROM members WHERE tenant_id=? AND username='operator' AND role='OPERATOR' AND enabled=TRUE",String.class,tenant);
      var admins=db.queryForList("SELECT id FROM members WHERE tenant_id=? AND username='admin' AND role='ADMIN' AND enabled=TRUE",String.class,tenant);
      if(farms.size()!=1 || operators.size()!=1 || admins.size()!=1) continue;
      // The richer operating/research scenario owns this farm's business history.
      if (db.queryForObject("SELECT COUNT(*) FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?",
          Integer.class, tenant, farms.getFirst()) > 0) continue;
      seed(tenant,farms.getFirst(),operators.getFirst(),admins.getFirst());
      db.update("INSERT INTO demo_scenarios(tenant_id,scenario,created_at) VALUES(?,'device-business-v1',CURRENT_TIMESTAMP)",tenant);
    }
  }

  private void seed(String tenant,String farm,String operator,String admin) {
    LocalDate today=LocalDate.now();
    for(var plot:db.queryForList("SELECT id,name,crop,area_mu FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY name",tenant,farm)) {
      String plotId=plot.get("ID").toString();
      BigDecimal area=(BigDecimal)plot.get("AREA_MU");
      boolean vegetable=plot.get("CROP").equals("蔬菜");
      if(db.queryForObject("SELECT COUNT(*) FROM plantings WHERE tenant_id=? AND plot_id=?",Integer.class,tenant,plotId)==0) {
        db.update("INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
          id(),tenant,plotId,plot.get("CROP"),"虚构经营演示品种",area,today.minusDays(vegetable?60:160),today.plusDays(vegetable?20:-8),vegetable?"ACTIVE":"FINISHED");
      }
      // Harvest dates fall within the demonstrated crop cycle. No daily production is invented.
      if(db.queryForObject("SELECT COUNT(*) FROM production WHERE tenant_id=? AND plot_id=?",Integer.class,tenant,plotId)==0) {
        for(int batch=0;batch<(vegetable?3:1);batch++) db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",
          id(),tenant,plotId,today.minusDays(vegetable?7L*(batch+1):8),area.multiply(BigDecimal.valueOf(vegetable?150+batch*10L:520)),"虚构经营演示收获，非真实产量；用于台账与图表验收");
      }
      String[] states={"COMPLETED","COMPLETED","RUNNING","PENDING"};
      String[] types={"INSPECTION","HARVEST","INSPECTION","FERTILIZING"};
      String[] titles={"巡田与处置复查","分区收获登记","灌溉接口排查","下次追肥准备"};
      int[] ago={3,vegetable?7:8,0,-3};
      String completedTask=null,activeTask=null;
      for(int index=0;index<states.length;index++) {
        String task=id();boolean completed=index<2;
        String note=completed?"虚构作业回执：已按示范面积完成并记录结果":"虚构演示任务：请在实际经营时录入真实安排";
        db.update("INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,?,?,?,?,?)",
          task,tenant,plotId,plot.get("NAME")+" / "+titles[index],types[index],today.minusDays(ago[index]),states[index],note);
        var done=Timestamp.from(today.minusDays(ago[index]).atTime(9,0).atZone(ZoneId.systemDefault()).toInstant());
        db.update("""
          INSERT INTO task_fieldwork(tenant_id,task_id,assignee_id,method,completion_note,actual_area_mu,completed_at)
          VALUES(?,?,?,'MANUAL',?,?,?)
          """,tenant,task,operator,completed?note:"",completed?area:BigDecimal.ZERO,completed?done:null);
        db.update("""
          INSERT INTO field_work_logs(id,tenant_id,task_id,actor_id,action,note,method,actual_area_mu,occurred_at)
          VALUES(?,?,?,?,?,?,'MANUAL',?,?)
          """,id(),tenant,task,operator,completed?"COMPLETED":index==2?"RUNNING":"PLANNED",note,
          completed?area:BigDecimal.ZERO,completed?done:Timestamp.from(Instant.now()));
        if(index==0) completedTask=task;
        if(index==2) activeTask=task;
      }
      db.update("""
        INSERT INTO field_issues(id,tenant_id,plot_id,category,severity,description,reporter_id,status,task_id,created_at)
        VALUES(?,?,?,'EQUIPMENT','NORMAL',?,?,'ASSIGNED',?,?)
        """,id(),tenant,plotId,"虚构巡田演练：接口渗漏待现场排查，不代表真实设备故障",operator,activeTask,Timestamp.from(Instant.now()));
      var reviewed=Timestamp.from(today.minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant());
      db.update("""
        INSERT INTO field_issues(id,tenant_id,plot_id,category,severity,description,reporter_id,status,task_id,created_at,review_note,reviewed_by,resolved_at)
        VALUES(?,?,?,'PEST','NORMAL',?,?,'RESOLVED',?,?,?, ?,?)
        """,id(),tenant,plotId,"虚构虫情巡查演练，已完成处置复查",operator,completedTask,
        Timestamp.from(today.minusDays(5).atStartOfDay(ZoneId.systemDefault()).toInstant()),"演示复查记录：处置结果符合模拟验收要求",admin,reviewed);
    }
  }
  private static String id() {return UUID.randomUUID().toString();}
}
