package app.zhinong.workspace;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "farm.alerts.automation-enabled", havingValue = "true", matchIfMissing = true)
public class DeviceAlertTicker {
  private static final Logger LOG = LoggerFactory.getLogger(DeviceAlertTicker.class);
  private final JdbcTemplate db;
  private final DeviceAlertMonitor monitor;
  private final AtomicBoolean scanning = new AtomicBoolean();
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(1), runnable -> {
      var thread = new Thread(runnable, "device-health-monitor");
      thread.setDaemon(true);
      return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

  public DeviceAlertTicker(JdbcTemplate db, DeviceAlertMonitor monitor) {
    this.db = db;
    this.monitor = monitor;
  }

  @Scheduled(fixedDelayString = "${farm.alerts.scan-delay-ms:60000}", initialDelayString = "${farm.alerts.initial-delay-ms:60000}")
  public void scheduled() {
    // Only dispatch on Spring's scheduler; SQL/row-lock waits must not delay irrigation stop checks.
    if (!scanning.compareAndSet(false, true)) return;
    try {
      worker.execute(() -> {
        try { tick(); }
        catch (RuntimeException ex) {
          LOG.warn("Device health scan unavailable; next scheduled scan will retry ({})", ex.getClass().getSimpleName());
        } finally { scanning.set(false); }
      });
    } catch (RejectedExecutionException unavailable) { scanning.set(false); }
  }

  /** Synchronous entry point for targeted verification; production scheduling uses dispatch above. */
  public void tick() {
    for (String tenant : db.queryForList("SELECT id FROM tenants WHERE enabled=TRUE", String.class)) {
      try { monitor.scanTenant(tenant); }
      catch (RuntimeException ex) {
        // Do not log raw database messages or device credentials. One tenant cannot block the next.
        LOG.warn("Device health monitoring failed for a tenant; next scheduled scan will retry ({})", ex.getClass().getSimpleName());
      }
    }
  }

  @PreDestroy void stop() { worker.shutdownNow(); }
}
