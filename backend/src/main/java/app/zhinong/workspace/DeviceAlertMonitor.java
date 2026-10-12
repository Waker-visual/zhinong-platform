package app.zhinong.workspace;

import app.zhinong.database.DatabaseTime;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal monitoring; every device read/write is scoped to the server-selected tenant. */
@Service
@Transactional
public class DeviceAlertMonitor {
  private final JdbcTemplate db;
  private final AssetService assets;
  private final TransactionTemplate transactions;

  public DeviceAlertMonitor(JdbcTemplate db, AssetService assets, PlatformTransactionManager transactions) {
    this.db = db;
    this.assets = assets;
    this.transactions = new TransactionTemplate(transactions);
  }

  public static long thresholdSeconds(int intervalSeconds) {
    return Math.max(180, intervalSeconds * 3L);
  }

  /** Called by the scheduler, or with Identity.tenant() by an authenticated request. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void scanTenant(String tenant) { scanFarm(tenant, null); }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void scanFarm(String tenant, String farmId) {
    var args = new ArrayList<Object>(); args.add(tenant);
    String sql = "SELECT d.id FROM devices d JOIN tenants t ON t.id=d.tenant_id WHERE d.tenant_id=? AND t.enabled=TRUE";
    if (farmId != null && !farmId.isBlank()) { sql += " AND d.farm_id=?"; args.add(farmId); }
    for (String id : db.queryForList(sql + " ORDER BY d.id", String.class, args.toArray())) {
      // Commit one device at a time; a dashboard refresh must not hold a whole farm's locks.
      transactions.executeWithoutResult(status -> {
        // Sampling or control may already own this row. A read-triggered scan must
        // retain the saved incident and defer this device to the next scheduled pass,
        // not wait until the database lock timeout turns the dashboard into a 500.
        if (!db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE SKIP LOCKED", tenant, id).isEmpty()) {
          inspectLockedDevice(tenant, id);
        }
      });
    }
  }

  public void checkDevice(String tenant, String id) {
    // Same lock as ingestion/configuration: overlapping scheduler/request checks cannot duplicate alerts.
    if (db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id).isEmpty()) return;
    inspectLockedDevice(tenant, id);
  }

  private void inspectLockedDevice(String tenant, String id) {
    var rows = db.queryForList("""
      SELECT p.protocol,p.lifecycle,p.interval_seconds,p.last_received_at,a.farm_id AS archived_farm
      FROM asset_profiles p JOIN devices d ON d.tenant_id=p.tenant_id AND d.id=p.device_id
      LEFT JOIN farm_archives a ON a.tenant_id=d.tenant_id AND a.farm_id=d.farm_id
      WHERE p.tenant_id=? AND p.device_id=?
      """, tenant, id);
    if (rows.isEmpty()) return;
    var profile = rows.getFirst();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    if (!"ACTIVE".equals(profile.get("LIFECYCLE")) || "MANUAL".equals(profile.get("PROTOCOL")) || profile.get("ARCHIVED_FARM") != null) {
      resolve(tenant, id, "OFFLINE", "设备已退出自动监测范围（维护、停用、手工录入或农场归档）", now);
      resolve(tenant, id, "DATA_STALE", "设备已退出自动监测范围（维护、停用、手工录入或农场归档）", now);
      return;
    }
    long threshold = thresholdSeconds(((Number) profile.get("INTERVAL_SECONDS")).intValue());
    var received = DatabaseTime.offset(profile.get("LAST_RECEIVED_AT"));
    var baseline = db.queryForObject("SELECT MIN(occurred_at) FROM audit_events WHERE tenant_id=? AND resource_id=? AND action IN ('SAVE_ASSET','CREATE_DEVICES','MONITOR_REGISTERED')", java.sql.Timestamp.class, tenant, id);
    if (baseline == null) {
      // Older seeded/imported devices have no creation timestamp; persist a first-observation grace period.
      baseline = java.sql.Timestamp.from(now.toInstant());
      db.update("INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(), tenant, "DEVICE_MONITOR", "MONITOR_REGISTERED", id, baseline);
    }
    Instant lastContact = received == null ? baseline.toInstant() : received.toInstant();
    long silence = Math.max(0, Duration.between(lastContact, now.toInstant()).getSeconds());
    if (silence >= threshold) {
      upsert(tenant, id, "OFFLINE", silence,
        received == null ? "设备启用后仍未收到数据，已超过 " + threshold + " 秒接入宽限期"
          : "设备已超过 " + threshold + " 秒未上报，当前中断约 " + silence / 60 + " 分钟", now);
      // A disconnected device is one incident; do not double-notify with stale-data alerts.
      resolve(tenant, id, "DATA_STALE", "上报已中断，转由设备离线告警跟踪", now);
      return;
    }
    resolve(tenant, id, "OFFLINE", "已恢复设备数据接收", now);
    if (received == null) return; // Newly registered devices get the complete connection grace period.

    var stale = new ArrayList<String>();
    long worstAge = 0;
    for (var channel : assets.channels(tenant, id)) {
      String metric = channel.get("metric").toString();
      var latest = assets.latest(tenant, id, metric);
      long age = latest == null ? Math.max(0, Duration.between(baseline.toInstant(), now.toInstant()).getSeconds())
        : Math.max(0, Duration.between(DatabaseTime.offset(latest.get("time")).toInstant(), now.toInstant()).getSeconds());
      if (age >= threshold || latest != null && !profile.get("PROTOCOL").equals(latest.get("source"))) {
        stale.add(MetricCatalog.get(metric).name());
        worstAge = Math.max(worstAge, age);
      }
    }
    if (!stale.isEmpty()) {
      String names = String.join("、", stale);
      if (names.length() > 150) names = names.substring(0, 150) + "等";
      upsert(tenant, id, "DATA_STALE", worstAge, "设备仍在上报，但以下通道缺测、过期或来源不匹配：" + names, now);
    } else resolve(tenant, id, "DATA_STALE", "所有已配置通道均已恢复当前接入方式的新鲜采样", now);
  }

  private void upsert(String tenant, String id, String metric, long seconds, String message, OffsetDateTime now) {
    var open = db.queryForList("SELECT id FROM device_alerts WHERE tenant_id=? AND device_id=? AND metric=? AND status<>'RESOLVED'", tenant, id, metric);
    if (open.isEmpty()) {
      db.update("INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), tenant, id, metric, BigDecimal.valueOf(seconds), message, now, now);
    } else {
      db.update("UPDATE device_alerts SET measured_value=?,message=?,updated_at=? WHERE tenant_id=? AND id=?",
        BigDecimal.valueOf(seconds), message, now, tenant, open.getFirst().get("ID"));
    }
  }

  private void resolve(String tenant, String id, String metric, String note, OffsetDateTime now) {
    db.update("UPDATE device_alerts SET status='RESOLVED',updated_at=?,handled_by='SYSTEM',handle_note=? WHERE tenant_id=? AND device_id=? AND metric=? AND status<>'RESOLVED'",
      now, note, tenant, id, metric);
  }
}
