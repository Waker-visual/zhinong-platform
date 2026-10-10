package app.zhinong.bootstrap;

import app.zhinong.workspace.FieldGeometry;
import app.zhinong.workspace.MetricCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, one-time fictional portfolio installation. Never deletes a farm or replaces user geometry. */
@Component @Order(70)
public class FarmPortfolioDemoData implements ApplicationRunner {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ResearchDemoData research;
  private final OperatingDemoData operating;
  private final boolean enabled;
  public FarmPortfolioDemoData(JdbcTemplate db,ObjectMapper json,ResearchDemoData research,OperatingDemoData operating,
      @Value("${farm.demo:false}") boolean demo,@Value("${farm.demo-rich:true}") boolean rich,
      @Value("${farm.demo-portfolio:false}") boolean portfolio) {
    this.db=db;this.json=json;this.research=research;this.operating=operating;enabled=demo&&rich&&portfolio;
  }
  @Override @Transactional public void run(ApplicationArguments args) throws Exception {
    if(!enabled)return;
    var fixture=json.readTree(new ClassPathResource("farm-portfolio-estimates.json").getInputStream());
    for(String t:db.queryForList("SELECT id FROM tenants WHERE code='demo-a' AND enabled=TRUE",String.class)) {
      db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE",t);
      if(db.queryForObject("SELECT COUNT(*) FROM demo_scenarios WHERE tenant_id=? AND scenario='farm-portfolio-v1'",Integer.class,t)>0)continue;
      // Only known retired scenarios are hidden. Arbitrarily named customer farms are untouched.
      for(var f:db.queryForList("SELECT id,name FROM farms WHERE tenant_id=?",t)) {
        String name=f.get("NAME").toString();
        if(Set.of("晴川示范园","溪谷设施示范农场","青禾综合示范农场").contains(name)||name.matches("验收农场-\\d+"))
          db.update("INSERT INTO farm_archives(tenant_id,farm_id,reason,archived_at) SELECT ?,?,'旧演示方案归档；经营与设备记录完整保留',? WHERE NOT EXISTS(SELECT 1 FROM farm_archives WHERE tenant_id=? AND farm_id=?)",t,f.get("ID"),OffsetDateTime.now(),t,f.get("ID"));
      }
      for(var f:db.queryForList("SELECT f.id FROM farms f JOIN farm_profiles p ON p.tenant_id=f.tenant_id AND p.farm_id=f.id WHERE f.tenant_id=? AND f.name='青禾设备联动演示场' AND p.demo=TRUE",t))upgradeMachines(t,f.get("ID").toString(),"QH",true);
      for(var f:fixture.path("farms"))createFarm(t,f);
      db.update("INSERT INTO demo_scenarios(tenant_id,scenario,created_at) VALUES(?,'farm-portfolio-v1',CURRENT_TIMESTAMP)",t);
    }
    research.initialize();
    operating.tick();
  }
  private void createFarm(String t,JsonNode f) throws Exception {
    String key=f.path("key").asText(),farm=id(t+"portfolio:"+key);
    if(db.queryForObject("SELECT COUNT(*) FROM farms WHERE tenant_id=? AND id=?",Integer.class,t,farm)>0)return;
    db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,?)",farm,t,f.path("name").asText(),"学术合成农场；独立影像估绘田块、四类模拟农机及模拟灌溉管网。作物与经营记录为假设情景，非实测生产数据。");
    db.update("INSERT INTO farm_profiles(tenant_id,farm_id,region,farm_type,demo,layout_revision) VALUES(?,?,'建三江示例区域 · 影像估绘','MIXED',TRUE,1)",t,farm);
    db.update("INSERT INTO farm_georeference(tenant_id,farm_id,mode,latitude,longitude,width_meters,height_meters,location_label,revision) VALUES(?,?,'SATELLITE',?,?,?,?,?,1)",t,farm,f.path("latitude").asDouble(),f.path("longitude").asDouble(),1600,1200,"田块估绘；设备和管网为虚构布置");
    var plots=new ArrayList<String>();
    for(int i=0;i<3;i++) {
      String plot=id(farm+"plot"+i);plots.add(plot);double area=0;
      var outline=new ArrayList<List<Double>>();
      for(var p:f.path("parcels"))if(p.path("plotIndex").asInt()==i){var ring=json.convertValue(p.path("boundary"),new TypeReference<List<List<Double>>>(){});area+=FieldGeometry.areaMu(ring);outline.addAll(ring);}
      db.update("INSERT INTO plots(id,tenant_id,farm_id,name,area_mu,crop) VALUES(?,?,?,?,?,?)",plot,t,farm,"演示分区 "+(i+1),Math.round(area*100)/100.0,i==2?"蔬菜":"水稻");
      // Thumbnail envelope only; actual map renders the individually traced subdivisions.
      double minLat=outline.stream().mapToDouble(p->p.get(0)).min().orElseThrow(),maxLat=outline.stream().mapToDouble(p->p.get(0)).max().orElseThrow();
      double minLng=outline.stream().mapToDouble(p->p.get(1)).min().orElseThrow(),maxLng=outline.stream().mapToDouble(p->p.get(1)).max().orElseThrow();
      var box=new ArrayList<List<Double>>();for(var v:List.of(List.of(minLat,minLng),List.of(minLat,maxLng),List.of(maxLat,maxLng),List.of(maxLat,minLng)))
        box.add(List.of(500+(v.get(1)-f.path("longitude").asDouble())*111320*Math.cos(Math.toRadians(f.path("latitude").asDouble()))/1600*1000,350-(v.get(0)-f.path("latitude").asDouble())*111320/1200*700));
      db.update("INSERT INTO plot_shapes(tenant_id,plot_id,boundary_json) VALUES(?,?,?)",t,plot,json.writeValueAsString(box));
    }
    String pump=null;
    String[] types={"WEATHER","SOIL","WATER","PEST","GATE","PUMP","CAMERA","GATEWAY","SOIL","SOIL"};
    for(int i=0;i<types.length;i++) {
      var parcel=f.path("parcels").get(i==9?3:i%9);var ring=parcel.path("boundary");double lat=0,lng=0;for(var point:ring){lat+=point.get(0).asDouble();lng+=point.get(1).asDouble();}lat/=ring.size();lng/=ring.size();
      if(types[i].equals("PUMP")){lat=f.path("pump").get(0).asDouble();lng=f.path("pump").get(1).asDouble();}
      String d=addDevice(t,farm,plots.get(parcel.path("plotIndex").asInt()),"DEMO-CTRL-"+key.toUpperCase(Locale.ROOT)+"-"+types[i]+i,types[i],"模拟"+MetricCatalog.TYPES.get(types[i]),lat,lng);
      if(types[i].equals("PUMP"))pump=d;
    }
    for(var p:f.path("parcels")) {
      String parcel=id(farm+p.path("name").asText());
      db.update("INSERT INTO farm_map_parcels(id,tenant_id,farm_id,plot_id,name,boundary_json,source,source_note,created_at) VALUES(?,?,?,?,?,?,'IMAGERY_ESTIMATE',?,?)",parcel,t,farm,plots.get(p.path("plotIndex").asInt()),p.path("name").asText(),p.path("boundary").toString(),f.path("sourceNote").asText(),OffsetDateTime.now());
      db.update("INSERT INTO farm_map_zones(id,tenant_id,farm_id,parcel_id,pump_id,name,pipeline_json,nodes_json,source) VALUES(?,?,?,?,?,?,?,?,'SIMULATED')",id(parcel+"water"),t,farm,parcel,pump,p.path("name").asText()+" 灌溉分区",p.path("pipeline").toString(),p.path("nodes").toString());
    }
    upgradeMachines(t,farm,key.toUpperCase(Locale.ROOT),false);
    db.update("INSERT INTO demo_portfolio_farms(tenant_id,farm_id,fixture_key) VALUES(?,?,?)",t,farm,key);
  }
  private void upgradeMachines(String t,String farm,String key,boolean original) throws Exception {
    var parcels=db.queryForList("SELECT plot_id,boundary_json FROM farm_map_parcels WHERE tenant_id=? AND farm_id=? ORDER BY name",t,farm);if(parcels.isEmpty())return;
    String[] kinds={"TRACTOR","SPRAYER","HARVESTER","DRONE"},names={"柳工1004","世昌3WPZ-1500F","洋马1180R","大疆T100"},brands={"柳工","世昌","洋马","大疆"};
    Integer[] hp={100,50,120,null};double[] widths={3,8,4,8},speeds={4,5,4,15},edges={5,5,6,3};
    for(int i=0;i<4;i++) {
      var p=parcels.get(i%parcels.size());var ring=json.readTree(p.get("BOUNDARY_JSON").toString());double lat=0,lng=0;for(var v:ring){lat+=v.get(0).asDouble();lng+=v.get(1).asDouble();}lat/=ring.size();lng/=ring.size();
      var old=original&&i==0?db.queryForList("SELECT a.device_id FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id WHERE a.tenant_id=? AND d.farm_id=? AND a.code='DEMO-CTRL-MACHINERY' AND a.protocol='SIMULATED'",String.class,t,farm):List.<String>of();
      String d=old.isEmpty()?addDevice(t,farm,p.get("PLOT_ID").toString(),"DEMO-CTRL-"+key+"-"+kinds[i],"MACHINERY",names[i]+" · 模拟",lat,lng):old.getFirst();
      db.update("UPDATE devices SET name=? WHERE tenant_id=? AND id=?",names[i]+" · 模拟",t,d);
      db.update("UPDATE asset_profiles SET model=?,revision=revision+1 WHERE tenant_id=? AND device_id=?",names[i],t,d);
      var profile=new LinkedHashMap<String,Object>();profile.put("kind",kinds[i]);profile.put("brand",brands[i]);profile.put("model",names[i]);profile.put("horsepower",hp[i]);
      profile.put("serialNumber",original?(i==0?"925520100123":i==1?"9255201000TQ":""):"");profile.put("internalId","SIM-"+key+"-"+(i+1));
      profile.put("widthMeters",widths[i]);profile.put("speedKmh",speeds[i]);profile.put("headlandMeters",edges[i]);profile.put("altitudeMeters",i==3?4:null);
      profile.put("specSource","型号、品牌和已填写马力来自用户；缺失编号未补造。运行参数为待现场核定的演示预设，非厂家性能保证。");
      profile.put("taskTypes",switch(kinds[i]){case "TRACTOR"->List.of("INSPECTION","SOWING","FERTILIZING");case "SPRAYER"->List.of("PROTECTION","FERTILIZING");case "HARVESTER"->List.of("HARVEST");default->List.of("PROTECTION","INSPECTION","FERTILIZING");});
      db.update("INSERT INTO machinery_profiles(tenant_id,device_id,profile_json) VALUES(?,?,?)",t,d,json.writeValueAsString(profile));
    }
  }
  private String addDevice(String t,String f,String plot,String code,String type,String name,double lat,double lng) {
    String d=id(t+code);var metrics=MetricCatalog.PRESETS.get(type);var primary=MetricCatalog.get(metrics.getFirst());var now=OffsetDateTime.now();
    db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,'SIMULATED')",d,t,f,name,primary.code(),primary.unit());
    db.update("INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol,plot_id,location_mode,latitude,longitude,model,notes,interval_seconds,last_received_at,control_enabled) VALUES(?,?,?,?,'SIMULATED',?,'WGS84',?,?,'学术演示','虚构设备布置与反馈',900,?,?)",t,d,code,type,plot,lat,lng,now,Set.of("PUMP","GATE").contains(type));
    for(String metric:metrics) {
      db.update("INSERT INTO device_channels(tenant_id,device_id,metric,lower_limit,upper_limit) VALUES(?,?,?,?,?)",t,d,metric,metric.equals("SOIL_MOISTURE")?20:null,metric.equals("FAULT")?0:null);
      double value=MetricCatalog.get(metric).normal();if(Set.of("PUMP_RUNNING","SPEED","FLOW","FAULT").contains(metric))value=0;
      db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')",UUID.randomUUID().toString(),t,d,metric,value,now,now);
    }
    return d;
  }
  private static String id(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();}
}
