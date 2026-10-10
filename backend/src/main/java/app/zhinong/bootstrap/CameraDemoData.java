package app.zhinong.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One-time expansion of the known fictional portfolio, preserving user sources and ordinary farms. */
@Component @Order(80)
public class CameraDemoData implements ApplicationRunner {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final boolean enabled;
  public CameraDemoData(JdbcTemplate db, ObjectMapper json, @Value("${farm.demo:false}") boolean demo) {
    this.db=db; this.json=json; enabled=demo;
  }
  @Override @Transactional public void run(ApplicationArguments args) {
    if (!enabled) return;
    var scenes=Map.of("青禾设备联动演示场","qinghe","丰穗农机演示场","fengsui",
      "新禾水田演示场","xinhe","润泽灌溉演示场","runze","田野稻作演示场","tianye");
    for (var tenant:db.queryForList("SELECT id FROM tenants WHERE code='demo-a' AND enabled=TRUE",String.class)) {
      db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE",tenant);
      for (var scene:scenes.entrySet()) {
        for (var farm:db.queryForList("""
          SELECT f.id FROM farms f JOIN farm_profiles p ON p.tenant_id=f.tenant_id AND p.farm_id=f.id
          WHERE f.tenant_id=? AND f.name=? AND p.demo=TRUE
          AND NOT EXISTS(SELECT 1 FROM farm_archives a WHERE a.tenant_id=f.tenant_id AND a.farm_id=f.id)
          """,String.class,tenant,scene.getKey())) {
          String migration="cam2"+farm; // 40 chars, compatible with the existing scenario column.
          if (db.queryForObject("SELECT COUNT(*) FROM demo_scenarios WHERE tenant_id=? AND scenario=?",Integer.class,tenant,migration)>0) continue;
          String firstLabel="01 · "+sceneLabel(scene.getValue());
        db.update("""
          INSERT INTO camera_profiles(tenant_id,device_id,media_mode,demo_scene,source_url,view_label)
          SELECT d.tenant_id,d.id,'DEMO_IMAGE',?,'',?
          FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
          WHERE d.tenant_id=? AND d.farm_id=? AND a.device_type='CAMERA'
          AND a.protocol='SIMULATED' AND a.code LIKE 'DEMO-CTRL-%'
          AND NOT EXISTS(SELECT 1 FROM camera_profiles c WHERE c.tenant_id=d.tenant_id AND c.device_id=d.id)
          """,scene.getValue(),firstLabel,tenant,farm);
          // Only replace untouched legacy fixture wording. A configured video, title or installation stays intact.
          for (var row:db.queryForList("""
            SELECT d.id,d.name,c.view_label,c.media_mode FROM devices d
            JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
            JOIN camera_profiles c ON c.tenant_id=d.tenant_id AND c.device_id=d.id
            WHERE d.tenant_id=? AND d.farm_id=? AND a.device_type='CAMERA'
            AND a.protocol='SIMULATED' AND a.code LIKE 'DEMO-CTRL-%' AND c.media_mode='DEMO_IMAGE'
            """,tenant,farm)) {
            String id=row.get("ID").toString();boolean changed=false;
            if (Set.of("演示视频监测","模拟视频监测").contains(row.get("NAME").toString())) {
              db.update("UPDATE devices SET name='田间摄像头 01' WHERE tenant_id=? AND id=?",tenant,id);changed=true;
            }
            if ("田间固定机位 · 合成演示".equals(row.get("VIEW_LABEL"))) {
              db.update("UPDATE camera_profiles SET view_label=? WHERE tenant_id=? AND device_id=?",firstLabel,tenant,id);changed=true;
            }
            if(changed)db.update("UPDATE asset_profiles SET revision=revision+1 WHERE tenant_id=? AND device_id=?",tenant,id);
          }
          var cameras=db.queryForList("SELECT d.id,a.plot_id FROM devices d JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id WHERE d.tenant_id=? AND d.farm_id=? AND a.device_type='CAMERA'",tenant,farm);
          var plots=db.queryForList("SELECT id FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY name,id",String.class,tenant,farm);
          var usedPlots=new HashSet<String>();for(var c:cameras)if(c.get("PLOT_ID")!=null)usedPlots.add(c.get("PLOT_ID").toString());
          var media=new ArrayList<>(List.of(scene.getValue()));
          for(String s:List.of("runze","tianye","qinghe"))if(!media.contains(s))media.add(s);
          for(int slot=cameras.size()+1;slot<=3;slot++) {
            String plot=plots.stream().filter(p->!usedPlots.contains(p)).findFirst().orElse(plots.isEmpty()?null:plots.get((slot-1)%plots.size()));
            if(plot!=null)usedPlots.add(plot);
            addCamera(tenant,farm,plot,slot,media.get(slot-1));
          }
          db.update("INSERT INTO demo_scenarios(tenant_id,scenario,created_at) VALUES(?,?,CURRENT_TIMESTAMP)",tenant,migration);
        }
      }
    }
  }
  private static String sceneLabel(String scene) {
    return switch(scene) {case "runze" -> "灌溉渠观察";case "tianye" -> "场区道路";case "fengsui" -> "稻田全景";case "xinhe" -> "育苗区观察";default -> "田块长势";};
  }
  private void addCamera(String tenant,String farm,String plot,int slot,String scene) {
    String id=UUID.nameUUIDFromBytes((tenant+":"+farm+":camera:"+slot).getBytes(StandardCharsets.UTF_8)).toString();
    var now=OffsetDateTime.now();
    db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,'CAMERA_ONLINE','','SIMULATED')",id,tenant,farm,String.format("田间摄像头 %02d",slot));
    db.update("""
      INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol,plot_id,location_mode,model,notes,interval_seconds,last_received_at,control_enabled)
      VALUES(?,?,?,'CAMERA','SIMULATED',?,'LOCAL_PLAN','田间固定式摄像头','虚构机位与状态；画面可在台账配置',900,?,FALSE)
      """,tenant,id,"DEMO-CTRL-ZCAM-"+farm+"-"+slot,plot,now);
    if(plot!=null) {
      var boundaries=db.queryForList("SELECT boundary_json FROM farm_map_parcels WHERE tenant_id=? AND farm_id=? AND plot_id=? ORDER BY name,id",String.class,tenant,farm,plot);
      if(!boundaries.isEmpty()) {
        double[] point=point(boundaries.getFirst());
        db.update("UPDATE asset_profiles SET location_mode='WGS84',latitude=?,longitude=? WHERE tenant_id=? AND device_id=?",point[0],point[1],tenant,id);
      } else {
        var shapes=db.queryForList("SELECT boundary_json FROM plot_shapes WHERE tenant_id=? AND plot_id=?",String.class,tenant,plot);
        if(!shapes.isEmpty()) {
          double[] point=point(shapes.getFirst());
          db.update("UPDATE asset_profiles SET location_mode='LOCAL_PLAN',plan_x=?,plan_y=? WHERE tenant_id=? AND device_id=?",Math.max(0,Math.min(1000,point[0])),Math.max(0,Math.min(700,point[1])),tenant,id);
        }
      }
    }
    db.update("INSERT INTO device_channels(tenant_id,device_id,metric) VALUES(?,?,'CAMERA_ONLINE')",tenant,id);
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,'CAMERA_ONLINE',1,?,?,'SIMULATED')",UUID.randomUUID().toString(),tenant,id,now,now);
    db.update("INSERT INTO camera_profiles(tenant_id,device_id,media_mode,demo_scene,source_url,view_label) VALUES(?,?,'DEMO_IMAGE',?,'',?)",tenant,id,scene,String.format("%02d · %s",slot,sceneLabel(scene)));
  }
  private double[] point(String boundary) {
    try {
      var ring=json.readTree(boundary);double a=0,b=0;
      for(var p:ring){a+=p.get(0).asDouble();b+=p.get(1).asDouble();}
      // A position just inside a field edge, rather than placing all cameras at the field center.
      return new double[]{(ring.get(0).get(0).asDouble()+ring.get(1).get(0).asDouble())*.45+a/ring.size()*.1,
        (ring.get(0).get(1).asDouble()+ring.get(1).get(1).asDouble())*.45+b/ring.size()*.1};
    } catch(Exception e){throw new IllegalStateException("Invalid saved camera plot geometry",e);}
  }
}
