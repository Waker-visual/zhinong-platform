package app.zhinong.workspace;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.business.FarmClock;
import app.zhinong.database.DatabaseSql;
import app.zhinong.database.DatabaseTime;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:retention-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=true", "farm.research-history=true", "farm.demo-live=false",
  "farm.demo-retention.initial-delay-ms=86400000",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class DemoTelemetryRetentionIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired DatabaseSql sql;
  @Autowired FarmClock clock;

  /** A fresh job per test: its watermarks would otherwise skip rows another test inserted in the past. */
  DemoTelemetryRetention job() { return new DemoTelemetryRetention(db, sql, clock, true, true, 2, 30, 731, 500); }

  String tenant() { return db.queryForObject("SELECT id FROM tenants WHERE code='demo-a'", String.class); }
  String farm() { return db.queryForObject("SELECT id FROM farms WHERE tenant_id=? AND name='青禾设备联动演示场'", String.class, tenant()); }
  String device(String code) {
    return db.queryForObject("SELECT a.device_id FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id "
      + "WHERE a.tenant_id=? AND d.farm_id=? AND a.code=?", String.class, tenant(), farm(), code);
  }

  @Test
  void thinsOnlySyntheticDemoRowsAndLeavesRealReadingsAndOtherFarmsAlone() {
    ZoneId zone = clock.zone();
    Instant now = clock.instant();
    LocalDate today = now.atZone(zone).toLocalDate();
    String t = tenant(), soil = device("DEMO-CTRL-SOIL"), metric = "SOIL_MOISTURE";
    long step = DemoTelemetryRetention.denseStep(db.queryForObject("SELECT interval_seconds FROM asset_profiles WHERE tenant_id=? AND device_id=?", Integer.class, t, soil));

    // Dense tier: three hours of minute samples five days ago, off the research grid.
    Instant denseStart = today.minusDays(5).atTime(8, 0).atZone(zone).toInstant(), denseEnd = denseStart.plusSeconds(3 * 3600);
    for (int minute = 0; minute < 180; minute++) insert(t, soil, metric, 30, denseStart.plusSeconds(minute * 60L + 20), "SIMULATED");
    for (int minute = 0; minute < 10; minute++) {
      insert(t, soil, metric, 31, denseStart.plusSeconds(minute * 60L + 40), "MANUAL");
      insert(t, soil, metric, 32, denseStart.plusSeconds(minute * 60L + 50), "HTTP_PUSH");
    }
    var gridBefore = gridPoints(t, soil, metric, denseStart, denseEnd, zone);
    long denseBuckets = times(t, soil, metric, denseStart, denseEnd, "SIMULATED").stream()
      .mapToLong(at -> Math.floorDiv(at.getEpochSecond(), step)).distinct().count();
    // Full tier: yesterday's minute data stays complete.
    Instant recent = now.minus(Duration.ofDays(1));
    for (int minute = 0; minute < 30; minute++) insert(t, soil, metric, 30, recent.plusSeconds(minute * 60L), "SIMULATED");
    int recentBefore = times(t, soil, metric, recent, recent.plusSeconds(1800), "SIMULATED").size();
    // Daily tier: a working day of minute samples forty days ago.
    LocalDate dailyDay = today.minusDays(40);
    Instant dailyStart = dailyDay.atStartOfDay(zone).toInstant(), dailyEnd = dailyDay.plusDays(1).atStartOfDay(zone).toInstant();
    Instant morning = dailyDay.atTime(6, 0).atZone(zone).toInstant();
    for (int minute = 0; minute < 720; minute++) insert(t, soil, metric, 30, morning.plusSeconds(minute * 60L + 20), "SIMULATED");
    // Past the daily tail: synthetic rows go, a manual record stays.
    Instant ancient = today.minusDays(800).atTime(12, 0).atZone(zone).toInstant();
    for (int i = 0; i < 5; i++) insert(t, soil, metric, 30, ancient.plusSeconds(i * 60L), "SIMULATED");
    insert(t, soil, metric, 31, ancient, "MANUAL");

    // A non-demo farm in the demo tenant, and a tenant outside demo-a/demo-b that marks its farm as demo.
    String realFarm = farmWithDevice(t, false), realDevice = realFarm.split(":")[1];
    String other = UUID.randomUUID().toString();
    db.update("INSERT INTO tenants(id,code,name,enabled) VALUES(?,?,?,TRUE)", other, "field-" + other.substring(0, 8), "虚构农场 C");
    String otherFarm = farmWithDevice(other, true), otherDevice = otherFarm.split(":")[1];
    for (var target : List.of(new String[] {t, realDevice}, new String[] {other, otherDevice})) {
      for (int minute = 0; minute < 180; minute++) insert(target[0], target[1], metric, 30, denseStart.plusSeconds(minute * 60L + 20), "SIMULATED");
      insert(target[0], target[1], metric, 30, ancient, "SIMULATED");
    }

    var retention = job();
    var result = retention.prune(now);
    assertTrue(result.deleted() > 0, result.toString());

    assertEquals(denseBuckets, times(t, soil, metric, denseStart, denseEnd, "SIMULATED").size(), "one synthetic sample per interval bucket");
    assertEquals(gridBefore, gridPoints(t, soil, metric, denseStart, denseEnd, zone), "research grid points survive dense thinning");
    assertEquals(10, times(t, soil, metric, denseStart, denseEnd, "MANUAL").size());
    assertEquals(10, times(t, soil, metric, denseStart, denseEnd, "HTTP_PUSH").size());
    assertEquals(recentBefore, times(t, soil, metric, recent, recent.plusSeconds(1800), "SIMULATED").size(), "the newest two days keep minute data");

    var daily = times(t, soil, metric, dailyStart, dailyEnd, "SIMULATED");
    assertEquals(1, daily.size(), "one synthetic sample per older day");
    long fromNoon = Math.abs(Duration.between(LocalTime.NOON, daily.getFirst().atZone(zone).toLocalTime()).getSeconds());
    assertTrue(fromNoon <= 40, "kept the sample nearest local noon: " + daily);

    assertEquals(0, times(t, soil, metric, ancient.minusSeconds(1), ancient.plusSeconds(600), "SIMULATED").size());
    assertEquals(1, times(t, soil, metric, ancient.minusSeconds(1), ancient.plusSeconds(600), "MANUAL").size());
    for (var target : List.of(new String[] {t, realDevice}, new String[] {other, otherDevice})) {
      assertEquals(180, times(target[0], target[1], metric, denseStart, denseEnd, "SIMULATED").size(), "out-of-scope farm untouched");
      assertEquals(1, times(target[0], target[1], metric, ancient, ancient.plusSeconds(1), "SIMULATED").size());
    }

    assertEquals(0, retention.prune(now).deleted(), "a second pass has nothing left to thin");
    assertEquals(0, job().prune(now).deleted(), "a restart rescans without removing anything more");
  }

  @Test
  void researchHistoryKeepsItsDensityAndCountersStayMonotonic() {
    ZoneId zone = clock.zone();
    Instant now = clock.instant();
    LocalDate today = now.atZone(zone).toLocalDate();
    String t = tenant(), f = farm(), pump = device("DEMO-CTRL-PUMP");
    // Interleave minute samples into the cumulative counters without breaking them: each copies
    // the preceding stored value, as a meter that has not advanced yet would report.
    for (String metric : List.of("ENERGY", "WATER_TOTAL")) {
      for (LocalDate day : List.of(today.minusDays(6), today.minusDays(45))) {
        Instant start = day.atTime(9, 0).atZone(zone).toInstant();
        for (int minute = 0; minute < 120; minute++) {
          Instant at = start.plusSeconds(minute * 60L + 15);
          var previous = db.queryForList("SELECT measured_value FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=? "
            + "AND source='SIMULATED' AND measured_at<? ORDER BY measured_at DESC LIMIT 1", BigDecimal.class, t, pump, metric, at.atOffset(ZoneOffset.UTC));
          insert(t, pump, metric, previous.isEmpty() ? 0 : previous.getFirst().doubleValue(), at, "SIMULATED");
        }
      }
    }
    assertEquals(0, decreasing(t, f), "fixture keeps counters monotonic");

    var before = perDay(t, f, zone);
    assertFalse(before.isEmpty());

    assertTrue(job().prune(now).deleted() > 0, "the interleaved minute samples were thinned");

    assertEquals(0, decreasing(t, f), "thinning keeps ENERGY and WATER_TOTAL monotonic");
    var after = perDay(t, f, zone);
    int checked = 0;
    for (var channel : before.entrySet()) {
      var was = channel.getValue();
      var is = after.getOrDefault(channel.getKey(), Map.of());
      for (LocalDate day = today.minusDays(29); day.isBefore(today.minusDays(2)); day = day.plusDays(1)) {
        int expected = Math.min(48, was.getOrDefault(day, 0));
        assertTrue(is.getOrDefault(day, 0) >= expected, channel.getKey() + " keeps " + expected + " samples on " + day + ": " + is.get(day));
        if (expected == 48) checked++;
      }
      for (LocalDate day = today.minusDays(700); day.isBefore(today.minusDays(31)); day = day.plusDays(1)) {
        int count = is.getOrDefault(day, 0);
        assertTrue(count <= 1, channel.getKey() + " keeps one sample on " + day + ": " + count);
        assertEquals(was.containsKey(day) ? 1 : 0, count, channel.getKey() + " keeps its daily research point on " + day);
      }
    }
    assertTrue(checked > 100, "the research grid covered the dense window: " + checked);
  }

  /** Synthetic samples per farm calendar day for every channel of the research farm's demo devices. */
  private Map<String, Map<LocalDate, Integer>> perDay(String tenant, String farm, ZoneId zone) {
    var perDay = new HashMap<String, Map<LocalDate, Integer>>();
    db.query("""
      SELECT r.device_id,r.metric,r.measured_at FROM telemetry_readings r JOIN asset_profiles a ON a.tenant_id=r.tenant_id AND a.device_id=r.device_id
      JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
      WHERE d.tenant_id=? AND d.farm_id=? AND a.code LIKE 'DEMO-CTRL-%' AND r.source='SIMULATED'
      """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> perDay
        .computeIfAbsent(rs.getString(1) + ":" + rs.getString(2), k -> new HashMap<>())
        .merge(DatabaseTime.instant(rs.getObject(3, OffsetDateTime.class)).atZone(zone).toLocalDate(), 1, Integer::sum), tenant, farm);
    return perDay;
  }

  @Test
  void disabledWithoutDemoModeAndRejectsUnsafeWindows() {
    var off = new DemoTelemetryRetention(null, null, FarmClock.system(), false, true, 2, 30, 731, 500);
    assertEquals(new DemoTelemetryRetention.Result(0, 0), off.prune(Instant.now()));
    var paused = new DemoTelemetryRetention(null, null, FarmClock.system(), true, false, 2, 30, 731, 500);
    assertEquals(new DemoTelemetryRetention.Result(0, 0), paused.prune(Instant.now()));
    assertThrows(IllegalArgumentException.class, () -> new DemoTelemetryRetention(null, null, FarmClock.system(), true, true, 2, 29, 731, 500));
    assertThrows(IllegalArgumentException.class, () -> new DemoTelemetryRetention(null, null, FarmClock.system(), true, true, 0, 30, 731, 500));
    assertThrows(IllegalArgumentException.class, () -> new DemoTelemetryRetention(null, null, FarmClock.system(), true, true, 2, 30, 30, 500));
    assertEquals(900, DemoTelemetryRetention.denseStep(900));
    assertEquals(1800, DemoTelemetryRetention.denseStep(3600));
    assertEquals(1800, DemoTelemetryRetention.denseStep(null));
    assertEquals(60, DemoTelemetryRetention.denseStep(5));
  }

  private long decreasing(String tenant, String farm) {
    long total = 0;
    for (String metric : List.of("ENERGY", "WATER_TOTAL"))
      total += db.queryForObject(ResearchDataController.decreasingCounterSql(sql), Long.class, metric, tenant, farm);
    return total;
  }

  private String farmWithDevice(String tenant, boolean demo) {
    String farm = UUID.randomUUID().toString(), device = UUID.randomUUID().toString();
    db.update("INSERT INTO farms(id,tenant_id,name,description) VALUES(?,?,?,?)", farm, tenant, "保留测试农场 " + farm.substring(0, 8), "虚构");
    db.update("INSERT INTO farm_profiles(tenant_id,farm_id,demo) VALUES(?,?,?)", tenant, farm, demo);
    db.update("INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter) VALUES(?,?,?,?,'SOIL_MOISTURE','%','SIMULATED')", device, tenant, farm, "虚构墒情点");
    return farm + ":" + device;
  }

  private void insert(String tenant, String device, String metric, double value, Instant at, String source) {
    db.update("INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source) VALUES(?,?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(), tenant, device, metric, BigDecimal.valueOf(value), at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), source);
  }

  private List<Instant> times(String tenant, String device, String metric, Instant from, Instant to, String source) {
    return db.queryForList("SELECT measured_at FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=? AND source=? "
        + "AND measured_at>=? AND measured_at<? ORDER BY measured_at", OffsetDateTime.class,
        tenant, device, metric, source, from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC))
      .stream().map(OffsetDateTime::toInstant).toList();
  }

  private List<Instant> gridPoints(String tenant, String device, String metric, Instant from, Instant to, ZoneId zone) {
    return times(tenant, device, metric, from, to, "SIMULATED").stream().filter(at -> {
      var local = at.atZone(zone);
      return local.getMinute() % 30 == 0 && local.getSecond() == 0;
    }).toList();
  }
}
