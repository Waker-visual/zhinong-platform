package app.zhinong.business;

import app.zhinong.api.ApiException;
import app.zhinong.security.Identity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Store {

  private static final Set<String> TABLES = Set.of(
    "farms",
    "plots",
    "plantings",
    "farm_tasks",
    "production",
    "devices",
    "observations",
    "audit_events",
    "members"
  );
  private final JdbcTemplate db;
  private final app.zhinong.database.DatabaseSql sql;
  private final SqlDialect dialect;

  public Store(JdbcTemplate db, app.zhinong.database.DatabaseSql sql, SqlDialect dialect) {
    this.db = db;
    this.sql = sql;
    this.dialect = dialect;
  }

  private String table(String name) {
    if (!TABLES.contains(name)) throw new IllegalArgumentException("Unknown table");
    return name;
  }

  public List<Map<String, Object>> list(String table) {
    // Members are exposed separately with a fixed projection, never a password hash.
    if ("members".equals(table)) throw new IllegalArgumentException("Use member projection");
    String order = "observations".equals(table)
      ? "measured_at DESC,id"
      : "audit_events".equals(table)
        ? "occurred_at DESC,id"
        : "id";
    return db.queryForList(
      "SELECT * FROM " + table(table) + " WHERE tenant_id=? AND " + activeScope(table) + " ORDER BY " + order,
      Identity.tenant()
    );
  }

  /** Default lists omit retired farms; direct tenant-scoped history remains recoverable. */
  public static String activeScope(String table) {
    return switch(table) {
      case "farms" -> "NOT EXISTS(SELECT 1 FROM farm_archives a WHERE a.tenant_id=farms.tenant_id AND a.farm_id=farms.id)";
      case "plots","devices" -> "NOT EXISTS(SELECT 1 FROM farm_archives a WHERE a.tenant_id="+table+".tenant_id AND a.farm_id="+table+".farm_id)";
      case "plantings","farm_tasks","production","field_issues" -> "NOT EXISTS(SELECT 1 FROM plots p JOIN farm_archives a ON a.tenant_id=p.tenant_id AND a.farm_id=p.farm_id WHERE p.tenant_id="+table+".tenant_id AND p.id="+table+".plot_id)";
      default -> "1=1";
    };
  }

  public Map<String, Object> get(String table, String id) {
    var rows = db.queryForList(
      "SELECT * FROM " + table(table) + " WHERE tenant_id=? AND id=?",
      Identity.tenant(),
      id
    );
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> lock(String table, String id) {
    var rows = db.queryForList(
      "SELECT * FROM " + table(table) + " WHERE tenant_id=? AND id=? FOR UPDATE",
      Identity.tenant(),
      id
    );
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  public String insert(String table, LinkedHashMap<String, Object> fields) {
    String id = UUID.randomUUID().toString();
    var all = new LinkedHashMap<String, Object>();
    all.put("id", id);
    all.put("tenant_id", Identity.tenant());
    all.putAll(fields); // Field names originate only from typed service code.
    db.update(
      "INSERT INTO " +
        table(table) +
        "(" +
        String.join(",", all.keySet()) +
        ") VALUES(" +
        String.join(",", Collections.nCopies(all.size(), "?")) +
        ")",
      all.values().toArray()
    );
    audit("CREATE_" + table.toUpperCase(Locale.ROOT), id);
    return id;
  }

  public void audit(String action, String resource) {
    Identity actor = Identity.current();
    db.update(
      "INSERT INTO audit_events(id,tenant_id,actor,action,resource_id,occurred_at) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(),
      actor.tenantId(),
      actor.username(),
      action,
      resource,
      Timestamp.from(Instant.now())
    );
  }

  public JdbcTemplate db() {
    return db;
  }

  public app.zhinong.database.DatabaseSql sql() { return sql; }
  public SqlDialect dialect() { return dialect; }

  public static LinkedHashMap<String, Object> fields(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }
}
