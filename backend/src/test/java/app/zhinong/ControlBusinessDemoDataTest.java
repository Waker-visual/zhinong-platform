package app.zhinong;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.bootstrap.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:business-demo-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true","farm.demo-rich=false","farm.bootstrap-password=Test-Only-Password-429!"})
class ControlBusinessDemoDataTest {
  @Autowired JdbcTemplate db;
  @Test void businessFixturesAreCompleteTenantScopedAndIdempotent() {
    new ControlDemoData(db,true,true,true).run(null);
    var fixtures=new ControlBusinessDemoData(db,true,true);
    long before=db.queryForObject("SELECT COUNT(*) FROM farm_tasks",Long.class);
    fixtures.run(null);
    assertEquals(before+24,db.queryForObject("SELECT COUNT(*) FROM farm_tasks",Long.class));
    assertEquals(24,db.queryForObject("SELECT COUNT(*) FROM task_fieldwork",Integer.class));
    assertEquals(12,db.queryForObject("SELECT COUNT(*) FROM field_issues",Integer.class));
    assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM production",Integer.class));
    assertEquals(0,db.queryForObject("""
      SELECT COUNT(*) FROM production r WHERE NOT EXISTS(SELECT 1 FROM plantings p
      WHERE p.tenant_id=r.tenant_id AND p.plot_id=r.plot_id AND r.record_date BETWEEN p.start_date AND p.end_date)
      """,Integer.class));
    assertEquals(0,db.queryForObject("""
      SELECT COUNT(*) FROM field_issues i JOIN farm_tasks t ON t.tenant_id=i.tenant_id AND t.id=i.task_id
      JOIN task_fieldwork w ON w.tenant_id=t.tenant_id AND w.task_id=t.id
      WHERE i.status='RESOLVED' AND (t.status<>'COMPLETED' OR w.completed_at IS NULL OR w.completion_note='')
      """,Integer.class));
    fixtures.run(null);
    assertEquals(before+24,db.queryForObject("SELECT COUNT(*) FROM farm_tasks",Long.class));
    assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM production",Integer.class));
  }
}
