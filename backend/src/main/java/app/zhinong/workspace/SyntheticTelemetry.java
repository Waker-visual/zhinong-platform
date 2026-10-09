package app.zhinong.workspace;

import java.math.*;
import java.time.*;
import java.util.*;

/** Invented demonstration model, not a weather forecast or a calibrated crop model. */
public final class SyntheticTelemetry {
  public static final String VERSION = "demo-stream-v1";
  private SyntheticTelemetry() {}

  public static Map<String, BigDecimal> sample(String type, String seed, Instant at,
      Map<String, BigDecimal> previous, long elapsedSeconds) {
    var local = at.atZone(ZoneId.of("Asia/Shanghai"));
    double hour = local.getHour() + local.getMinute() / 60.0 + local.getSecond() / 3600.0;
    double day = local.toLocalDate().toEpochDay();
    double offset = Math.floorMod(seed.hashCode(), 11) / 10.0 - 0.5;
    double wave = Math.sin((hour - 9) * Math.PI / 12);
    double wet = Math.sin(day * Math.PI / 3.5);
    double hours = Math.max(0, elapsedSeconds) / 3600.0;
    double running = value(previous, "PUMP_RUNNING", 0);
    double standby = value(previous, "STANDBY_RUNNING", 0);
    double frequency = value(previous, "PUMP_FREQUENCY", running > 0 ? 35 : 0);
    double flow = (running > 0 ? frequency / 35.0 * 20 : 0) + (standby > 0 ? 16 : 0);
    double current = (running > 0 ? frequency / 35.0 * 12 : 0) + (standby > 0 ? 10 : 0);
    double rainRate = Math.floorMod((long) day, 7) == 0 && hour >= 6 && hour < 9 ? 1.2 : 0;
    var result = new LinkedHashMap<String, BigDecimal>();
    for (var spec : MetricCatalog.METRICS) {
      String metric = spec.code();
      int layer = metric.endsWith("_3") ? 3 : metric.endsWith("_2") ? 2 : 1;
      double moisture = 35 + layer * 2 + wet * (4.0 / layer) - wave * (1.5 / layer) + offset;
      double number = switch (metric) {
        case "TEMPERATURE" -> 23 + wave * 7 + offset;
        case "HUMIDITY" -> 68 - wave * 16 + wet * 3;
        case "LIGHT" -> Math.max(0, Math.sin((hour - 6) * Math.PI / 12)) * 52000;
        case "SOIL_MOISTURE", "SOIL_MOISTURE_2", "SOIL_MOISTURE_3" -> moisture;
        case "SOIL_TEMPERATURE", "SOIL_TEMPERATURE_2", "SOIL_TEMPERATURE_3" ->
          22 + Math.sin((hour - 9 - layer * 2) * Math.PI / 12) * (3.0 / layer) + offset;
        case "SOIL_EC", "SOIL_EC_2", "SOIL_EC_3" -> 680 + layer * 20 - (moisture - 35) * 8;
        case "PH" -> 6.7 + wet * 0.08 + offset * 0.02;
        case "WIND_SPEED" -> 2.2 + Math.sin(hour * Math.PI / 6) * 1.2;
        case "WIND_DIRECTION" -> 135 + Math.sin(hour * Math.PI / 12) * 45;
        case "AIR_PRESSURE" -> 1012 - wet * 4 + Math.cos(hour * Math.PI / 12) * 2;
        case "RAINFALL" -> value(previous, metric, 0) + rainRate * hours;
        case "WATER_LEVEL" -> 22 + wet * 2 + Math.sin(hour * Math.PI / 12);
        case "FLOW" -> type.equals("PUMP") ? flow : 10 + Math.max(0, wave) * 4;
        case "CURRENT" -> type.equals("PUMP") ? current : spec.normal();
        case "ENERGY" -> value(previous, metric, 120) + Math.sqrt(3) * 380 * current * 0.85 / 1000 * hours;
        case "WATER_TOTAL" -> value(previous, metric, 340) + flow * hours;
        case "GATE_OPENING", "PUMP_RUNNING", "STANDBY_RUNNING", "PUMP_FREQUENCY",
            "REMOTE_ENABLED", "FAULT", "EMERGENCY_STOP", "PARAMETER_WRITE_ENABLED" -> value(previous, metric, spec.normal());
        case "PEST_COUNT" -> Math.round(6 + Math.max(0, -wave) * 10 + wet * 2);
        case "CAMERA_ONLINE" -> 1; // Only the virtual device's status; no video is invented.
        case "SPEED" -> hour >= 8 && hour < 17 ? 4.5 : 0;
        case "BATTERY" -> 88 - Math.max(0, wave) * 8;
        default -> spec.normal();
      };
      number = Math.max(spec.min(), Math.min(spec.max(), number));
      if (MetricCatalog.discrete(metric)) number = Math.round(number);
      result.put(metric, BigDecimal.valueOf(number).setScale(3, RoundingMode.HALF_UP));
    }
    return result;
  }

  private static double value(Map<String, BigDecimal> previous, String metric, double fallback) {
    return previous.containsKey(metric) ? previous.get(metric).doubleValue() : fallback;
  }
}
