package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;

/** Media metadata shares the asset's tenant, lifecycle and optimistic revision. */
@Service
public class CameraProfiles {
  public static final Map<String, String> SCENES = Map.of(
    "qinghe", "青禾 · 稻田长势", "fengsui", "丰穗 · 成熟稻田",
    "xinhe", "新禾 · 水田育苗", "runze", "润泽 · 渠道灌溉", "tianye", "田野 · 田间道路");
  private final Store store;
  public CameraProfiles(Store store) { this.store = store; }

  public List<Map<String, String>> scenes() {
    return SCENES.entrySet().stream().sorted(Map.Entry.comparingByKey())
      .map(e -> Map.of("value", e.getKey(), "label", e.getValue())).toList();
  }

  public Map<String, Object> read(String tenant, String device) {
    var rows = store.db().queryForList("""
      SELECT media_mode AS "mode",demo_scene AS "demoScene",source_url AS "sourceUrl",view_label AS "viewLabel"
      FROM camera_profiles WHERE tenant_id=? AND device_id=?
      """, tenant, device);
    var row = rows.isEmpty() ? new LinkedHashMap<String, Object>(Map.of(
      "mode", "NONE", "demoScene", "qinghe", "sourceUrl", "", "viewLabel", "")) : rows.getFirst();
    row.put("playbackUrl", "DEMO_IMAGE".equals(row.get("mode"))
      ? "/camera-demo/" + row.get("demoScene") + ".png" : row.get("sourceUrl"));
    return row;
  }

  public void validate(String deviceType, WorkspaceInputs.Camera input) {
    if (input == null) return;
    if (!"CAMERA".equals(deviceType)) throw new ApiException(400, "仅视频监测设备可以配置画面来源");
    if ("DEMO_IMAGE".equals(input.mode()) && !SCENES.containsKey(input.demoScene() == null ? "" : input.demoScene()))
      throw new ApiException(400, "请选择有效的演示场景");
    if (Set.of("IMAGE", "VIDEO").contains(input.mode())) {
      try {
        var uri = URI.create(input.sourceUrl() == null ? "" : input.sourceUrl().strip());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw new ApiException(400, "画面地址须为不含账号口令和片段的 HTTPS 地址");
      }
    }
  }

  public void save(String tenant, String device, String type, WorkspaceInputs.Camera input) {
    if (!"CAMERA".equals(type)) {
      store.db().update("DELETE FROM camera_profiles WHERE tenant_id=? AND device_id=?", tenant, device);
    } else if (input != null) {
      store.db().update(store.sql().upsert("camera_profiles", "tenant_id,device_id",
        "tenant_id,device_id,media_mode,demo_scene,source_url,view_label"), tenant, device, input.mode(),
        "DEMO_IMAGE".equals(input.mode()) ? input.demoScene() : "qinghe",
        Set.of("IMAGE", "VIDEO").contains(input.mode()) ? input.sourceUrl().strip() : "",
        input.viewLabel() == null ? "" : input.viewLabel().strip());
    }
  }
}
