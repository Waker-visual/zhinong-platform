package app.zhinong.business;

import java.time.*;
import org.springframework.context.annotation.*;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.*;
import tools.jackson.databind.module.SimpleModule;

@Configuration
public class DatabaseJsonConfiguration {
  /** Retain the session zone when Connector/J returns a DATETIME as LocalDateTime. */
  @Bean
  JacksonModule databaseTimes(app.zhinong.database.DatabaseSql dialect, org.springframework.core.env.Environment env) {
    var module = new SimpleModule("database-utc-times");
    String url = env.getProperty("spring.datasource.url", "");
    ZoneId zone = url.matches(".*[?&]connectionTimeZone=UTC(?:&.*)?$") ? ZoneOffset.UTC : ZoneId.systemDefault();
    if (dialect.mysql()) module.addSerializer(LocalDateTime.class, new ValueSerializer<LocalDateTime>() {
      @Override public void serialize(LocalDateTime value, JsonGenerator output, SerializationContext context) {
        output.writeString(value.atZone(zone).toInstant().toString());
      }
    });
    return module;
  }

  /** Keep DATE columns as yyyy-MM-dd; Jackson 3 would write java.sql.Date as a UTC instant of local midnight. */
  @Bean
  JacksonModule databaseDates() {
    return new SimpleModule("database-dates").addSerializer(java.sql.Date.class, new ValueSerializer<java.sql.Date>() {
      @Override public void serialize(java.sql.Date value, JsonGenerator output, SerializationContext context) {
        output.writeString(value.toString());
      }
    });
  }
}
