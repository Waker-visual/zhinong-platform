package app.zhinong.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Internal scheduler only: identity is derived from an existing administrator-owned policy. */
@Component
public class ScheduledIdentity {
  private final JdbcTemplate db;
  public ScheduledIdentity(JdbcTemplate db) {this.db=db;}
  public boolean asPolicyOwner(String tenant,String plot,Runnable work) {
    var rows=db.queryForList("""
      SELECT m.id,m.tenant_id,t.name AS tenant_name,m.username,m.display_name,m.role
      FROM ai_irrigation_policies p JOIN members m ON m.tenant_id=p.tenant_id AND m.id=p.owner_id
      JOIN tenants t ON t.id=m.tenant_id
      WHERE p.tenant_id=? AND p.plot_id=? AND p.mode='AUTO' AND m.enabled=TRUE AND t.enabled=TRUE AND m.role='ADMIN'
      """,tenant,plot);
    if(rows.isEmpty()) return false;
    var m=rows.getFirst();
    Identity previous=null; try {previous=Identity.current();} catch(app.zhinong.api.ApiException ignored) {}
    Identity.set(new Identity(m.get("ID").toString(),tenant,m.get("TENANT_NAME").toString(),m.get("USERNAME").toString(),m.get("DISPLAY_NAME").toString(),"ADMIN"));
    try {work.run(); return true;} finally {if(previous==null) Identity.clear();else Identity.set(previous);}
  }
}
