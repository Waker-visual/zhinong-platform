package app.zhinong.business;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.*;
import org.springframework.context.annotation.*;

@Configuration
public class DatabaseJsonConfiguration {
  /** Retain the session zone when Connector/J returns a DATETIME as LocalDateTime. */
  @Bean
  com.fasterxml.jackson.databind.Module databaseTimes(SqlDialect dialect, org.springframework.core.env.Environment env) {
    var module = new SimpleModule("database-utc-times");
    String url = env.getProperty("spring.datasource.url", "");
    ZoneId zone = url.matches(".*[?&]connectionTimeZone=UTC(?:&.*)?$") ? ZoneOffset.UTC : ZoneId.systemDefault();
    if (dialect.mysql()) module.addSerializer(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
      @Override public void serialize(LocalDateTime value, JsonGenerator output, SerializerProvider provider) throws IOException {
        output.writeString(value.atZone(zone).toInstant().toString());
      }
    });
    return module;
  }
}
