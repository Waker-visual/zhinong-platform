package app.zhinong.workspace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="farm.map.automation-enabled",havingValue="true",matchIfMissing=true)
public class FieldMapTicker {
  private final JdbcTemplate db;private final FieldMapService service;
  public FieldMapTicker(JdbcTemplate db,FieldMapService service){this.db=db;this.service=service;}
  @Scheduled(fixedDelay=1000,initialDelay=15000) public void tick() {
    for(String tenant:db.queryForList("SELECT id FROM tenants",String.class)) {
      for(var job:db.queryForList("SELECT id,farm_id FROM farm_map_jobs WHERE tenant_id=? AND status='RUNNING'",tenant)) {
        try{service.tick(tenant,job.get("FARM_ID").toString(),job.get("ID").toString());}
        catch(Exception ex){db.update("UPDATE farm_map_jobs SET result_note='状态更新失败，将自动重试；请检查设备与服务' WHERE tenant_id=? AND id=? AND status='RUNNING'",tenant,job.get("ID"));}
      }
    }
  }
}
