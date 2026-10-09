package app.zhinong.bootstrap;

import app.zhinong.workspace.MetricCatalog;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A reproducible, invented two-year workflow, not observations from a real farm. */
@Service
public class ResearchDemoData {
  static final String NOTE = "学术合成数据 v2；非真实观测、非农艺处方。";
  private final JdbcTemplate db;
  private final OperatingDemoData work;
  private final boolean enabled;
  public ResearchDemoData(JdbcTemplate db, OperatingDemoData work,
      @Value("${farm.demo:false}") boolean demo, @Value("${farm.demo-rich:true}") boolean rich,
      @Value("${farm.research-history:false}") boolean research) {
    this.db=db; this.work=work; enabled=demo && rich && research;
  }

  @Transactional public void initialize() {
    if (!enabled) return;
    var farms=db.queryForList("""
      SELECT f.id,f.tenant_id FROM farms f JOIN tenants t ON t.id=f.tenant_id
      JOIN farm_profiles p ON p.tenant_id=f.tenant_id AND p.farm_id=f.id
      WHERE t.code='demo-a' AND t.enabled=TRUE AND p.demo=TRUE AND f.name='青禾设备联动演示场'
      """);
    for(var farm:farms) {
      String tenant=farm.get("TENANT_ID").toString(), id=farm.get("ID").toString();
      db.queryForList("SELECT id FROM farms WHERE tenant_id=? AND id=? FOR UPDATE",tenant,id);
      if(db.queryForObject("SELECT COUNT(*) FROM demo_research_farms WHERE tenant_id=? AND farm_id=?",Integer.class,tenant,id)>0) continue;
      var plots=work.plots(tenant,id); var crew=work.crew(tenant);
      if(plots.size()!=3 || crew.isEmpty()) continue;
      if(!prepareUntouchedLegacy(tenant,id)) continue;
      LocalDate today=LocalDate.now(), first=today.minusYears(2);
      for(int index=0;index<plots.size();index++) {
        var plot=plots.get(index);
        var seasons=new ArrayList<Season>();
        if(index<2) {
          for(int year=first.getYear();year<=today.getYear()+1;year++) {
            LocalDate start=LocalDate.of(year,5,15),end=LocalDate.of(year,9,25);
            if(start.isBefore(first) || start.isAfter(today.plusYears(1))) continue;
            seasons.add(season(tenant,plot,start,end,today,"水稻单季研究品种"));
          }
        } else {
          LocalDate start=today.minusDays(12);
          seasons.add(season(tenant,plot,start,start.plusDays(52),today,"设施叶菜研究品种"));
          for(int cycle=1;cycle<=20;cycle++) {
            LocalDate end=start.minusDays(8); start=end.minusDays(48+(cycle%3)*7);
            if(start.isBefore(first)) break;
            seasons.add(season(tenant,plot,start,end,today,"设施叶菜研究品种"));
          }
        }
        String actor=crew.get(index%crew.size());
        for(Season season:seasons) seedSeason(tenant,plot,actor,season,today,index);
        // Actual winter downtime is represented by inspection, not invented rice harvests.
        int week=0;
        for(LocalDate day=first.plusDays(index);day.isBefore(today);day=day.plusWeeks(1),week++) {
          String task=work.task(tenant,plot,actor,"周巡田：长势、沟渠与设施检查","INSPECTION",day,"COMPLETED","MANUAL","",
            NOTE+"已完成该地块巡查并记录设施状态。",day.atTime(16,0));
          if(week%8==3 && day.plusDays(1).isBefore(today)) work.issue(tenant,plot.get("ID").toString(),actor,task,index==2?"EQUIPMENT":"WATER","NORMAL",
            "合成巡田事件："+(index==2?"设施接头巡检后调整并复测。":"沟渠局部淤积，经清理后复查。"),"RESOLVED",
            day.atTime(7,0),crew.getLast(),day.plusDays(1).atTime(10,0));
        }
      }
      work.seedCurrent(tenant,plots,crew,today);
      for(var plot:plots.subList(0,2)) {
        if(today.isBefore(LocalDate.of(today.getYear(),5,15)) || today.isAfter(LocalDate.of(today.getYear(),9,25))) {
          db.update("UPDATE farm_tasks SET title='休耕期沟渠与排水复核' WHERE tenant_id=? AND plot_id=? AND title='复核一分区灌溉水位'",tenant,plot.get("ID"));
          db.update("UPDATE farm_tasks SET title='休耕期田间残株与虫源巡查' WHERE tenant_id=? AND plot_id=? AND title='二分区虫情巡检与人工处理'",tenant,plot.get("ID"));
          db.update("UPDATE farm_tasks SET title='下一季育秧与资源安排核对' WHERE tenant_id=? AND plot_id=? AND title='水稻收获前成熟度抽样'",tenant,plot.get("ID"));
          db.update("UPDATE farm_tasks SET title='预约下一季整地与机械服务',task_type='INSPECTION' WHERE tenant_id=? AND plot_id=? AND title='协调后续机械收获服务'",tenant,plot.get("ID"));
        }
      }
      db.update("UPDATE farm_tasks SET due_date=? WHERE tenant_id=? AND plot_id=? AND title='安排下一批叶菜采收与周转筐'",today.plusDays(40),tenant,plots.get(2).get("ID"));
      addSoilCoverage(tenant,id,plots);
      monitoring(tenant,id,first,today);
      db.update("INSERT INTO demo_research_farms(tenant_id,farm_id,version,history_start,as_of_date,generated_at) VALUES(?,?,2,?,?,?)",
        tenant,id,first,today,LocalDateTime.now());
      if(db.queryForObject("SELECT COUNT(*) FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?",Integer.class,tenant,id)==0)
        db.update("INSERT INTO demo_operating_farms(tenant_id,farm_id,seeded_on,last_daily_date) VALUES(?,?,?,?)",tenant,id,today,today);
      else db.update("UPDATE demo_operating_farms SET last_daily_date=? WHERE tenant_id=? AND farm_id=?",today,tenant,id);
      db.update("INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,'RESEARCH_GENERATOR','RESEARCH_V2_SEEDED',?,?)",uuid(),tenant,id,LocalDateTime.now());
    }
  }

