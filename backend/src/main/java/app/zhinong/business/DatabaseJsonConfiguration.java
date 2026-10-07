package app.zhinong.business;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.*;
import org.springframework.context.annotation.*;

@Configuration
public class DatabaseJsonConfiguration {
  /** Connector/J returns UTC DATETIME as LocalDateTime: retain its zone in API responses. */
  @Bean
  com.fasterxml.jackson.databind.Module databaseTimes(SqlDialect dialect) {
    var module = new SimpleModule("database-utc-times");
    if (dialect.mysql()) module.addSerializer(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
      @Override public void serialize(LocalDateTime value, JsonGenerator output, SerializerProvider provider) throws IOException {
        output.writeString(value.toInstant(ZoneOffset.UTC).toString());
      }
    });
    return module;
  }
}
