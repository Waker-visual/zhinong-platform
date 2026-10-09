package app.zhinong.database;

import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Only identifiers owned by application code may be passed to these helpers. */
@Component
public class DatabaseSql {
  private final boolean mysql;
  public DatabaseSql(Environment env) { mysql = env.getProperty("spring.datasource.url", "").startsWith("jdbc:mysql:"); }
  public boolean mysql() { return mysql; }
  public String calendarDate(String column) {
    String offset=java.time.OffsetDateTime.now().getOffset().getId();
    return "CAST("+column+(mysql?"":" AT TIME ZONE '"+offset+"'")+" AS DATE)";
  }
  public String timestamp(String expression) { return "CAST(" + expression + " AS " + (mysql ? "DATETIME(6)" : "TIMESTAMP WITH TIME ZONE") + ")"; }
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
}
