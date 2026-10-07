package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farms/{farmId}/map-config")
@Transactional
public class MapConfiguration {

  private final Store store;

  public MapConfiguration(Store store) {
    this.store = store;
  }

  public record Input(
    @NotNull @Pattern(regexp = "SATELLITE|STREET|PLAN") String mode,
    @DecimalMin("-80") @DecimalMax("80") double latitude,
    @DecimalMin("-180") @DecimalMax("180") double longitude,
    @Min(100) @Max(20000) int widthMeters,
    @Min(100) @Max(20000) int heightMeters,
    @NotBlank @Size(max = 120) String locationLabel,
    @Min(0) int revision
  ) {}

  @GetMapping
  public Map<String, Object> get(@PathVariable String farmId) {
    store.get("farms", farmId);
    var rows = store
      .db()
      .queryForList(
        """
        SELECT mode AS "mode",latitude AS "latitude",longitude AS "longitude",
          width_meters AS "widthMeters",height_meters AS "heightMeters",
          location_label AS "locationLabel",revision AS "revision"
        FROM farm_georeference WHERE tenant_id=? AND farm_id=?
        """,
        Identity.tenant(),
        farmId
      );
    Map<String, Object> result = rows.isEmpty()
      ? new LinkedHashMap<>(
          Map.of(
            "mode",
            "SATELLITE",
            "latitude",
            47.26,
            "longitude",
            132.73,
            "widthMeters",
            1600,
            "heightMeters",
            1120,
            "locationLabel",
            "建三江公开农业区域 · 示例位置待校准",
            "revision",
            0
          )
        )
      : rows.getFirst();
    result.put("configured", !rows.isEmpty());
    result.put("coordinateSystem", "WGS84");
    result.put("imagerySource", "Esri World Imagery");
    result.put(
      "imageryNotice",
      "卫星底图具有拍摄时间差，不是实时视频；叠加地块以用户保存的布局为准。"
    );
    return result;
  }

  @PutMapping
  public Map<String, Object> save(
    @PathVariable String farmId,
    @Valid @RequestBody Input input
  ) {
    Identity.require("ADMIN");
    store.lock("farms", farmId);
    if (
      !Double.isFinite(input.latitude()) || !Double.isFinite(input.longitude())
    ) throw new ApiException(400, "地图中心坐标无效");
    double halfLon =
      input.widthMeters() /
      (111320 * Math.cos(Math.toRadians(input.latitude()))) /
      2;
    if (Math.abs(input.longitude()) + halfLon > 180) throw new ApiException(
      400,
      "当前布局不支持跨越国际日期变更线，请调整中心或覆盖范围"
    );
    var old = get(farmId);
    if (
      ((Number) old.get("revision")).intValue() != input.revision()
    ) throw new ApiException(409, "地图位置已被修改，请刷新后再保存");
    store
      .db()
      .update(
        store.dialect().upsert("farm_georeference", "tenant_id,farm_id,mode,latitude,longitude,width_meters,height_meters,location_label,revision", "tenant_id,farm_id"),
        Identity.tenant(),
        farmId,
        input.mode(),
        input.latitude(),
        input.longitude(),
        input.widthMeters(),
        input.heightMeters(),
        input.locationLabel().strip(),
        input.revision() + 1
      );
    store.audit("UPDATE_FARM_MAP", farmId);
    store.db().update("""
      INSERT INTO farm_profiles(tenant_id,farm_id) SELECT ?,? WHERE NOT EXISTS
      (SELECT 1 FROM farm_profiles WHERE tenant_id=? AND farm_id=?)
      """, Identity.tenant(), farmId, Identity.tenant(), farmId);
    // Invalidate a layout being edited against an older map extent.
    store.db().update("UPDATE farm_profiles SET layout_revision=layout_revision+1 WHERE tenant_id=? AND farm_id=?",
      Identity.tenant(), farmId);
    return get(farmId);
  }
}
