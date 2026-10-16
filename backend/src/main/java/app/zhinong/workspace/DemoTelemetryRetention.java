package app.zhinong.workspace;

import app.zhinong.business.FarmClock;
import app.zhinong.database.DatabaseSql;
import app.zhinong.database.DatabaseTime;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ages out the fictional demo telemetry that the operating-demo ticker and demo stream keep writing.
 * Only SIMULATED rows on demo farms of the demo tenants are read or deleted; MANUAL and HTTP_PUSH
 * readings and every non-demo farm are out of scope in each statement, not just in the device list.
 *
 * <p>Tiers, by age:
 * <ul>
 *   <li>younger than {@code full-days}: untouched (24/48 h charts keep minute data);</li>
 *   <li>up to {@code dense-days} (calendar days): one sample per device interval, never sparser than
 *       30 minutes, so trend health stays accurate and the research grid keeps its 48 samples a day;</li>
 *   <li>up to {@code daily-days}: one sample per farm calendar day, the one nearest local noon;</li>
 *   <li>older: deleted.</li>
 * </ul>
 * Keeping a subset of each series never reorders it, so cumulative counters stay monotonic.
 * Deletes run in small autocommitted batches without device locks, so ingestion and control never wait.
 */
@Component
@EnableScheduling
public class DemoTelemetryRetention {
  static final long MAX_DENSE_STEP = 1800;
  private static final long MIN_DENSE_STEP = 60;
  /** Daily-tier windows: bounded memory even when the first run meets months of minute data. */
  private static final int DAILY_WINDOW_DAYS = 30;
  private static final String DEMO_TENANTS = "SELECT id FROM tenants WHERE code IN ('demo-a','demo-b') AND enabled=TRUE ORDER BY id";
  /** Re-proves the scope inside every delete, in case a farm stops being a demo between list and delete. */
  private static final String SCOPE = """
     AND EXISTS(SELECT 1 FROM devices d
      JOIN farm_profiles f ON f.tenant_id=d.tenant_id AND f.farm_id=d.farm_id AND f.demo=TRUE
      JOIN tenants t ON t.id=d.tenant_id AND t.code IN ('demo-a','demo-b')
      WHERE d.tenant_id=? AND d.id=?)
    """;

  private final JdbcTemplate db;
  private final DatabaseSql sql;
  private final FarmClock clock;
  private final boolean enabled;
  private final int fullDays, denseDays, dailyDays, batchSize;
  /** Per channel: dense tier thinned up to [0], daily tier up to [1]. A restart rescans once. */
  private final Map<String, Instant[]> done = new HashMap<>();

  public record Result(long channels, long deleted) {}
  record Sample(String id, Instant at) {}

  public DemoTelemetryRetention(JdbcTemplate db, DatabaseSql sql, FarmClock clock,
      @Value("${farm.demo:false}") boolean demo,
      @Value("${farm.demo-retention.enabled:true}") boolean retention,
      @Value("${farm.demo-retention.full-days:2}") int fullDays,
      @Value("${farm.demo-retention.dense-days:30}") int denseDays,
      @Value("${farm.demo-retention.daily-days:731}") int dailyDays,
      @Value("${farm.demo-retention.batch-size:500}") int batchSize) {
    if (fullDays < 1 || fullDays > denseDays) throw new IllegalArgumentException("演示数据完整保留天数须在 1 与加密层天数之间");
    // The research report promises 48 samples a day for its last 30 days.
    if (denseDays < 30 || denseDays >= dailyDays) throw new IllegalArgumentException("演示数据加密层须至少 30 天且短于每日层");
    if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("演示数据删除批量须在 1–1000 之间");
    this.db = db;
    this.sql = sql;
    this.clock = clock;
    this.enabled = demo && retention;
    this.fullDays = fullDays;
    this.denseDays = denseDays;
    this.dailyDays = dailyDays;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${farm.demo-retention.delay-ms:3600000}",
    initialDelayString = "${farm.demo-retention.initial-delay-ms:300000}")
  public void tick() {
    if (!enabled) return;
    var result = prune(clock.instant());
    if (result.deleted() > 0) org.slf4j.LoggerFactory.getLogger(getClass())
      .info("Demo telemetry retention removed {} synthetic readings across {} channels", result.deleted(), result.channels());
  }

