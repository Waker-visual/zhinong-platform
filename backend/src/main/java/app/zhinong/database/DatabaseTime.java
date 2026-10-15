package app.zhinong.database;

import java.sql.*;
import java.time.*;

/** Timestamps read back from JDBC: MySQL DATETIME columns hold UTC, H2 returns OffsetDateTime. */
public final class DatabaseTime {
  private DatabaseTime() {}
  /** The stored instant; a missing value is an error. */
  public static Instant instant(Object value) {
    if (value instanceof OffsetDateTime time) return time.toInstant();
    if (value instanceof Instant time) return time;
    if (value instanceof Timestamp time) return time.toInstant();
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
    throw new IllegalArgumentException("Unsupported database timestamp");
  }
  /** The same instant normalized to UTC, for API fields that must read the same on H2 and MySQL. */
  public static OffsetDateTime utc(Object value) {
    return value == null ? null : instant(value).atOffset(ZoneOffset.UTC);
  }
  /** Keeps an H2 offset as stored; other types are read as UTC. */
  public static OffsetDateTime offset(Object value) {
    if (value == null) return null;
    if (value instanceof OffsetDateTime time) return time;
    return instant(value).atOffset(ZoneOffset.UTC);
  }
  public static OffsetDateTime offset(ResultSet rs, int column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class);
  }
}
