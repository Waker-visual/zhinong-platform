package app.zhinong.workspace;

import app.zhinong.business.DatabaseTime;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Continuously maintains only explicitly synthetic fixture devices, never a real-device gap. */
@Component
@Order(50)
@EnableScheduling
@ConditionalOnProperty(name = "farm.demo-stream.enabled", havingValue = "true")
public class DemoTelemetryStream implements ApplicationRunner {
  private final JdbcTemplate db;
  private final TelemetryService telemetry;
  private final TransactionTemplate tx;
  private final int days;
  private final boolean enabled;
  private boolean ready;
  private final Map<String, Cursor> cursors = new HashMap<>();
  private record Cursor(Instant at, String signature, LocalDate checkedDate) {}
  private static final String ELIGIBLE = """
    SELECT p.*,d.farm_id FROM asset_profiles p
    JOIN devices d ON d.tenant_id=p.tenant_id AND d.id=p.device_id
    JOIN tenants t ON t.id=p.tenant_id
    JOIN farm_profiles f ON f.tenant_id=d.tenant_id AND f.farm_id=d.farm_id
    WHERE t.code IN ('demo-a','demo-b') AND t.enabled=TRUE AND f.demo=TRUE
      AND p.protocol='SIMULATED' AND p.lifecycle='ACTIVE'
      AND (p.code LIKE 'DEMO-%' OR p.code LIKE 'POINT-%')
      AND NOT EXISTS (SELECT 1 FROM demo_operating_farms r
        WHERE r.tenant_id=d.tenant_id AND r.farm_id=d.farm_id)
    """;

  public DemoTelemetryStream(JdbcTemplate db, TelemetryService telemetry, PlatformTransactionManager manager,
      @Value("${farm.demo:false}") boolean demo, @Value("${farm.demo-stream.days:30}") int days) {
    if (days < 1 || days > 30) throw new IllegalArgumentException("模拟历史范围为 1–30 天");
    this.db = db;
    this.telemetry = telemetry;
    this.tx = new TransactionTemplate(manager);
    this.days = days;
    this.enabled = demo;
  }

  @Override
  public void run(ApplicationArguments args) {
    refresh(Instant.now(), true);
    ready = true;
    org.slf4j.LoggerFactory.getLogger(getClass()).info("Synthetic demo history ready ({} days); real-device readings are untouched", days);
  }

  @Scheduled(fixedDelayString = "${farm.demo-stream.delay-ms:60000}", initialDelay = 60000)
  public void tick() {
    if (ready) refresh(Instant.now(), false);
  }

  /** Full scans on startup and each UTC day also repair holes inside the retained window. */
  public synchronized void refresh(Instant now, boolean full) {
    if (!enabled) return;
    for (var device : db.queryForList(ELIGIBLE + " ORDER BY p.tenant_id,p.device_id")) {
      String tenant = device.get("TENANT_ID").toString(), id = device.get("DEVICE_ID").toString();
      String key = tenant + ":" + id;
      var cursor = tx.execute(status -> {
        // Same device lock as ingestion/control: never race a command or protocol change.
        db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id);
        var eligible = db.queryForList(ELIGIBLE + " AND p.tenant_id=? AND p.device_id=?", tenant, id);
        return eligible.isEmpty() ? null : fill(eligible.getFirst(), now, full, cursors.get(key));
      });
      if (cursor != null) cursors.put(key, cursor);
      else cursors.remove(key);
    }
  }

  private Cursor fill(Map<String, Object> device, Instant now, boolean full, Cursor cursor) {
    String tenant = device.get("TENANT_ID").toString(), id = device.get("DEVICE_ID").toString();
    String type = device.get("DEVICE_TYPE").toString();
    int interval = ((Number) device.get("INTERVAL_SECONDS")).intValue();
    var channels = db.queryForList("SELECT metric FROM device_channels WHERE tenant_id=? AND device_id=? ORDER BY metric", String.class, tenant, id);
    String signature = interval + ":" + String.join(",", channels);
    LocalDate date = now.atOffset(ZoneOffset.UTC).toLocalDate();
    Instant end = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), interval) * interval);
    Instant start = end.minusSeconds(days * 86400L);
    start = Instant.ofEpochSecond(Math.floorDiv(start.getEpochSecond(), interval) * interval);
    if (!full && cursor != null && cursor.signature().equals(signature) && cursor.checkedDate().equals(date)) {
      if (!cursor.at().isBefore(end)) return cursor;
      start = cursor.at().plusSeconds(interval).isAfter(start) ? cursor.at().plusSeconds(interval) : start;
    }
    var state = new HashMap<String, BigDecimal>();
    for (String metric : channels) {
      var prior = db.queryForList("""
        SELECT measured_value FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric=?
          AND source='SIMULATED' AND measured_at<? ORDER BY measured_at DESC,received_at DESC LIMIT 1
        """, tenant, id, metric, start.atOffset(ZoneOffset.UTC));
      if (!prior.isEmpty()) state.put(metric, (BigDecimal) prior.getFirst().get("MEASURED_VALUE"));
    }
    var saved = db.queryForList("""
      SELECT metric,measured_value,measured_at FROM telemetry_readings
      WHERE tenant_id=? AND device_id=? AND measured_at>=? AND measured_at<=? AND source='SIMULATED'
      ORDER BY measured_at,received_at,id
      """, tenant, id, start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC));
    var occupied = new HashSet<String>();
    for (var row : saved) occupied.add(row.get("METRIC") + ":" + DatabaseTime.instant(row.get("MEASURED_AT")));
    var batch = new ArrayList<Object[]>();
    int pos = 0;
    for (Instant at = start; !at.isAfter(end); at = at.plusSeconds(interval)) {
      while (pos < saved.size() && !DatabaseTime.instant(saved.get(pos).get("MEASURED_AT")).isAfter(at)) {
        var row = saved.get(pos++);
        state.put(row.get("METRIC").toString(), (BigDecimal) row.get("MEASURED_VALUE"));
      }
      var values = SyntheticTelemetry.sample(type, device.get("FARM_ID").toString(), at, state, interval);
      var current = new ArrayList<WorkspaceInputs.Reading>();
      for (String metric : channels) {
        if (occupied.contains(metric + ":" + at)) continue;
        var value = values.get(metric);
        MetricCatalog.validate(metric, value);
        state.put(metric, value);
        if (at.equals(end)) current.add(new WorkspaceInputs.Reading(metric, value));
        else {
          String stableId = UUID.nameUUIDFromBytes((SyntheticTelemetry.VERSION + tenant + id + metric + at).getBytes(StandardCharsets.UTF_8)).toString();
          batch.add(new Object[] {stableId, tenant, id, metric, value, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC)});
        }
      }
      if (batch.size() >= 500 || at.equals(end)) {
        if (!batch.isEmpty()) db.batchUpdate("""
          INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source)
          VALUES(?,?,?,?,?,?,?,'SIMULATED')
          """, batch);
        batch.clear();
      }
      if (!current.isEmpty()) telemetry.write(tenant, id, at, current, "SIMULATED", "DEMO_STREAM");
    }
    return new Cursor(end, signature, date);
  }
}
