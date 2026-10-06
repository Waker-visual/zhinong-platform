package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import java.math.BigDecimal;

/** Geographic coordinates are always WGS84; local sketches are deliberately separate. */
public final class AssetLocation {
  private AssetLocation() {}

  public static String mode(String mode) { return mode == null ? "LOCAL_PLAN" : mode; }

  public static void validate(String mode, BigDecimal x, BigDecimal y, Double lat, Double lon) {
    if (mode(mode).equals("LOCAL_PLAN")) {
      PlanGeometry.point(x, y);
      if (lat != null || lon != null) throw new ApiException(400, "平面点位不能同时提交经纬度");
    } else if (mode.equals("WGS84")) {
      if (x != null || y != null || lat == null || lon == null || !Double.isFinite(lat) || !Double.isFinite(lon)
          || Math.abs(lat) > 80 || Math.abs(lon) > 180) {
        throw new ApiException(400, "WGS84 点位需有效经纬度，且不能同时提交平面坐标");
      }
    } else throw new ApiException(400, "不支持的坐标系");
  }
}
