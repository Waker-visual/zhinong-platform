package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.business.DatabaseTime;
import app.zhinong.workspace.DemoTelemetryStream;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:stream-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true", "farm.demo-rich=false", "farm.demo-stream.enabled=true",
  "farm.demo-stream.days=1", "farm.demo-stream.delay-ms=3600000",
  "farm.bootstrap-password=Test-Only-Password-429!"
})
class DemoTelemetryStreamTest {
  @Autowired JdbcTemplate db;
  @Autowired DemoTelemetryStream stream;

  @Test
  void completeWindowRepairsInternalGapsWithoutDuplicatingRowsOrTouchingOtherSources() {
    var device = db.queryForList("SELECT * FROM asset_profiles WHERE protocol='SIMULATED' ORDER BY tenant_id").getFirst();
    String tenant = device.get("TENANT_ID").toString(), id = device.get("DEVICE_ID").toString();
    Instant now = Instant.now();
    stream.refresh(now, true);
    long total = count();
    var rows = db.queryForList("SELECT id,measured_at FROM telemetry_readings WHERE tenant_id=? AND device_id=? ORDER BY measured_at", tenant,id);
    assertEquals(97, rows.size());
    for (int i = 1; i < rows.size(); i++) assertEquals(900,
      Duration.between(DatabaseTime.instant(rows.get(i-1).get("MEASURED_AT")), DatabaseTime.instant(rows.get(i).get("MEASURED_AT"))).getSeconds());
    stream.refresh(now, true);
    assertEquals(total, count());
    db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND id=?", tenant,id,rows.get(24).get("ID"));
    stream.refresh(now, true);
    assertEquals(total, count());
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM telemetry_readings r JOIN asset_profiles p ON p.tenant_id=r.tenant_id AND p.device_id=r.device_id WHERE p.protocol<>'SIMULATED'", Integer.class));
  }

  @Test
  void changedProtocolAndDisabledTenantAreExcludedEvenFromBackfill() {
    var device = db.queryForList("SELECT * FROM asset_profiles WHERE protocol='SIMULATED' ORDER BY tenant_id").getFirst();
    String tenant = device.get("TENANT_ID").toString(), id = device.get("DEVICE_ID").toString();
    var row = db.queryForList("SELECT id FROM telemetry_readings WHERE tenant_id=? AND device_id=? ORDER BY measured_at LIMIT 1",tenant,id).getFirst();
    db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND id=?",tenant,row.get("ID"));
    long missing = count();
    try {
      db.update("UPDATE asset_profiles SET protocol='HTTP_PUSH' WHERE tenant_id=? AND device_id=?",tenant,id);
      stream.refresh(Instant.now(),true);
      assertEquals(missing,count());
      db.update("UPDATE asset_profiles SET protocol='SIMULATED' WHERE tenant_id=? AND device_id=?",tenant,id);
      db.update("UPDATE tenants SET enabled=FALSE WHERE id=?",tenant);
      stream.refresh(Instant.now(),true);
      assertEquals(missing,count());
    } finally {
      db.update("UPDATE tenants SET enabled=TRUE WHERE id=?",tenant);
      db.update("UPDATE asset_profiles SET protocol='SIMULATED' WHERE tenant_id=? AND device_id=?",tenant,id);
      stream.refresh(Instant.now(),true);
    }
  }

  @Test
  void newlyConfiguredLayerGetsItsWholeHistory() {
    var device = db.queryForList("SELECT * FROM asset_profiles WHERE protocol='SIMULATED' ORDER BY tenant_id").getFirst();
    String tenant = device.get("TENANT_ID").toString(), id = device.get("DEVICE_ID").toString();
    try {
      db.update("INSERT INTO device_channels(tenant_id,device_id,metric) VALUES(?,?,'SOIL_MOISTURE_3')",tenant,id);
      stream.refresh(Instant.now(),false);
      assertEquals(97, db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='SOIL_MOISTURE_3'",Integer.class,tenant,id));
    } finally {
      db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND metric='SOIL_MOISTURE_3'",tenant,id);
      db.update("DELETE FROM device_channels WHERE tenant_id=? AND device_id=? AND metric='SOIL_MOISTURE_3'",tenant,id);
    }
  }

  private long count() { return db.queryForObject("SELECT COUNT(*) FROM telemetry_readings",Long.class); }

  @Test
  void registeredOperatingFarmKeepsItsOwnHistoryWhenTheGenericStreamRuns() {
    var device=db.queryForList("SELECT d.id,d.tenant_id,d.farm_id FROM devices d JOIN asset_profiles p ON p.tenant_id=d.tenant_id AND p.device_id=d.id WHERE p.protocol='SIMULATED' ORDER BY d.tenant_id").getFirst();
    String tenant=device.get("TENANT_ID").toString(), farm=device.get("FARM_ID").toString(), id=device.get("ID").toString();
    String reading=db.queryForObject("SELECT id FROM telemetry_readings WHERE tenant_id=? AND device_id=? ORDER BY measured_at LIMIT 1",String.class,tenant,id);
    db.update("DELETE FROM telemetry_readings WHERE tenant_id=? AND device_id=? AND id=?",tenant,id,reading);
    long before=db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=?",Long.class,tenant,id);
    db.update("INSERT INTO demo_operating_farms(tenant_id,farm_id,seeded_on,last_daily_date) VALUES(?,?,CURRENT_DATE,CURRENT_DATE)",tenant,farm);
    try {
      stream.refresh(Instant.now(),true);
      assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM telemetry_readings WHERE tenant_id=? AND device_id=?",Long.class,tenant,id));
    } finally {
      db.update("DELETE FROM demo_operating_farms WHERE tenant_id=? AND farm_id=?",tenant,farm);
      stream.refresh(Instant.now(),true);
    }
  }
}
