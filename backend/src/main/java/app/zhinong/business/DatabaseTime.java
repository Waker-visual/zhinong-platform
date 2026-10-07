package app.zhinong.business;

import java.sql.Timestamp;
import java.time.*;

/** MySQL DATETIME columns hold UTC; H2 returns OffsetDateTime for telemetry. */
public final class DatabaseTime {
  private DatabaseTime() {}
  public static Instant instant(Object value) {
    if (value instanceof OffsetDateTime time) return time.toInstant();
    if (value instanceof Instant time) return time;
    if (value instanceof Timestamp time) return time.toInstant();
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
    throw new IllegalArgumentException("Unsupported database timestamp");
  }
  public static OffsetDateTime utc(Object value) {
    return value == null ? null : instant(value).atOffset(ZoneOffset.UTC);
  }
}
