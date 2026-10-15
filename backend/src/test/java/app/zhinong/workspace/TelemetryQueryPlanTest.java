package app.zhinong.workspace;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.business.Store;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 2.2 chose the (tenant_id, device_id) foreign-key index for telemetry lookups, so every
 * latest-value read and the research counter check scanned whole device or tenant histories.
 * These plans must stay seeks on the composite indexes regardless of retained history.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:telemetry-plan-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=false", "farm.alerts.automation-enabled=false",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class TelemetryQueryPlanTest {
  static final Pattern SCAN = Pattern.compile("scanCount: (\\d+)");
  static final int SAMPLES = 400;
  @Autowired JdbcTemplate db;
  @Autowired AssetService assets;
  @Autowired Store store;
  String tenant, farm, otherFarm, pump, other;
  final Instant start = Instant.now().minus(Duration.ofDays(2));

  @BeforeEach void seed() {
    tenant = db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'", String.class);
    farm = UUID.randomUUID().toString(); otherFarm = UUID.randomUUID().toString();
    pump = UUID.randomUUID().toString(); other = UUID.randomUUID().toString();
    for (String f : List.of(farm, otherFarm))
      db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,?)", f, tenant, "虚构计划验证-" + f, "测试");
    db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,?)", pump, tenant, farm, "虚构泵站", "ENERGY", "kWh", "SIMULATED");
    db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,?,?,?)", other, tenant, otherFarm, "虚构邻场泵站", "ENERGY", "kWh", "SIMULATED");
    var rows = new ArrayList<Object[]>();
    // The counter is one of many channels; the neighbouring farm makes a tenant-wide scan visible.
    for (String metric : List.of("ENERGY", "WATER_TOTAL", "FLOW", "CURRENT", "VOLTAGE", "PUMP_RUNNING"))
      for (int i = 0; i < SAMPLES; i++) {
        var at = start.plusSeconds(60L * i).atOffset(ZoneOffset.UTC);
        rows.add(new Object[] {UUID.randomUUID().toString(), tenant, pump, metric, BigDecimal.valueOf(i), at, at});
        rows.add(new Object[] {UUID.randomUUID().toString(), tenant, other, metric, BigDecimal.valueOf(i), at, at});
        rows.add(new Object[] {UUID.randomUUID().toString(), tenant, other, metric + "_SPARE", BigDecimal.valueOf(i), at, at});
      }
    db.batchUpdate("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')", rows);
  }

  @AfterEach void clean() {
    db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id IN (?,?)", tenant, pump, other);
    db.update("DELETE FROM devices WHERE tenant_id=? AND id IN (?,?)", tenant, pump, other);
    db.update("DELETE FROM farms WHERE tenant_id=? AND id IN (?,?)", tenant, farm, otherFarm);
  }

  @Test void latestValueSeeksOneRowAndKeepsReceiptTieBreak() {
    var late = start.plusSeconds(60L * SAMPLES).atOffset(ZoneOffset.UTC);
    // Same sample time, later receipt: the correction must win, as before the index hint.
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')",
      UUID.randomUUID().toString(), tenant, pump, "ENERGY", new BigDecimal("900.000"), late, late);
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'MANUAL')",
      UUID.randomUUID().toString(), tenant, pump, "ENERGY", new BigDecimal("901.000"), late, late.plusSeconds(5));
    var latest = assets.latest(tenant, pump, "ENERGY");
    assertEquals(0, new BigDecimal("901").compareTo((BigDecimal) latest.get("value")));
    assertEquals("MANUAL", latest.get("source"));
    assertNull(assets.latest(tenant, pump, "SOIL_MOISTURE"));

    String plan = plan(assets.latestSql(), tenant, pump, "ENERGY", tenant, pump, "ENERGY");
    assertTrue(plan.contains("IX_TELEMETRY_LATEST"), plan);
    assertTrue(plan.contains("index sorted"), plan);
    assertEquals(1, scans(plan).getFirst(), "the newest reading must be the first index entry read: " + plan);
  }

  @Test void decreasingCounterCheckReadsOnlyTheFarmsCounterRows() {
    String sql = ResearchDataController.decreasingCounterSql(store.sql());
    assertEquals(0L, db.queryForObject(sql, Long.class, "ENERGY", tenant, farm));
    var dip = start.plusSeconds(60L * SAMPLES).atOffset(ZoneOffset.UTC); String dipId = UUID.randomUUID().toString();
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,'SIMULATED')",
      dipId, tenant, pump, "ENERGY", BigDecimal.ONE, dip, dip);
    assertEquals(1L, db.queryForObject(sql, Long.class, "ENERGY", tenant, farm), "a counter reset is still reported");
    assertEquals(0L, db.queryForObject(sql, Long.class, "WATER_TOTAL", tenant, farm));
    // Manual corrections are outside the synthetic-data contract.
    db.update("UPDATE telemetry_readings SET source='MANUAL' WHERE tenant_id=? AND id=?", tenant, dipId);
    assertEquals(0L, db.queryForObject(sql, Long.class, "ENERGY", tenant, farm));

    String plan = plan(sql, "ENERGY", tenant, farm);
    assertTrue(plan.contains("IX_TELEMETRY_HISTORY"), plan);
    long tenantRows = db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=?", Long.class, tenant);
    assertTrue(tenantRows > 10L * SAMPLES, "fixture must make a tenant scan observable");
    for (long scanned : scans(plan))
      assertTrue(scanned <= SAMPLES + 2, "only the farm's ENERGY rows may be read (" + scanned + " of " + tenantRows + "): " + plan);
  }

  String plan(String sql, Object... args) {
    return db.queryForObject("EXPLAIN ANALYZE " + sql, String.class, args);
  }

  static List<Long> scans(String plan) {
    var matcher = SCAN.matcher(plan); var counts = new ArrayList<Long>();
    while (matcher.find()) counts.add(Long.parseLong(matcher.group(1)));
    assertFalse(counts.isEmpty(), plan);
    return counts;
  }
}