  public synchronized Result prune(Instant now) {
    if (!enabled) return new Result(0, 0);
    ZoneId zone = clock.zone();
    LocalDate today = now.atZone(zone).toLocalDate();
    Instant fullCutoff = now.minus(Duration.ofDays(fullDays));
    Instant dailyCutoff = today.minusDays(denseDays).atStartOfDay(zone).toInstant();
    Instant dropCutoff = today.minusDays(dailyDays).atStartOfDay(zone).toInstant();
    long channels = 0, deleted = 0;
    for (String tenant : db.queryForList(DEMO_TENANTS, String.class)) {
      var devices = db.queryForList("""
        SELECT d.id,a.interval_seconds FROM devices d
        JOIN farm_profiles f ON f.tenant_id=d.tenant_id AND f.farm_id=d.farm_id AND f.demo=TRUE
        LEFT JOIN asset_profiles a ON a.tenant_id=d.tenant_id AND a.device_id=d.id
        WHERE d.tenant_id=? ORDER BY d.id
        """, tenant);
      for (var device : devices) {
        String id = device.get("ID").toString();
        long step = denseStep((Number) device.get("INTERVAL_SECONDS"));
        Instant denseEnd = Instant.ofEpochSecond(Math.floorDiv(fullCutoff.getEpochSecond(), step) * step);
        for (String metric : db.queryForList("""
            SELECT metric FROM device_channels WHERE tenant_id=? AND device_id=?
            UNION SELECT metric FROM devices WHERE tenant_id=? AND id=?
            """, String.class, tenant, id, tenant, id)) {
          channels++;
          String key = tenant + ":" + id + ":" + metric;
          var marks = done.computeIfAbsent(key, k -> new Instant[2]);
          deleted += dropOlder(tenant, id, metric, dropCutoff);
          Instant from = later(marks[1], dropCutoff);
          for (Instant start = from; start.isBefore(dailyCutoff); ) {
            Instant end = min(start.atZone(zone).toLocalDate().plusDays(DAILY_WINDOW_DAYS).atStartOfDay(zone).toInstant(), dailyCutoff);
            deleted += thin(tenant, id, metric, start, end, at -> at.atZone(zone).toLocalDate().toEpochDay(), samples -> nearestNoon(samples, zone));
            start = end;
          }
          marks[1] = dailyCutoff;
          from = later(marks[0], dailyCutoff);
          long window = step * Math.max(1, 86400 / step);
          for (Instant start = from; start.isBefore(denseEnd); ) {
            Instant end = min(Instant.ofEpochSecond((Math.floorDiv(start.getEpochSecond(), step) * step) + window), denseEnd);
            deleted += thin(tenant, id, metric, start, end, at -> Math.floorDiv(at.getEpochSecond(), step), samples -> preferGrid(samples, zone));
            start = end;
          }
          marks[0] = later(marks[0], denseEnd);
        }
      }
    }
    return new Result(channels, deleted);
  }

  /** One sample per reporting interval, but at least one every 30 minutes; unknown intervals use 30 minutes. */
  static long denseStep(Number interval) {
    long seconds = interval == null ? MAX_DENSE_STEP : interval.longValue();
    return Math.min(MAX_DENSE_STEP, Math.max(MIN_DENSE_STEP, seconds));
  }

  /** Dense tier: keep the research grid point (:00/:30 local) when the bucket has one, else the earliest sample. */
  static Sample preferGrid(List<Sample> bucket, ZoneId zone) {
    for (var sample : bucket) {
      var local = sample.at().atZone(zone);
      if (local.getMinute() % 30 == 0 && local.getSecond() == 0 && local.getNano() == 0) return sample;
    }
    return bucket.getFirst();
  }

  /** Daily tier: the sample closest to local noon, matching the research history's one-a-day point. */
  static Sample nearestNoon(List<Sample> bucket, ZoneId zone) {
    Sample best = bucket.getFirst();
    long bestDistance = Long.MAX_VALUE;
    for (var sample : bucket) {
      long distance = Math.abs(Duration.between(LocalTime.NOON, sample.at().atZone(zone).toLocalTime()).toMillis());
      if (distance < bestDistance) { best = sample; bestDistance = distance; }
    }
    return best;
  }

  private long thin(String tenant, String device, String metric, Instant start, Instant end,
      java.util.function.ToLongFunction<Instant> bucketOf, java.util.function.Function<List<Sample>, Sample> keep) {
    var rows = new ArrayList<Sample>();
    db.query("SELECT id,measured_at FROM telemetry_readings" + sql.useIndex("ix_telemetry_history") + """
       WHERE tenant_id=? AND device_id=? AND metric=? AND measured_at>=? AND measured_at<? AND source='SIMULATED'
      ORDER BY tenant_id,device_id,metric,measured_at,id
      """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> rows.add(new Sample(rs.getString(1), DatabaseTime.instant(rs.getObject(2, OffsetDateTime.class)))),
      tenant, device, metric, start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC));
    var doomed = new ArrayList<String>();
    var bucket = new ArrayList<Sample>();
    long current = Long.MIN_VALUE;
    for (var row : rows) {
      long key = bucketOf.applyAsLong(row.at());
      if (key != current) { discard(bucket, keep, doomed); current = key; }
      bucket.add(row);
    }
    discard(bucket, keep, doomed);
    return delete(tenant, device, doomed);
  }

  private static void discard(List<Sample> bucket, java.util.function.Function<List<Sample>, Sample> keep, List<String> doomed) {
    if (bucket.size() > 1) {
      var kept = keep.apply(bucket);
      for (var sample : bucket) if (sample != kept) doomed.add(sample.id());
    }
    bucket.clear();
  }

  private long dropOlder(String tenant, String device, String metric, Instant cutoff) {
    long total = 0;
    while (true) {
      var ids = db.queryForList("SELECT id FROM telemetry_readings" + sql.useIndex("ix_telemetry_history") + """
         WHERE tenant_id=? AND device_id=? AND metric=? AND measured_at<? AND source='SIMULATED'
        ORDER BY tenant_id,device_id,metric,measured_at LIMIT ?
        """, String.class, tenant, device, metric, cutoff.atOffset(ZoneOffset.UTC), batchSize);
      if (ids.isEmpty()) return total;
      long removed = delete(tenant, device, ids);
      total += removed;
      // A row the scope check refused stays put; stop instead of selecting it forever.
      if (removed < ids.size()) return total;
    }
  }

  /** Each batch is its own short statement: no device lock and no long transaction. */
  private long delete(String tenant, String device, List<String> ids) {
    long total = 0;
    for (int from = 0; from < ids.size(); from += batchSize) {
      var part = ids.subList(from, Math.min(ids.size(), from + batchSize));
      var args = new ArrayList<Object>(List.of(tenant, device));
      args.addAll(part);
      args.add(tenant);
      args.add(device);
      total += db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND source='SIMULATED' AND id IN ("
        + String.join(",", Collections.nCopies(part.size(), "?")) + ")" + SCOPE, args.toArray());
    }
    return total;
  }

  private static Instant later(Instant a, Instant b) { return a == null || a.isBefore(b) ? b : a; }
  private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
