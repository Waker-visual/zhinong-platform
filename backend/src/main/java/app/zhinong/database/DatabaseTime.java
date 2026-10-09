package app.zhinong.database;

import java.sql.*;
import java.time.*;

public final class DatabaseTime {
  private DatabaseTime() {}
  public static OffsetDateTime offset(Object value) {
    if (value == null) return null;
    if (value instanceof OffsetDateTime time) return time;
    if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC);
    if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC);
    throw new IllegalArgumentException("Unsupported database timestamp");
  }
  public static OffsetDateTime offset(ResultSet rs, int column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class);
  }
}