  /** A legacy demo is replaceable only if its business rows have no user edit evidence. */
  private boolean prepareUntouchedLegacy(String tenant,String farm) {
    String scope="tenant_id=? AND plot_id IN (SELECT id FROM plots WHERE tenant_id=? AND farm_id=?)";
    Object[] args={tenant,tenant,farm};
    int total=db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE "+scope,Integer.class,args);
    if(total==0) return db.queryForObject("SELECT COUNT(*) FROM plantings WHERE "+scope,Integer.class,args)==0
      && db.queryForObject("SELECT COUNT(*) FROM production WHERE "+scope,Integer.class,args)==0
      && db.queryForObject("SELECT COUNT(*) FROM field_issues WHERE "+scope,Integer.class,args)==0;
    if(db.queryForObject("SELECT COUNT(*) FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?",Integer.class,tenant,farm)!=1) return false;
    if(db.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE "+scope+" AND note<>'虚构经营演示；用于流程展示，不代表实际生产或农艺处方。'",Integer.class,args)>0) return false;
    if(db.queryForObject("SELECT COUNT(*) FROM production WHERE "+scope+" AND note NOT LIKE '%虚构经营演示；用于流程展示，不代表实际生产或农艺处方。'",Integer.class,args)>0) return false;
    if(db.queryForObject("SELECT COUNT(*) FROM plantings WHERE "+scope+" AND variety NOT IN ('往季水稻演示品种','当季水稻演示品种','叶菜轮作演示品种','当期叶菜演示品种')",Integer.class,args)>0) return false;
    if(db.queryForObject("""
      SELECT COUNT(*) FROM audit_events a WHERE a.tenant_id=? AND (
        a.resource_id IN (SELECT id FROM farm_tasks WHERE tenant_id=? AND plot_id IN (SELECT id FROM plots WHERE tenant_id=? AND farm_id=?)) OR
        a.resource_id IN (SELECT id FROM plantings WHERE tenant_id=? AND plot_id IN (SELECT id FROM plots WHERE tenant_id=? AND farm_id=?)) OR
        a.resource_id IN (SELECT id FROM production WHERE tenant_id=? AND plot_id IN (SELECT id FROM plots WHERE tenant_id=? AND farm_id=?)) OR
        a.resource_id IN (SELECT id FROM field_issues WHERE tenant_id=? AND plot_id IN (SELECT id FROM plots WHERE tenant_id=? AND farm_id=?)))
      """,Integer.class,tenant,tenant,tenant,farm,tenant,tenant,farm,tenant,tenant,farm,tenant,tenant,farm)>0) return false;
    // Only the authenticated application's audited writes are supported; direct DB edits are outside this migration.
    db.update("DELETE FROM field_issues WHERE "+scope,args);
    for(String table:List.of("field_work_logs","task_fieldwork")) db.update("DELETE FROM "+table+" WHERE tenant_id=? AND task_id IN (SELECT id FROM farm_tasks WHERE "+scope+")",tenant,tenant,tenant,farm);
    for(String table:List.of("production","farm_tasks","plantings")) db.update("DELETE FROM "+table+" WHERE "+scope,args);
    return true;
  }

  private record Season(String id,LocalDate start,LocalDate end) {}
  private Season season(String tenant,Map<String,Object> plot,LocalDate start,LocalDate end,LocalDate today,String variety) {
    String id=uuid(),status=end.isBefore(today)?"FINISHED":start.isAfter(today)?"PLANNED":"ACTIVE";
    db.update("INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
      id,tenant,plot.get("ID"),plot.get("CROP"),variety,plot.get("AREA_MU"),start,end,status);
    return new Season(id,start,end);
  }
  private void seedSeason(String tenant,Map<String,Object> plot,String actor,Season season,LocalDate today,int index) {
    if(season.start.isAfter(today)) return;
    String[] types={"SOWING","IRRIGATION","FERTILIZING","INSPECTION","PROTECTION","IRRIGATION","FERTILIZING"};
    String[] titles={"播种与建苗验收","建苗期供水检查","生长阶段肥水作业","田间长势复查","病虫巡查与物理防护","中期灌溉与排水复查","后期养分与长势记录"};
    for(int step=0;step<types.length;step++) {
      LocalDate day=season.start.plusDays(step*6L);
      if(!day.isBefore(today) || day.isAfter(season.end)) continue;
      work.task(tenant,plot,actor,titles[step],types[step],day,"COMPLETED","MANUAL","",NOTE+"种植季内作业，完成面积与地块一致。",day.atTime(16,0));
    }
    if(!season.end.isBefore(today)) return;
    double totalPerMu=index<2?510+((season.end.getYear()*17+index*23)%95):1600+(season.end.getDayOfYear()*13%500);
    int batches=index<2?1:3;
    for(int batch=0;batch<batches;batch++) {
      LocalDate day=season.end.minusDays((batches-1-batch)*2L);
      BigDecimal yield=((BigDecimal)plot.get("AREA_MU")).multiply(BigDecimal.valueOf(totalPerMu/batches)).setScale(2,RoundingMode.HALF_UP);
      String task=work.task(tenant,plot,actor,(index<2?"水稻成熟收获":"设施叶菜分批采收")+" · "+(batch+1),"HARVEST",day,"COMPLETED",index<2?"SERVICE":"MANUAL","",
        NOTE+"称重入库 "+yield.toPlainString()+" kg；种植季、任务和产量台账已关联。",day.atTime(16,0));
      String production=uuid();
      db.update("INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",production,tenant,plot.get("ID"),day,yield,NOTE+"同季第 "+(batch+1)+" 批采收，产量为假设值。");
      db.update("INSERT INTO production_lineage(tenant_id,production_id,planting_id,task_id) VALUES(?,?,?,?)",tenant,production,season.id,task);
    }
  }

  private void addSoilCoverage(String tenant,String farm,List<Map<String,Object>> plots) {
    for(int i=0;i<plots.size();i++) {
      Object plot=plots.get(i).get("ID");
      if(db.queryForObject("SELECT COUNT(*) FROM asset_profiles p JOIN device_channels c ON c.tenant_id=p.tenant_id AND c.device_id=p.device_id WHERE p.tenant_id=? AND p.plot_id=? AND c.metric='SOIL_MOISTURE'",Integer.class,tenant,plot)>0) continue;
      String id=uuid();
      db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?, 'SOIL_MOISTURE','%','SIMULATED')",id,tenant,farm,"研究墒情点 · 分区 "+(i+1));
      db.update("""
        INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol,plot_id,plan_x,plan_y,model,notes,interval_seconds)
        VALUES(?,?,?,'SOIL','SIMULATED',?,?,?,'研究合成监测',?,1800)
        """,tenant,id,"DEMO-CTRL-SOIL-P"+(i+1),plot,160+i*310,350,NOTE);
      for(String metric:MetricCatalog.PRESETS.get("SOIL")) db.update("INSERT INTO device_channels(tenant_id,device_id,metric,lower_limit,upper_limit) VALUES(?,?,?,?,?)",
        tenant,id,metric,metric.equals("SOIL_MOISTURE")?20:null,metric.equals("SOIL_MOISTURE")?65:null);
    }
  }

