package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class H2ConstraintConnectionTest {
  @TempDir Path directory;

  @Test
  void fileDatabaseEnforcesChecksAfterSchemaConnectionIsClosed() throws SQLException {
    String url = "jdbc:h2:file:" + directory.resolve("farm");
    try (var writer = DriverManager.getConnection(url, "sa", "")) {
      try (var initializer = DriverManager.getConnection(url, "sa", "")) {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).populate(initializer);
        try (var seed = initializer.createStatement()) {
          seed.executeUpdate("INSERT INTO tenants(id,code,name) VALUES('demo','demo','Demo')");
          seed.executeUpdate("INSERT INTO farms(id,tenant_id,name,description) VALUES('farm','demo','Farm','')");
          seed.executeUpdate("""
            INSERT INTO devices(id,tenant_id,farm_id,name,metric,unit,adapter)
            VALUES('sensor','demo','farm','Sensor','SOIL_MOISTURE','%','SIMULATED')
            """);
        }
      }

      try (var sql = writer.createStatement()) {
        sql.executeUpdate("INSERT INTO tenants(id,code,name) VALUES('new','new','New tenant')");
        assertAll(
          () -> assertEquals(1, sql.executeUpdate("""
            INSERT INTO members(id,tenant_id,username,display_name,password_hash,role)
            VALUES('member','new','admin','Admin','test-fixture','ADMIN')
            """)),
          () -> assertEquals(1, insertReading(sql, "SIMULATED")),
          () -> assertEquals(1, insertReading(sql, "MANUAL")),
          () -> assertEquals(1, insertReading(sql, "HTTP_PUSH")),
          () -> assertEquals(1, sql.executeUpdate("""
            INSERT INTO device_alerts(id,tenant_id,device_id,metric,measured_value,message,opened_at,updated_at)
            VALUES('alert','demo','sensor','SOIL_MOISTURE',18,'Low moisture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """)),
          () -> assertEquals(1, sql.executeUpdate("""
            INSERT INTO ai_farm_briefings(id,tenant_id,farm_id,report_date,status,source,diagnostic,body_json,generated_at,updated_at)
            VALUES('briefing','demo','farm',CURRENT_DATE,'READY','RULE_ENGINE','','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """)),
          () -> assertEquals("23513", assertThrows(SQLException.class, () -> insertReading(sql, "INVALID")).getSQLState())
        );
      }
    }
  }

  private int insertReading(Statement sql, String source) throws SQLException {
    return sql.executeUpdate("""
      INSERT INTO telemetry_readings(id,tenant_id,device_id,metric,measured_value,measured_at,received_at,source)
      VALUES(RANDOM_UUID(),'demo','sensor','SOIL_MOISTURE',18,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'%s')
      """.formatted(source));
  }
}
