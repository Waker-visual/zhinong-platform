package app.zhinong.business;

import app.zhinong.api.ApiException;
import app.zhinong.security.AuthService;
import app.zhinong.security.Identity;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TenantService {

  private final JdbcTemplate db;
  private final AuthService auth;
  private final Store store;

  public TenantService(JdbcTemplate db, AuthService auth, Store store) {
    this.db = db;
    this.auth = auth;
    this.store = store;
  }

  public Object list() {
    Identity.require("PLATFORM_ADMIN");
    return db.queryForList(
      "SELECT id,code,name,enabled FROM tenants WHERE code<>'platform' ORDER BY code"
    );
  }

  public Object create(Records.Tenant input) {
    Identity.require("PLATFORM_ADMIN");
    if (input.code().equals("platform")) throw new ApiException(400, "租户代码已保留");
    String id = UUID.randomUUID().toString();
    db.update("INSERT INTO tenants(id,code,name) VALUES(?,?,?)", id, input.code(), input.name());
    addMember(id, "admin", "租户管理员", input.adminPassword(), "ADMIN");
    store.audit("CREATE_TENANT", id);
    return Map.of("id", id, "code", input.code(), "name", input.name());
  }

  public void enabled(String id, boolean enabled) {
    Identity.require("PLATFORM_ADMIN");
    int count = db.update(
      "UPDATE tenants SET enabled=? WHERE id=? AND code<>'platform'",
      enabled,
      id
    );
    if (count == 0) throw ApiException.missing();
    store.audit(enabled ? "ENABLE_TENANT" : "DISABLE_TENANT", id);
  }

  public Object members() {
    Identity.require("ADMIN");
    return db.queryForList(
      "SELECT id,username,display_name,role,enabled FROM members WHERE tenant_id=? ORDER BY username",
      Identity.tenant()
    );
  }

  public Object member(Records.Member input) {
    Identity.require("ADMIN");
    String id = addMember(
      Identity.tenant(),
      input.username(),
      input.displayName(),
      input.password(),
      input.role()
    );
    store.audit("CREATE_MEMBER", id);
    return Map.of("id", id, "username", input.username(), "role", input.role());
  }

  public void memberEnabled(String id, boolean enabled) {
    Identity.require("ADMIN");
    if (id.equals(Identity.current().memberId())) throw new ApiException(409, "不能停用当前账号");
    store.lock("members", id);
    db.update(
      "UPDATE members SET enabled=? WHERE tenant_id=? AND id=?",
      enabled,
      Identity.tenant(),
      id
    );
    store.audit(enabled ? "ENABLE_MEMBER" : "DISABLE_MEMBER", id);
  }

  public String addMember(
    String tenant,
    String username,
    String name,
    String password,
    String role
  ) {
    String id = UUID.randomUUID().toString();
    db.update(
      "INSERT INTO members(id,tenant_id,username,display_name,password_hash,role) VALUES(?,?,?,?,?,?)",
      id,
      tenant,
      username,
      name,
      auth.hashPassword(password),
      role
    );
    return id;
  }
}
