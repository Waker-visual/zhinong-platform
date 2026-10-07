package app.zhinong.business;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The small set of SQL operations that differ between H2 and MySQL 8. */
@Component
public class SqlDialect {
  private final boolean mysql;

  public SqlDialect(@Value("${spring.datasource.url}") String url) {
    mysql = url.startsWith("jdbc:mysql:");
    if (!mysql && !url.startsWith("jdbc:h2:")) throw new IllegalArgumentException("仅支持 H2 或 MySQL 8 数据库");
  }

  public boolean mysql() { return mysql; }
  public String timestamp(String expression) {
    return "CAST(" + expression + (mysql ? " AS DATETIME(6))" : " AS TIMESTAMP WITH TIME ZONE)");
  }
  public String bucket(String expression, boolean hourly) {
    if (!mysql) return "DATE_TRUNC('" + (hourly ? "HOUR" : "MINUTE") + "'," + expression + ")";
    return "CAST(DATE_FORMAT(" + expression + ",'%Y-%m-%d %H:" + (hourly ? "00" : "%i") + ":00') AS DATETIME)";
  }

  /** Identifiers come from service constants, never request fields. */
  public String upsert(String table, String columns, String keys) {
    for (String identifier : (table + "," + columns + "," + keys).split(",")) {
      if (!identifier.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid SQL identifier");
    }
    var names = List.of(columns.split(","));
    String values = String.join(",", Collections.nCopies(names.size(), "?"));
    if (!mysql) return "MERGE INTO " + table + "(" + columns + ") KEY(" + keys + ") VALUES(" + values + ")";
    var primary = Set.of(keys.split(","));
    String updates = names.stream().filter(n -> !primary.contains(n)).map(n -> n + "=VALUES(" + n + ")")
      .collect(java.util.stream.Collectors.joining(","));
    return "INSERT INTO " + table + "(" + columns + ") VALUES(" + values + ") ON DUPLICATE KEY UPDATE " + updates;
  }
}
