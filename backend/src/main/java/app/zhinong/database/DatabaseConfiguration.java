package app.zhinong.database;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Keeps the public row contract identical across H2 and MySQL. */
@Configuration
public class DatabaseConfiguration {
  @Bean public static BeanPostProcessor mysqlSessionTimeZone() {
    return new BeanPostProcessor() {
      @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean instanceof HikariDataSource source && source.getJdbcUrl()!=null && source.getJdbcUrl().startsWith("jdbc:mysql:")) {
          // Numeric offsets do not require loading MySQL's named-time-zone tables.
          // Align SQL CURRENT_TIMESTAMP with the application's local workflow timestamps.
          // The RDS launcher explicitly selects UTC for its legacy DATETIME schema.
          if (!source.getJdbcUrl().matches(".*[?&]connectionTimeZone=[^&]+.*"))
            source.addDataSourceProperty("connectionTimeZone", java.time.OffsetDateTime.now().getOffset().getId());
          source.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
          source.addDataSourceProperty("preserveInstants", "true");
        }
        return bean;
      }
    };
  }
  @Bean public JdbcTemplate jdbcTemplate(DataSource source) { return new PortableJdbcTemplate(source); }

  static class PortableJdbcTemplate extends JdbcTemplate {
    private static final Set<String> BOOLEANS = Set.of("enabled", "demo", "operatingdemo", "controlenabled",
      "control_enabled", "credentialconfigured", "tracked", "must_change_password", "researchdemo");
    PortableJdbcTemplate(DataSource source) { super(source); }
    private RowMapper<Map<String, Object>> rows(String sql) {
      Set<String> quoted = new HashSet<>();
      var matcher = Pattern.compile("\"([A-Za-z_][A-Za-z_0-9]*)\"").matcher(sql);
      while (matcher.find()) quoted.add(matcher.group(1));
      return new ColumnMapRowMapper() {
        @Override protected String getColumnKey(String name) {
          return quoted.contains(name) ? name : name.toUpperCase(Locale.ROOT);
        }
        @Override protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
          Object value = super.getColumnValue(rs, index);
          // Connector/J exposes DATETIME expressions without their session zone.
          // Read via JDBC's offset-aware conversion before services compare instants.
          if (value instanceof java.time.LocalDateTime) return rs.getObject(index, java.time.OffsetDateTime.class);
          if (value != null && BOOLEANS.contains(rs.getMetaData().getColumnLabel(index).toLowerCase(Locale.ROOT))) return rs.getBoolean(index);
          return value;
        }
      };
    }
    @Override public List<Map<String,Object>> queryForList(String sql, Object... args) { return query(sql, rows(sql), args); }
    @Override public List<Map<String,Object>> queryForList(String sql) { return query(sql, rows(sql)); }
    @Override public Map<String,Object> queryForMap(String sql, Object... args) { return queryForObject(sql, rows(sql), args); }
    @Override public Map<String,Object> queryForMap(String sql) { return queryForObject(sql, rows(sql)); }
  }
}
