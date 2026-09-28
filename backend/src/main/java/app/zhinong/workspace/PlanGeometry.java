package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import java.math.BigDecimal;
import java.util.*;

/** LOCAL_PLAN coordinates: x 0..1000, y 0..700; not latitude/longitude or measured area. */
public final class PlanGeometry {

  private PlanGeometry() {}

  public static void point(BigDecimal x, BigDecimal y) {
    if (x == null && y == null) return;
    if (
      x == null ||
      y == null ||
      x.doubleValue() < 0 ||
      x.doubleValue() > 1000 ||
      y.doubleValue() < 0 ||
      y.doubleValue() > 700
    ) {
      throw new ApiException(400, "点位必须同时填写 X/Y，范围分别为 0–1000、0–700");
    }
  }

  public static void polygon(List<List<Double>> points) {
    if (points.isEmpty()) return;
    if (points.size() < 3 || points.size() > 80) throw new ApiException(
      400,
      "地块边界需要 3–80 个顶点"
    );
    var distinct = new HashSet<List<Double>>();
    for (var p : points) {
      if (
        p == null ||
        p.size() != 2 ||
        p.get(0) == null ||
        p.get(1) == null ||
        !Double.isFinite(p.get(0)) ||
        !Double.isFinite(p.get(1))
      ) {
        throw new ApiException(400, "地块坐标必须为有限数值 [x,y]");
      }
      point(BigDecimal.valueOf(p.get(0)), BigDecimal.valueOf(p.get(1)));
      if (!distinct.add(p)) throw new ApiException(400, "地块顶点不能重复");
    }
    double area = 0;
    for (int i = 0; i < points.size(); i++) {
      var a = points.get(i);
      var b = points.get((i + 1) % points.size());
      area += a.get(0) * b.get(1) - b.get(0) * a.get(1);
      for (int j = i + 1; j < points.size(); j++) {
        if (j == i + 1 || (i == 0 && j == points.size() - 1)) continue;
        var c = points.get(j);
        var d = points.get((j + 1) % points.size());
        if (intersects(a, b, c, d)) throw new ApiException(400, "地块边界不能自相交");
      }
    }
    if (Math.abs(area) < 2) throw new ApiException(400, "地块边界面积过小或顶点共线");
  }

  private static double cross(List<Double> a, List<Double> b, List<Double> c) {
    return (
      (b.get(0) - a.get(0)) * (c.get(1) - a.get(1)) - (b.get(1) - a.get(1)) * (c.get(0) - a.get(0))
    );
  }

  private static boolean intersects(
    List<Double> a,
    List<Double> b,
    List<Double> c,
    List<Double> d
  ) {
    if (
      Math.max(a.get(0), b.get(0)) < Math.min(c.get(0), d.get(0)) ||
      Math.max(c.get(0), d.get(0)) < Math.min(a.get(0), b.get(0)) ||
      Math.max(a.get(1), b.get(1)) < Math.min(c.get(1), d.get(1)) ||
      Math.max(c.get(1), d.get(1)) < Math.min(a.get(1), b.get(1))
    ) return false;
    return cross(a, b, c) * cross(a, b, d) <= 0 && cross(c, d, a) * cross(c, d, b) <= 0;
  }
}
