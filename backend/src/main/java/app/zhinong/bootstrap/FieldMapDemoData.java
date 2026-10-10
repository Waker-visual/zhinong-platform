package app.zhinong.bootstrap;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in fictional device farm only. Image-traced geometry never replaces edited/user farm layouts. */
@Component @Order(60)
public class FieldMapDemoData implements ApplicationRunner {
  private final JdbcTemplate db;private final ObjectMapper json;private final boolean enabled;
  public FieldMapDemoData(JdbcTemplate db,ObjectMapper json,@Value("${farm.demo:false}") boolean demo,@Value("${farm.demo-rich:true}") boolean rich) {this.db=db;this.json=json;enabled=demo&&rich;}
  @Override @Transactional public void run(ApplicationArguments args) throws Exception {
    if(!enabled)return;
    JsonNode fixture=json.readTree(new ClassPathResource("farm-map-estimates.json").getInputStream());
    for(var tenant:db.queryForList("SELECT id FROM tenants WHERE code='demo-a' AND enabled=TRUE")) {
      String t=tenant.get("ID").toString();
      for(var farm:db.queryForList("""
        SELECT f.id,g.latitude,g.longitude,g.revision AS geo_revision,p.layout_revision
        FROM farms f JOIN farm_profiles p ON p.tenant_id=f.tenant_id AND p.farm_id=f.id
        JOIN farm_georeference g ON g.tenant_id=f.tenant_id AND g.farm_id=f.id
        WHERE f.tenant_id=? AND f.name='青禾设备联动演示场' AND p.demo=TRUE
        """,t)) {
        String f=farm.get("ID").toString();
        db.queryForList("SELECT id FROM farms WHERE tenant_id=? AND id=? FOR UPDATE",t,f);
        if(db.queryForObject("SELECT COUNT(*) FROM demo_scenarios WHERE tenant_id=? AND scenario='field-map-v1'",Integer.class,t)>0) {
          // Also invalidate a pre-upgrade editor in the first map-v1 build.
          db.update("UPDATE farm_profiles SET layout_revision=1 WHERE tenant_id=? AND farm_id=? AND layout_revision=0",t,f);
          continue;
        }
        if(((Number)farm.get("GEO_REVISION")).intValue()!=0 || ((Number)farm.get("LAYOUT_REVISION")).intValue()!=0
          || Math.abs(((Number)farm.get("LATITUDE")).doubleValue()-47.26)>0.000001 || Math.abs(((Number)farm.get("LONGITUDE")).doubleValue()-132.73)>0.000001)continue;
        var plots=db.queryForList("SELECT id FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY name",String.class,t,f);
        var pumps=db.queryForList("SELECT device_id FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id WHERE a.tenant_id=? AND d.farm_id=? AND a.code='DEMO-CTRL-PUMP' AND a.protocol='SIMULATED'",String.class,t,f);
        if(plots.size()!=3 || pumps.size()!=1)continue;
        var byPlot=new HashMap<String,List<JsonNode>>();
        for(var p:fixture.path("parcels")) {
          String plot=plots.get(p.path("plotIndex").asInt()),id=uuid(t+f+p.path("name").asText());
          byPlot.computeIfAbsent(plot,k->new ArrayList<>()).add(p.path("boundary"));
          db.update("INSERT INTO farm_map_parcels(id,tenant_id,farm_id,plot_id,name,boundary_json,source,source_note,created_at) VALUES(?,?,?,?,?,?,'IMAGERY_ESTIMATE',?,?)",
            id,t,f,plot,p.path("name").asText(),p.path("boundary").toString(),fixture.path("sourceNote").asText(),OffsetDateTime.now());
          db.update("INSERT INTO farm_map_zones(id,tenant_id,farm_id,parcel_id,pump_id,name,pipeline_json,nodes_json,source) VALUES(?,?,?,?,?,?,?,?,'SIMULATED')",
            uuid(id+"zone"),t,f,id,pumps.getFirst(),p.path("name").asText()+" 灌溉分区",p.path("pipeline").toString(),p.path("nodes").toString());
        }
        for(var d:db.queryForList("SELECT a.device_id,a.device_type,a.plot_id FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id WHERE a.tenant_id=? AND d.farm_id=? AND a.protocol='SIMULATED' AND a.code LIKE 'DEMO-CTRL-%' AND a.revision=0",t,f)) {
          var boundaries=byPlot.get(d.get("PLOT_ID").toString());if(boundaries==null)continue;
          String type=d.get("DEVICE_TYPE").toString();JsonNode point=fixture.path("devicePoints").path(type);
          if(point.isMissingNode()) {
            var ring=boundaries.get(type.equals("SOIL")?0:type.equals("WEATHER")?1:2);double lat=0,lng=0;
            for(var v:ring){lat+=v.get(0).asDouble();lng+=v.get(1).asDouble();}
            point=json.valueToTree(List.of(lat/ring.size(),lng/ring.size()));
          }
          db.update("UPDATE asset_profiles SET location_mode='WGS84',plan_x=NULL,plan_y=NULL,latitude=?,longitude=?,revision=revision+1 WHERE tenant_id=? AND device_id=? AND revision=0",
            point.get(0).asDouble(),point.get(1).asDouble(),t,d.get("DEVICE_ID"));
        }
        db.update("UPDATE farm_georeference SET mode='SATELLITE',location_label='建三江示例区域 · 田块影像估绘 / 设备与管网为模拟',revision=revision+1 WHERE tenant_id=? AND farm_id=?",t,f);
        db.update("UPDATE farm_profiles SET layout_revision=layout_revision+1 WHERE tenant_id=? AND farm_id=?",t,f);
        db.update("INSERT INTO demo_scenarios(tenant_id,scenario,created_at) VALUES(?,'field-map-v1',CURRENT_TIMESTAMP)",t);
      }
    }
  }
  private static String uuid(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();}
}
