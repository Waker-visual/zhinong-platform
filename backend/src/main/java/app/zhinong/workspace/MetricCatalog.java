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
    new Metric("SOIL_MOISTURE_2", "第二层土壤水分", "%", 0, 100, 35),
    new Metric("SOIL_TEMPERATURE_2", "第二层土壤温度", "℃", -40, 80, 22),
    new Metric("SOIL_MOISTURE_3", "第三层土壤水分", "%", 0, 100, 35),
    new Metric("SOIL_TEMPERATURE_3", "第三层土壤温度", "℃", -40, 80, 22),
    new Metric("PH", "土壤酸碱度", "pH", 0, 14, 6.8),
    new Metric("LIGHT", "光照强度", "lux", 0, 200000, 26000),
    new Metric("RAINFALL", "累计降雨量", "mm", 0, 3000, 3),
    new Metric("WATER_LEVEL", "水位", "cm", 0, 1000, 18),
    new Metric("FLOW", "瞬时流量", "m³/h", 0, 10000, 12),
    new Metric("BATTERY", "电量", "%", 0, 100, 86),
    new Metric("WIND_SPEED", "风速", "m/s", 0, 80, 2.5),
    new Metric("WIND_DIRECTION", "风向", "°", 0, 360, 135),
    new Metric("AIR_PRESSURE", "大气压", "hPa", 300, 1200, 1013),
    new Metric("SOIL_EC", "土壤电导率", "µS/cm", 0, 100000, 620),
    new Metric("SOIL_EC_2", "第二层土壤电导率", "µS/cm", 0, 100000, 620),
    new Metric("SOIL_EC_3", "第三层土壤电导率", "µS/cm", 0, 100000, 620),
    new Metric("PEST_COUNT", "虫情计数", "只", 0, 1000000, 8),
    new Metric("GATE_OPENING", "闸门实际开度", "%", 0, 100, 0),
    new Metric("PUMP_RUNNING", "主泵运行反馈", "", 0, 1, 0),
    new Metric("STANDBY_RUNNING", "备用泵运行反馈", "", 0, 1, 0),
    new Metric("PUMP_FREQUENCY", "主泵设定频率", "Hz", 0, 60, 0),
    new Metric("EMERGENCY_STOP", "急停反馈", "", 0, 1, 0),
    new Metric("PARAMETER_WRITE_ENABLED", "远程参数设置许可", "", 0, 1, 1),
    new Metric("VOLTAGE", "电压", "V", 0, 1000, 380),
    new Metric("CURRENT", "电流", "A", 0, 1000, 0),
    new Metric("ENERGY", "累计用电量", "kWh", 0, 100000000, 120),
    new Metric("WATER_TOTAL", "累计用水量", "m³", 0, 100000000, 340),
    new Metric("REMOTE_ENABLED", "远程模式反馈", "", 0, 1, 1),
    new Metric("FAULT", "设备故障反馈", "", 0, 1, 0),
    new Metric("CAMERA_ONLINE", "视频设备在线反馈", "", 0, 1, 0),
    new Metric("SPEED", "作业速度", "km/h", 0, 200, 0)
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
    "GATE",
    "灌溉闸门",
    "PUMP",
    "泵房控制器",
    "PEST",
    "虫情监测",
    "CAMERA",
    "视频监测",
    "MACHINERY",
    "农机终端",
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

  public static final Map<String, List<String>> PRESETS = Map.of(
    "WEATHER", List.of("TEMPERATURE", "HUMIDITY", "WIND_SPEED", "WIND_DIRECTION", "AIR_PRESSURE", "RAINFALL", "LIGHT"),
    "SOIL", List.of("SOIL_MOISTURE", "SOIL_TEMPERATURE", "SOIL_EC", "SOIL_MOISTURE_2", "SOIL_TEMPERATURE_2", "SOIL_EC_2", "SOIL_MOISTURE_3", "SOIL_TEMPERATURE_3", "SOIL_EC_3", "PH"),
    "WATER", List.of("WATER_LEVEL", "FLOW"),
    "PEST", List.of("PEST_COUNT", "TEMPERATURE", "HUMIDITY"),
    "GATE", List.of("GATE_OPENING", "WATER_LEVEL", "REMOTE_ENABLED", "FAULT"),
    "PUMP", List.of("PUMP_RUNNING", "STANDBY_RUNNING", "PUMP_FREQUENCY", "WATER_LEVEL", "VOLTAGE", "CURRENT", "FLOW", "ENERGY", "WATER_TOTAL", "REMOTE_ENABLED", "FAULT", "EMERGENCY_STOP", "PARAMETER_WRITE_ENABLED"),
    "CAMERA", List.of("CAMERA_ONLINE"),
    "MACHINERY", List.of("SPEED", "BATTERY"),
    "GATEWAY", List.of("BATTERY"),
    "OTHER", List.of("TEMPERATURE")
  );

  public static boolean discrete(String metric) {
    return Set.of("PUMP_RUNNING", "STANDBY_RUNNING", "REMOTE_ENABLED", "FAULT", "EMERGENCY_STOP", "PARAMETER_WRITE_ENABLED", "CAMERA_ONLINE", "PEST_COUNT").contains(metric);
  }

  /** Units are explicit; a numeric magnitude never implies a unit. */
  public static BigDecimal normalize(String metric, BigDecimal value, String unit) {
    if (value == null) throw new ApiException(400, "缺测值不能当作零上报");
    String expected = get(metric).unit();
    if (unit == null || unit.equals(expected)) return value;
    if (metric.equals("AIR_PRESSURE") && unit.equals("kPa")) return value.multiply(BigDecimal.TEN);
    if (metric.equals("WATER_LEVEL") && unit.equals("m")) return value.multiply(BigDecimal.valueOf(100));
    if (metric.equals("WATER_LEVEL") && unit.equals("mm")) return value.movePointLeft(1);
    if (metric.equals("FLOW") && unit.equals("L/min")) return value.multiply(new BigDecimal("0.06"));
    if (metric.startsWith("SOIL_EC") && unit.equals("mS/cm")) return value.multiply(BigDecimal.valueOf(1000));
    if (metric.startsWith("SOIL_EC") && unit.equals("μS/cm")) return value;
    if (expected.equals("%") && unit.equals("%RH") && (metric.equals("HUMIDITY") || metric.startsWith("SOIL_MOISTURE"))) return value;
    if (expected.equals("℃") && unit.equals("°C")) return value;
    throw new ApiException(400, get(metric).name() + "的单位不受支持，标准单位为 " + expected);
  }

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
    if (discrete(metric) && value.stripTrailingZeros().scale() > 0) {
      throw new ApiException(400, spec.name() + "必须为整数");
    }
  }
}
