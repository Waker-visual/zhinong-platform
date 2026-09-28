package app.zhinong.bootstrap;

import app.zhinong.workspace.MetricCatalog;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Additive fixtures. Existing records are never overwritten; scenario markers prevent duplicates. */
@Component
@Order(20)
public class WorkspaceData implements ApplicationRunner {

  private final JdbcTemplate db;
  private final boolean demo;

  public WorkspaceData(
    JdbcTemplate db,
    @Value("${farm.demo:false}") boolean demo,
    @Value("${farm.demo-rich:true}") boolean rich
  ) {
    this.db = db;
    this.demo = demo && rich;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    db.update(
      """
      INSERT INTO farm_profiles(tenant_id,farm_id,demo)
      SELECT tenant_id,id,description LIKE '虚构演示%' FROM farms f WHERE NOT EXISTS
      (SELECT 1 FROM farm_profiles p WHERE p.tenant_id=f.tenant_id AND p.farm_id=f.id)
      """
    );
    db.update(
      """
      INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol)
      SELECT tenant_id,id,CONCAT('POINT-',id),'OTHER',adapter FROM devices d WHERE NOT EXISTS
      (SELECT 1 FROM asset_profiles p WHERE p.tenant_id=d.tenant_id AND p.device_id=d.id)
      """
    );
    db.update(
      """
      INSERT INTO device_channels(tenant_id,device_id,metric)
      SELECT tenant_id,id,metric FROM devices d WHERE NOT EXISTS
      (SELECT 1 FROM device_channels c WHERE c.tenant_id=d.tenant_id AND c.device_id=d.id AND c.metric=d.metric)
      """
    );
    db.update(
      """
      UPDATE asset_profiles p SET last_received_at=(SELECT CAST(MAX(o.measured_at) AS TIMESTAMP WITH TIME ZONE)
      FROM observations o WHERE o.tenant_id=p.tenant_id AND o.device_id=p.device_id)
      WHERE p.last_received_at IS NULL AND EXISTS
      (SELECT 1 FROM observations o WHERE o.tenant_id=p.tenant_id AND o.device_id=p.device_id)
      """
    );
    if (!demo) return;
    for (var tenant : db.queryForList(
      "SELECT id,code FROM tenants WHERE code IN ('demo-a','demo-b')"
    )) {
      String id = tenant.get("ID").toString();
      if (
        db.queryForObject(
          "SELECT COUNT(*) FROM demo_scenarios WHERE tenant_id=? AND scenario='farm-workspace-v1'",
          Long.class,
          id
        ) >
        0
      ) continue;
      seedFarm(
        id,
        tenant.get("CODE").equals("demo-a") ? "青禾综合示范农场" : "云岭生态示范农场",
        false
      );
      if (tenant.get("CODE").equals("demo-a")) seedFarm(id, "溪谷设施示范农场", true);
      db.update(
        "INSERT INTO demo_scenarios(tenant_id,scenario,created_at) VALUES(?,'farm-workspace-v1',?)",
        id,
        OffsetDateTime.now(ZoneOffset.UTC)
      );
    }
  }

