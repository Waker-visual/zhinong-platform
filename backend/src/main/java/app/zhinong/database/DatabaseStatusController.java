package app.zhinong.database;

import app.zhinong.security.Identity;
import java.util.Map;
import org.springframework.jdbc.core.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system/database")
public class DatabaseStatusController {
  private final JdbcTemplate db;
  public DatabaseStatusController(JdbcTemplate db) { this.db = db; }
  @GetMapping public Object status() {
    Identity.current();
    return db.execute((ConnectionCallback<Object>) connection -> {
      String url = connection.getMetaData().getURL();
      boolean mysql = connection.getMetaData().getDatabaseProductName().equals("MySQL");
      boolean local = !mysql || url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost)(:|/).*" );
      try (var statement = connection.prepareStatement("SELECT 1"); var result = statement.executeQuery()) {
        return Map.of("engine", connection.getMetaData().getDatabaseProductName(), "location", local ? "LOCAL" : "REMOTE",
          "connected", result.next() && result.getInt(1) == 1, "dataPolicy", "SYNTHETIC_RESEARCH");
      }
    });
  }
}
