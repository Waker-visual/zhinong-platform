package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Called inside the ingestion/command transaction; serializes with credential and asset changes. */
@Service
public class DeviceCredentials {
  private final JdbcTemplate db;
  public DeviceCredentials(JdbcTemplate db) { this.db = db; }
  public record Device(String tenant, String id) {}

  public Device authenticate(String key) {
    if (key == null || key.length() < 32 || key.length() > 128) throw new ApiException(401, "设备接入凭据无效");
    var matches = db.queryForList("""
      SELECT p.tenant_id,p.device_id FROM asset_profiles p JOIN tenants t ON t.id=p.tenant_id
      WHERE p.credential_hash=? AND p.protocol='HTTP_PUSH' AND p.lifecycle='ACTIVE' AND t.enabled=TRUE
      """, TelemetryService.hash(key));
    if (matches.isEmpty()) throw new ApiException(401, "设备接入凭据无效或设备/租户已停用");
    var device = new Device(matches.getFirst().get("TENANT_ID").toString(), matches.getFirst().get("DEVICE_ID").toString());
    db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", device.tenant());
    db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE", device.tenant(), device.id());
    long valid = db.queryForObject("""
      SELECT COUNT(*) FROM asset_profiles p JOIN tenants t ON t.id=p.tenant_id
      WHERE p.tenant_id=? AND p.device_id=? AND p.credential_hash=?
        AND p.protocol='HTTP_PUSH' AND p.lifecycle='ACTIVE' AND t.enabled=TRUE
      """, Long.class, device.tenant(), device.id(), TelemetryService.hash(key));
    if (valid != 1) throw new ApiException(401, "设备接入凭据已失效");
    return device;
  }
}