  private void seedFarm(String tenant, String name, boolean greenhouse) {
    String farm = id();
    db.update(
      "INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,?)",
      farm,
      tenant,
      name,
      "虚构示范场景：用于验证地图、监测趋势、生产统计与告警流程，非真实经营数据。"
    );
    db.update(
      "INSERT INTO farm_profiles(tenant_id,farm_id,region,farm_type,demo) VALUES(?,?,?,?,TRUE)",
      tenant,
      farm,
      "示范区域 · 本地平面坐标",
      greenhouse ? "GREENHOUSE" : "MIXED"
    );
    String[] crops = greenhouse
      ? new String[] { "番茄", "番茄", "黄瓜", "生菜", "生菜", "草莓" }
      : new String[] { "水稻", "水稻", "小麦", "玉米", "蔬菜", "蔬菜" };
    double[] areas = greenhouse
      ? new double[] { 12, 12, 10, 8, 8, 6 }
      : new double[] { 62, 48, 36, 28, 20, 16 };
    String[] plots = new String[6];
    for (int i = 0; i < 6; i++) {
      plots[i] = id();
      String plotName =
        (greenhouse ? "设施" : "生产") + "区 " + (char) ('A' + i) + " · " + crops[i];
      db.update(
        "INSERT INTO plots(id,tenant_id,farm_id,name,area_mu,crop) VALUES(?,?,?,?,?,?)",
        plots[i],
        tenant,
        farm,
        plotName,
        areas[i],
        crops[i]
      );
      double x = 70 + (i % 3) * 285,
        y = 90 + (i / 3) * 295;
      String geometry =
        "[[" +
        x +
        "," +
        y +
        "],[" +
        (x + 250) +
        "," +
        (y + 8) +
        "],[" +
        (x + 242) +
        "," +
        (y + 210) +
        "],[" +
        (x + 4) +
        "," +
        (y + 198) +
        "]]";
      db.update(
        "INSERT INTO plot_shapes(tenant_id,plot_id,boundary_json) VALUES(?,?,?)",
        tenant,
        plots[i],
        geometry
      );
      db.update(
        "INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
        id(),
        tenant,
        plots[i],
        crops[i],
        "演示品种",
        areas[i],
        LocalDate.now().minusDays(55 + i * 3),
        LocalDate.now().plusDays(35 + i * 4),
        "ACTIVE"
      );
      int harvests = greenhouse || i >= 4 ? 8 : 2;
      for (int h = 0; h < harvests; h++) {
        int ago = harvests == 2 ? 12 + h * 160 : 4 + h * 10 + i;
        double kg = areas[i] * (harvests == 2 ? 520 + i * 15 : 210 + h * 18);
        db.update(
          "INSERT INTO production(id,tenant_id,plot_id,record_date,yield_kg,note) VALUES(?,?,?,?,?,?)",
          id(),
          tenant,
          plots[i],
          LocalDate.now().minusDays(ago),
          kg,
          "虚构演示产量记录：用于统计交互验证"
        );
      }
      for (int j = 0; j < 5; j++) {
        String status = new String[] {
          "COMPLETED",
          "COMPLETED",
          "RUNNING",
          "PENDING",
          "CANCELLED",
        }[(i + j) % 5];
        String type = new String[] {
          "INSPECTION",
          "IRRIGATION",
          "FERTILIZING",
          "HARVEST",
          "SOWING",
        }[j];
        db.update(
          "INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,?,?,?,?,?)",
          id(),
          tenant,
          plots[i],
          plotName +
            " / " +
            new String[] { "苗情巡查", "水分补给", "追肥记录", "分区采收", "补植安排" }[j],
          type,
          LocalDate.now().plusDays(j - 3 + (i % 3)),
          status,
          "虚构演示农事；按实际业务录入时请使用真实记录"
        );
      }
    }
    String[][] metrics = {
      { "TEMPERATURE", "HUMIDITY", "LIGHT" },
      { "TEMPERATURE", "HUMIDITY" },
      { "SOIL_MOISTURE", "SOIL_TEMPERATURE", "PH" },
      { "SOIL_MOISTURE", "PH" },
      { "SOIL_MOISTURE", "SOIL_TEMPERATURE" },
      { "WATER_LEVEL", "FLOW" },
      { "WATER_LEVEL" },
      { "BATTERY" },
    };
    String[] types = { "WEATHER", "WEATHER", "SOIL", "SOIL", "SOIL", "WATER", "WATER", "GATEWAY" };
    for (int i = 0; i < 8; i++) {
      String device = id(),
        protocol = i == 7 ? "HTTP_PUSH" : i == 3 ? "MANUAL" : "SIMULATED";
      var first = MetricCatalog.get(metrics[i][0]);
      db.update(
        "INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,?)",
        device,
        tenant,
        farm,
        MetricCatalog.TYPES.get(types[i]) + " · " + String.format("%02d", i + 1),
        first.code(),
        first.unit(),
        protocol.equals("SIMULATED") ? "SIMULATED" : "MANUAL"
      );
      var end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(i == 5 ? 360 : 2);
      int plotIndex = i % 6;
      db.update(
        """
        INSERT INTO asset_profiles(tenant_id,device_id,code,device_type,protocol,lifecycle,plot_id,
          plan_x,plan_y,model,notes,interval_seconds,last_received_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        tenant,
        device,
        "DEMO-" + farm.substring(0, 6) + "-" + (i + 1),
        types[i],
        protocol,
        i == 6 ? "MAINTENANCE" : "ACTIVE",
        plots[plotIndex],
        175 + (plotIndex % 3) * 285 + (i / 6) * 35,
        180 + (plotIndex / 3) * 295 + (i / 6) * 30,
        "通用多指标终端",
        "虚构设备；点位与历史数值均为演示数据",
        900,
        i == 7 ? null : end
      );
      for (String metric : metrics[i]) {
        var spec = MetricCatalog.get(metric);
        Double lower = metric.equals("SOIL_MOISTURE") ? 20d : null;
        Double upper = metric.equals("TEMPERATURE") ? 32d : null;
        db.update(
          "INSERT INTO device_channels(tenant_id,device_id,metric,lower_limit,upper_limit) VALUES(?,?,?,?,?)",
          tenant,
          device,
          metric,
          lower,
          upper
        );
        if (i == 7) continue;
        var readings = new ArrayList<Object[]>();
        for (int h = 14 * 24; h >= 0; h--) {
          double amplitude = Math.max(0.3, spec.normal() * 0.17);
          double value =
            spec.normal() +
            Math.sin((14 * 24 - h) / 3.8 + i) * amplitude +
            Math.cos(h / 13.0) * amplitude * 0.3;
          if (metric.equals("LIGHT")) value = Math.max(
            0,
            value * Math.sin(Math.PI * ((24 - (h % 24)) / 24.0))
          );
          if (i == 2 && metric.equals("SOIL_MOISTURE") && h < 5) value = 14 + h * 0.8;
          value = Math.max(spec.min(), Math.min(spec.max(), value));
          readings.add(
            new Object[] {
              id(),
              tenant,
              device,
              metric,
              BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP),
              end.minusHours(h),
              end.minusHours(h),
              "SIMULATED",
            }
          );
        }
        db.batchUpdate(
          "INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,?)",
          readings
        );
        if (i == 2 && metric.equals("SOIL_MOISTURE")) {
          db.update(
            "INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            id(),
            tenant,
            device,
            metric,
            14,
            "土壤水分低于下限 20（模拟场景）",
            end.minusHours(4),
            end
          );
        }
      }
    }
  }

  private String id() {
    return UUID.randomUUID().toString();
  }
}
