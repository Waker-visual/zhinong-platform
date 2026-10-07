package app.zhinong.bootstrap;

import app.zhinong.business.TenantService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Only synthetic fixture data. Enabled explicitly; no production data import. */
@Component
@org.springframework.core.annotation.Order(10)
public class DemoData implements ApplicationRunner {

  private final JdbcTemplate db;
  private final TenantService tenants;
  private final boolean demo;
  private final String password;
  private final boolean streaming;

  public DemoData(
    JdbcTemplate db,
    TenantService tenants,
    @Value("${farm.demo}") boolean demo,
    @Value("${farm.bootstrap-password}") String password,
    @Value("${farm.demo-stream.enabled:false}") boolean streaming
  ) {
    this.db = db;
    this.tenants = tenants;
    this.demo = demo;
    this.password = password;
    this.streaming = streaming;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.queryForObject("SELECT COUNT(*) FROM tenants", Long.class) > 0) return;
    if (password.length() < 12 || password.length() > 72) {
      throw new IllegalStateException("首次启动需设置 12 至 72 字符的 FARM_BOOTSTRAP_PASSWORD");
    }
    String platform = tenant("platform", "平台管理");
    tenants.addMember(platform, "platform", "平台管理员", password, "PLATFORM_ADMIN");
    if (!demo) return;
    seed("demo-a", "示范农场甲", "晴川示范园");
    seed("demo-b", "示范农场乙", "青禾示范园");
  }

  private String tenant(String code, String name) {
    String id = UUID.randomUUID().toString();
    db.update("INSERT INTO tenants(id,code,name) VALUES(?,?,?)", id, code, name);
    return id;
  }

  private void seed(String code, String name, String farmName) {
    String tenant = tenant(code, name);
    tenants.addMember(tenant, "admin", "农场管理员", password, "ADMIN");
    tenants.addMember(tenant, "operator", "农事操作员", password, "OPERATOR");
    tenants.addMember(tenant, "viewer", "经营查看者", password, "VIEWER");
    String farm = UUID.randomUUID().toString(),
      plot = UUID.randomUUID().toString();
    db.update(
      "INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,?)",
      farm,
      tenant,
      farmName,
      "虚构演示数据，可在农场档案中修改"
    );
    db.update(
      "INSERT INTO plots(id,tenant_id,farm_id,name,area_mu,crop) VALUES(?,?,?,?,?,?)",
      plot,
      tenant,
      farm,
      "一号示范田",
      128.5,
      "水稻"
    );
    db.update(
      "INSERT INTO plantings(id,tenant_id,plot_id,crop,variety,area_mu,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(),
      tenant,
      plot,
      "水稻",
      "演示品种",
      128.5,
      LocalDate.now().minusDays(60),
      LocalDate.now().plusDays(60),
      "ACTIVE"
    );
    db.update(
      "INSERT INTO farm_tasks(id,tenant_id,plot_id,title,task_type,due_date,status,note) VALUES(?,?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(),
      tenant,
      plot,
      "一号田生长巡查",
      "INSPECTION",
      LocalDate.now().plusDays(1),
      "PENDING",
      "检查叶色和田间水位"
    );
    db.update(
      "INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(),
      tenant,
      farm,
      "环境温度演示点",
      "TEMPERATURE",
      "℃",
      "SIMULATED"
    );
    db.update(
      "INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(),
      tenant,
      farm,
      streaming ? "土壤水分演示点" : "土壤水分记录点",
      "SOIL_MOISTURE",
      "%",
      streaming ? "SIMULATED" : "MANUAL"
    );
  }
}
