package app.zhinong.database;

import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The SQL that differs between H2 and MySQL 8. Only identifiers owned by application code may be passed to these helpers. */
@Component
public class DatabaseSql {
  private final boolean mysql;
  public DatabaseSql(Environment env) {
    String url = env.getProperty("spring.datasource.url", "");
    mysql = url.startsWith("jdbc:mysql:");
    if (!mysql && !url.startsWith("jdbc:h2:")) throw new IllegalArgumentException("仅支持 H2 或 MySQL 8 数据库");
  }
  public boolean mysql() { return mysql; }
  /** Use the same business zone (FarmClock) for date buckets and the caller's time-window boundaries. */
  public String calendarDate(String column, java.time.ZoneId zone) {
    String offset=java.time.OffsetDateTime.now(zone).getOffset().getId();
    if ("Z".equals(offset)) offset="+00:00";
    return mysql
      ? "CAST(CONVERT_TZ("+column+",@@session.time_zone,'"+offset+"') AS DATE)"
      // H2 casts offset timestamps back through the session zone, even after AT TIME ZONE.
      : "CAST(FORMATDATETIME("+column+",'yyyy-MM-dd','en','"+zone.getId()+"') AS DATE)";
  }
  public String timestamp(String expression) { return "CAST(" + expression + " AS " + (mysql ? "DATETIME(6)" : "TIMESTAMP WITH TIME ZONE") + ")"; }
  /** Legacy observations keep UTC DATETIME fields on MySQL and server-local TIMESTAMP on H2.
   * Project MySQL's UTC fields into the session zone before mixing them with TIMESTAMP columns;
   * Connector/J then reconstructs their real instant using that same session zone. */
  public String observationTime(String expression) {
    return mysql ? "CONVERT_TZ("+expression+",'+00:00',@@session.time_zone)" : timestamp(expression);
  }
  public String bucket(String column, boolean minute) {
    return mysql ? "CAST(DATE_FORMAT(" + column + ",'" + (minute ? "%Y-%m-%d %H:%i:00" : "%Y-%m-%d %H:00:00") + "') AS DATETIME)"
      : "DATE_TRUNC('" + (minute ? "MINUTE" : "HOUR") + "'," + column + ")";
  }
  public String upsert(String table, String keys, String columns) {
    for (String value : List.of(table, keys, columns)) if (!value.matches("[a-z_,]+")) throw new IllegalArgumentException("Invalid SQL identifier");
    var names = columns.split(",");
    String base = "INSERT INTO " + table + "(" + columns + ") VALUES(" + String.join(",", Collections.nCopies(names.length, "?")) + ")";
    if (!mysql) return base.replaceFirst("INSERT INTO", "MERGE INTO").replace(") VALUES", ") KEY(" + keys + ") VALUES");
    Set<String> keySet = Set.of(keys.split(","));
    return base + " ON DUPLICATE KEY UPDATE " + Arrays.stream(names).filter(n -> !keySet.contains(n))
      .map(n -> n + "=VALUES(" + n + ")").collect(java.util.stream.Collectors.joining(","));
  }
  /**
   * H2 2.2 picks the two-column foreign-key index for telemetry even when a longer index
   * matches every equality column, then reads and sorts a device's whole history. Pinning
   * the index and spelling out its column order lets H2 seek the first row. MySQL plans
   * these lookups itself, so it gets no hint.
   */
  public String useIndex(String index) {
    if (!index.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid SQL identifier");
    return mysql ? "" : " USE INDEX (" + index + ")";
  }
}
