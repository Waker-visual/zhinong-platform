package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import java.math.BigDecimal;
import java.util.*;

/** One catalog governs validation, units, forms and adapter capabilities. */
public final class MetricCatalog {

  public record Metric(
    String code,
    String name,
    String unit,
    double min,
    double max,
    double normal
  ) {}

  public static final List<Metric> METRICS = List.of(
    new Metric("TEMPERATURE", "空气温度", "℃", -80, 100, 25),
    new Metric("HUMIDITY", "空气湿度", "%", 0, 100, 65),
    new Metric("SOIL_MOISTURE", "土壤水分", "%", 0, 100, 35),
    new Metric("SOIL_TEMPERATURE", "土壤温度", "℃", -40, 80, 22),
    new Metric("PH", "土壤酸碱度", "pH", 0, 14, 6.8),
    new Metric("LIGHT", "光照强度", "lux", 0, 200000, 26000),
    new Metric("RAINFALL", "累计降雨量", "mm", 0, 3000, 3),
    new Metric("WATER_LEVEL", "水位", "cm", 0, 1000, 18),
    new Metric("FLOW", "瞬时流量", "m³/h", 0, 10000, 12),
    new Metric("BATTERY", "电量", "%", 0, 100, 86)
  );
  public static final Map<String, String> TYPES = Map.of(
    "WEATHER",
    "气象站",
    "SOIL",
    "土壤监测",
    "WATER",
    "水情监测",
    "GATEWAY",
    "采集网关",
    "OTHER",
    "通用设备"
  );
  public static final Map<String, String> PROTOCOLS = Map.of(
    "MANUAL",
    "人工录入",
    "SIMULATED",
    "本地模拟",
    "HTTP_PUSH",
    "HTTP 数据上报"
  );

  private MetricCatalog() {}

  public static Metric get(String code) {
    return METRICS.stream()
      .filter(m -> m.code().equals(code))
      .findFirst()
      .orElseThrow(() -> new ApiException(400, "不支持的监测指标"));
  }

  public static void validate(String metric, BigDecimal value) {
    var spec = get(metric);
    if (
      value == null ||
      value.compareTo(BigDecimal.valueOf(spec.min())) < 0 ||
      value.compareTo(BigDecimal.valueOf(spec.max())) > 0
    ) {
      throw new ApiException(400, spec.name() + "超出允许范围 " + spec.min() + " ~ " + spec.max());
    }
  }
}
