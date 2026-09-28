package app.zhinong.simulation;

import app.zhinong.api.ApiException;
import app.zhinong.security.Identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SimulationStore {

  private final JdbcTemplate db;
  private final HikariDataSource pool;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final boolean mysql;

  public SimulationStore(
    JdbcTemplate primary,
    Environment env,
    ObjectMapper json,
    WeatherData weather
  ) {
    this.json = json;
    String url = env.getProperty("FARM_SIM_DATABASE_URL", "");
    mysql = !url.isBlank();
    if (mysql) {
      if (
        !url.startsWith("jdbc:mysql://127.0.0.1:") &&
        !url.startsWith("jdbc:mysql://localhost:")
      ) throw new IllegalStateException(
        "Simulation database must be local MySQL"
      );
      var config = new HikariConfig();
      config.setJdbcUrl(url);
      config.setUsername(env.getRequiredProperty("FARM_SIM_DATABASE_USER"));
      config.setPassword(env.getRequiredProperty("FARM_SIM_DATABASE_PASSWORD"));
      config.setMaximumPoolSize(3);
      config.setPoolName("simulation-db");
      config.setConnectionTimeout(10000);
      pool = new HikariDataSource(config);
      db = new JdbcTemplate(pool);
    } else {
      pool = null;
      db = primary;
    }
    tx = new TransactionTemplate(
      new DataSourceTransactionManager(
        Objects.requireNonNull(db.getDataSource())
      )
    );
    String large = mysql ? "LONGTEXT" : "CLOB";
    db.execute(
      "CREATE TABLE IF NOT EXISTS sim_weather(dataset_id VARCHAR(64) NOT NULL,weather_date DATE NOT NULL,temperature DOUBLE NOT NULL,rain DOUBLE NOT NULL,wind DOUBLE NOT NULL,et0 DOUBLE NOT NULL,PRIMARY KEY(dataset_id,weather_date))"
    );
    db.execute(
      "CREATE TABLE IF NOT EXISTS sim_runs(id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,label VARCHAR(100) NOT NULL,actor VARCHAR(60) NOT NULL,created_at TIMESTAMP NOT NULL,model_version VARCHAR(40) NOT NULL,weather_sha VARCHAR(64) NOT NULL,request_json " +
        large +
        " NOT NULL,result_json " +
        large +
        " NOT NULL)"
    );
    db.execute(
      "CREATE TABLE IF NOT EXISTS sim_run_days(run_id VARCHAR(36) NOT NULL,scenario VARCHAR(20) NOT NULL,day_date DATE NOT NULL,backlog_mu DOUBLE NOT NULL,treated_mu DOUBLE NOT NULL,irrigation_m3 DOUBLE NOT NULL,PRIMARY KEY(run_id,scenario,day_date),FOREIGN KEY(run_id) REFERENCES sim_runs(id) ON DELETE CASCADE)"
    );
    // A data set is inserted atomically and keyed by the content hash for reproducibility.
    String hash = (String) weather.provenance.get("sha256");
    tx.executeWithoutResult(status -> {
      if (
        db.queryForObject(
          "SELECT COUNT(*) FROM sim_weather WHERE dataset_id=?",
          Integer.class,
          hash
        ) ==
        0
      ) for (var day : weather.days)
        db.update(
          "INSERT INTO sim_weather VALUES(?,?,?,?,?,?)",
          hash,
          java.sql.Date.valueOf(day.date()),
          day.temperature(),
          day.rain(),
          day.wind(),
          day.et0()
        );
    });
  }

  public Map<String, Object> status() {
    return Map.of(
      "engine",
      mysql ? "MySQL" : "H2",
      "separateDatabase",
      mysql,
      "weatherRows",
      db.queryForObject("SELECT COUNT(*) FROM sim_weather", Integer.class)
    );
  }

  @SuppressWarnings("unchecked")
  public String save(
    SimulationInput input,
    Map<String, Object> result,
    Map<String, Object> provenance
  ) {
    String tenant = Identity.tenant(),
      id = UUID.randomUUID().toString(),
      actor = Identity.current().username();
    String requestJson = encode(input),
      resultJson = encode(result);
    tx.executeWithoutResult(status -> {
      db.update(
        "INSERT INTO sim_runs VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        tenant,
        input.farmId(),
        input.label().strip(),
        actor,
        Timestamp.from(Instant.now()),
        SimulationEngine.VERSION,
        provenance.get("sha256"),
        requestJson,
        resultJson
      );
      for (var scenario : (List<Map<String, Object>>) result.get("scenarios")) {
        var rows = new ArrayList<Object[]>();
        for (var day : (List<Map<String, Object>>) scenario.get("days"))
          rows.add(
            new Object[] {
              id,
              scenario.get("id"),
              java.sql.Date.valueOf((String) day.get("date")),
              day.get("backlogMu"),
              day.get("treatedMu"),
              day.get("irrigationM3"),
            }
          );
        db.batchUpdate("INSERT INTO sim_run_days VALUES(?,?,?,?,?,?)", rows);
      }
    });
    return id;
  }

  public List<Map<String, Object>> list(String farm) {
    String sql =
      "SELECT id,label,farm_id,actor,created_at,model_version FROM sim_runs WHERE tenant_id=?";
    var args = new ArrayList<Object>();
    args.add(Identity.tenant());
    if (farm != null && !farm.isBlank()) {
      sql += " AND farm_id=?";
      args.add(farm);
    }
    return db.query(
      sql + " ORDER BY created_at DESC,id LIMIT 100",
      (rs, n) ->
        Map.of(
          "id",
          rs.getString(1),
          "label",
          rs.getString(2),
          "farmId",
          rs.getString(3),
          "actor",
          rs.getString(4),
          "createdAt",
          rs.getTimestamp(5).toInstant().toString(),
          "modelVersion",
          rs.getString(6)
        ),
      args.toArray()
    );
  }

  public Object get(String id) {
    var rows = db.query(
      "SELECT result_json FROM sim_runs WHERE tenant_id=? AND id=?",
      (rs, n) -> rs.getString(1),
      Identity.tenant(),
      id
    );
    if (rows.isEmpty()) throw ApiException.missing();
    try {
      return Map.of("id", id, "result", json.readTree(rows.getFirst()));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored simulation result", e);
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @PreDestroy
  public void close() {
    if (pool != null) pool.close();
  }
}