  private void monitoring(String tenant,String farm,LocalDate first,LocalDate today) {
    var channels=db.queryForList("""
      SELECT d.id,c.metric,p.device_type,p.plot_id,q.name AS plot_name FROM devices d
      JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id
      JOIN device_channels c ON c.tenant_id=d.tenant_id AND c.device_id=d.id
      LEFT JOIN plots q ON q.tenant_id=p.tenant_id AND q.id=p.plot_id
      WHERE d.tenant_id=? AND d.farm_id=? AND p.protocol='SIMULATED' AND p.code LIKE 'DEMO-CTRL-%'
      """,tenant,farm);
    OffsetDateTime cutoff=OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
    ZoneId zone=ZoneId.systemDefault();
    for(var channel:channels) {
      String device=channel.get("ID").toString(),metric=channel.get("METRIC").toString();
      var existing=new HashSet<Instant>();
      db.query("SELECT measured_at FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=?",(org.springframework.jdbc.core.RowCallbackHandler)rs->existing.add(rs.getObject(1,OffsetDateTime.class).toInstant()),tenant,device,metric);
      var rows=new ArrayList<Object[]>();
      for(LocalDate day=first;!day.isAfter(today);day=day.plusDays(1)) {
        int samples=day.isBefore(today.minusDays(30))?1:48;
        for(int sample=0;sample<samples;sample++) {
          int minute=samples==1?12*60:sample*30;
          var at=day.atStartOfDay(zone).plusMinutes(minute).toOffsetDateTime();
          if(at.isAfter(cutoff)||existing.contains(at.toInstant())) continue;
          double value=ResearchSignals.reading(metric,day,minute/60.0,Objects.toString(channel.get("PLOT_NAME"),""));
          rows.add(new Object[]{uuid(),tenant,device,metric,BigDecimal.valueOf(value).setScale(3,RoundingMode.HALF_UP),at,at});
          if(rows.size()==500) { insertReadings(rows); rows.clear(); }
        }
      }
      if(!rows.isEmpty()) insertReadings(rows);
      if(Set.of("ENERGY","WATER_TOTAL").contains(metric)) {
        double maximum=0;
        var corrections=new ArrayList<Object[]>();
        for(var point:db.queryForList("SELECT id,measured_value FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=? AND source='SIMULATED' ORDER BY measured_at,id",tenant,device,metric)) {
          double value=((Number)point.get("MEASURED_VALUE")).doubleValue();
          maximum=Math.max(maximum,value);
          if(value<maximum) corrections.add(new Object[]{maximum,tenant,device,point.get("ID")});
        }
        if(!corrections.isEmpty()) db.batchUpdate("UPDATE telemetry_readings SET measured_value=? WHERE tenant_id=? AND device_id=? AND id=? AND source='SIMULATED'",corrections);
      }
    }
  }
  private void insertReadings(List<Object[]> rows) {
    db.batchUpdate("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')",rows);
  }
  private static String uuid() { return UUID.randomUUID().toString(); }
}
