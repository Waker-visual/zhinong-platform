package app.zhinong.bootstrap;

import app.zhinong.workspace.MetricCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Entirely invented calibration scenario; never imports customer data or vendor identifiers. */
@Component
@Order(30)
public class ControlDemoData implements ApplicationRunner {
  private final JdbcTemplate db;
  private final boolean enabled;
  public ControlDemoData(JdbcTemplate db, @Value("${farm.demo:false}") boolean demo,
      @Value("${farm.demo-rich:true}") boolean rich) {
    this.db = db;
    enabled = demo && rich;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!enabled) return;
    for (String tenant : db.queryForList("SELECT id FROM tenants WHERE code IN ('demo-a','demo-b')", String.class)) {
      if (db.queryForObject("SELECT COUNT(*) FROM demo_scenarios WHERE tenant_id=? AND scenario='device-control-v1'", Long.class, tenant) > 0) continue;
      seed(tenant);
      db.update("INSERT INTO demo_scenarios(tenant_id,scenario,created_at) VALUES(?,'device-control-v1',CURRENT_TIMESTAMP)", tenant);
    }
  }

  private void seed(String tenant) {
    String farm = UUID.randomUUID().toString();
    db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,?)", farm, tenant,
      "青禾设备联动演示场", "虚构演示：气象、墒情、水情、虫情、闸门和泵房联动；全部位置与数值均为构造数据，未接入生产设备。");
    db.update("INSERT INTO farm_profiles(tenant_id,farm_id,region,farm_type,demo) VALUES(?,?,?,'MIXED',TRUE)",
      tenant, farm, "虚构校准场景");
    db.update("""
      INSERT INTO farm_georeference(tenant_id,farm_id,mode,latitude,longitude,width_meters,height_meters,location_label)
      VALUES(?,?,'PLAN',47.26,132.73,1600,1120,'虚构设备布局 · 公开区域示意，不代表实际安装位置')
      """, tenant, farm);
    var plots = new ArrayList<String>();
    for (int i = 0; i < 3; i++) {
      String plot = UUID.randomUUID().toString();
      plots.add(plot);
      db.update("INSERT INTO plots(id,tenant_id,farm_id,name,area_mu,crop) VALUES(?,?,?,?,?,?)", plot, tenant, farm,
        "演示分区 " + (i + 1), 20 + i * 5, i == 2 ? "蔬菜" : "水稻");
      int x = 60 + i * 310;
      db.update("INSERT INTO plot_shapes(tenant_id,plot_id,boundary_json) VALUES(?,?,?)", tenant, plot,
        "[[" + x + ",100],[" + (x + 270) + ",100],[" + (x + 270) + ",560],[" + x + ",560]]");
    }
    String[] types = { "WEATHER", "SOIL", "WATER", "PEST", "GATE", "PUMP", "CAMERA", "MACHINERY", "GATEWAY" };
    var now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    for (int i = 0; i < types.length; i++) {
      String type = types[i], device = UUID.randomUUID().toString();
      var metrics = MetricCatalog.PRESETS.get(type);
      var primary = MetricCatalog.get(metrics.getFirst());
      db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,'SIMULATED')",
        device, tenant, farm, "演示" + MetricCatalog.TYPES.get(type), primary.code(), primary.unit());
      db.update("""
        INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol,plot_id,plan_x,plan_y,
          model,notes,interval_seconds,last_received_at,control_enabled)
        VALUES(?,?,?,?,'SIMULATED',?,?,?,'通用能力演示','全部设备及数据为虚构，控制仅改变本地模拟反馈',900,?,?)
        """, tenant, device, "DEMO-CTRL-" + type, type, plots.get(i % 3), 160 + (i % 3) * 310,
        170 + (i / 3) * 160, now, type.equals("GATE") || type.equals("PUMP"));
      if (type.equals("GATE") || type.equals("PUMP")) {
        double x = 160 + (i % 3) * 310, y = 170 + (i / 3) * 160;
        db.update("UPDATE asset_profiles SET plan_x=NULL,plan_y=NULL,location_mode='WGS84',latitude=?,longitude=? WHERE tenant_id=? AND device_id=?",
          47.26 + (0.5 - y / 700) * 1120 / 111320,
          132.73 + (x / 1000 - 0.5) * 1600 / (111320 * Math.cos(Math.toRadians(47.26))), tenant, device);
      }
      for (String metric : metrics) {
        var spec = MetricCatalog.get(metric);
        db.update("INSERT INTO device_channels(tenant_id,device_id,metric,lower_limit,upper_limit) VALUES(?,?,?,?,?)",
          tenant, device, metric, metric.equals("SOIL_MOISTURE") ? 20 : null, metric.equals("FAULT") ? 0 : null);
        var rows = new ArrayList<Object[]>();
        for (int step = 96; step >= 0; step--) {
          double value = spec.normal();
          if (Set.of("TEMPERATURE", "HUMIDITY", "SOIL_MOISTURE", "SOIL_TEMPERATURE", "AIR_PRESSURE", "WIND_SPEED").contains(metric)) {
            value += Math.sin(step / 8.0 + i) * Math.max(0.5, value * 0.05);
          }
          if (type.equals("PUMP") && metric.equals("FLOW")) value = 0;
          if (MetricCatalog.discrete(metric)) value = Math.round(value);
          var time = now.minusMinutes(step * 15L);
          rows.add(new Object[] { UUID.randomUUID().toString(), tenant, device, metric,
            BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP), time, time });
        }
        db.batchUpdate("""
          INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source)
          VALUES(?,?,?,?,?,?,?,'SIMULATED')
          """, rows);
      }
    }
  }
}
