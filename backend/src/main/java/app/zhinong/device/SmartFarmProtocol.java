package app.zhinong.device;

import app.zhinong.api.ApiException;
import app.zhinong.workspace.MetricCatalog;
import app.zhinong.workspace.WorkspaceInputs;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Wire shapes verified against smart-farm's pump, gate and LAN collectors. */
public final class SmartFarmProtocol {
  public record Binding(@NotBlank String metric, @NotBlank @Size(max=80) String field,
                        @NotNull @Size(max=20) String unit) {}
  public record Sample(Instant time, WorkspaceInputs.Reading reading) {}
  public record Decoded(List<Sample> samples, List<String> missing) {}

  public static List<Binding> defaults(String adapter, String type) {
    return switch (adapter) {
      case "PUMP_MQTT" -> List.of(
        b("PUMP_RUNNING", "mainPumpRunning", ""), b("STANDBY_RUNNING", "standbyPumpRunning", ""),
        b("PUMP_FREQUENCY", "mainPumpFreqSet", "Hz"), b("WATER_LEVEL", "waterLevelFeedback", "cm"),
        b("VOLTAGE", "mainPumpVoltage", "V"), b("CURRENT", "mainPumpCurrent", "A"),
        b("REMOTE_ENABLED", "remoteFlag", ""), b("EMERGENCY_STOP", "emergencyStop", ""),
        b("PARAMETER_WRITE_ENABLED", "remoteParamSet", ""));
      // The old gate protocol proves opening; it does not prove remote/fault field names.
      case "GATE_MQTT" -> List.of(b("GATE_OPENING", "opening", "%"));
      case "LAN_DTU" -> type.equals("SOIL") ? List.of(
        b("SOIL_TEMPERATURE", "1.temValue", "℃"), b("SOIL_MOISTURE", "1.humValue", "%"),
        b("SOIL_EC", "2.humValue", "µS/cm"),
        b("SOIL_TEMPERATURE_2", "3.temValue", "℃"), b("SOIL_MOISTURE_2", "3.humValue", "%"),
        b("SOIL_EC_2", "4.humValue", "µS/cm"),
        b("SOIL_TEMPERATURE_3", "5.temValue", "℃"), b("SOIL_MOISTURE_3", "5.humValue", "%"),
        b("SOIL_EC_3", "6.humValue", "µS/cm"), b("PH", "7.humValue", "pH")) : List.of();
      default -> throw new ApiException(400, "不支持的 smart-farm 接入类型");
    };
  }
  private static Binding b(String metric, String field, String unit) { return new Binding(metric,field,unit); }

  public static void validateBinding(String adapter, Binding binding) {
    MetricCatalog.normalize(binding.metric(), BigDecimal.ONE, binding.unit());
    if (adapter.equals("LAN_DTU")) {
      if (!binding.field().matches("[0-9]{1,6}\\.(temValue|humValue|tem|hum|float_value|signed_val|unsigned_val)"))
        throw new ApiException(400, "局域网字段格式为 节点编号.数值字段，例如 1.temValue");
    } else {
      if (!binding.field().matches("[A-Za-z][A-Za-z0-9_]{0,79}")) throw new ApiException(400, "MQTT 字段名无效");
      if (Set.of("setOpening", "mainPumpManual", "standbyPumpManual", "remoteAutoRun").contains(binding.field()))
        throw new ApiException(400, "控制设置字段不能作为实际运行或远程模式反馈");
      if (binding.field().equals("remoteParamSet") && !binding.metric().equals("PARAMETER_WRITE_ENABLED"))
        throw new ApiException(400,"参数设置许可不能作为远程运行模式反馈");
    }
  }

  public static Decoded decode(String adapter, List<Binding> bindings, JsonNode payload, Instant time) {
    var values = new HashMap<String, JsonNode>();
    var times = new HashMap<String, Instant>();
    if (adapter.equals("LAN_DTU")) {
      JsonNode nodes = payload.path("data");
      if (!nodes.isArray() || nodes.size() > 128) throw new ApiException(400, "局域网报文需要 data 节点数组，最多 128 个节点");
      var ids = new HashSet<String>();
      for (JsonNode node : nodes) {
        String id = node.path("nodeId").asText("");
        if (!id.matches("[0-9]{1,6}") || !ids.add(id)) throw new ApiException(400, "节点编号无效或重复");
        Instant sampled = time;
        JsonNode stamp = node.get("timeStamp");
        if (stamp != null && !stamp.isNull()) {
          if (!stamp.isIntegralNumber() || !stamp.canConvertToLong()) throw new ApiException(400, "节点 timeStamp 必须为毫秒时间戳");
          try { sampled = Instant.ofEpochMilli(stamp.longValue()); }
          catch (RuntimeException e) { throw new ApiException(400, "节点采样时间无效"); }
        }
        if (sampled.isAfter(Instant.now()) || sampled.isBefore(Instant.parse("2000-01-01T00:00:00Z")))
          throw new ApiException(400, "节点采样时间无效");
        for (var binding : bindings) if (binding.field().startsWith(id + ".")) {
          values.put(binding.field(), node.get(binding.field().substring(id.length() + 1)));
          times.put(binding.field(), sampled);
        }
      }
    } else {
      JsonNode body = payload.has("params") ? payload.path("params") : payload.path("rw_prot");
      if (!body.isObject() || !"up".equals(body.path("dir").asText("up"))) throw new ApiException(400, "仅接受设备上行报文");
      JsonNode fields = body.path("r_data");
      if (!fields.isArray() || fields.size() > 128) throw new ApiException(400, "上行报文需要 r_data；写指令回显不能作为监测数据");
      for (JsonNode item : fields) {
        String name = item.path("name").asText("");
        if (name.isBlank() || values.containsKey(name)) throw new ApiException(400, "上报字段缺少名称或重复");
        values.put(name, item.get("value"));
      }
    }
    var samples = new ArrayList<Sample>();
    var missing = new ArrayList<String>();
    for (Binding binding : bindings) {
      JsonNode raw = values.get(binding.field());
      if (raw == null || raw.isNull() || raw.isTextual() && Set.of("", "--", "null").contains(raw.asText().strip())) {
        missing.add(binding.metric()); continue;
      }
      if (!raw.isNumber() && !raw.isTextual()) throw new ApiException(400, "字段 " + binding.field() + " 不是有效数值");
      BigDecimal number;
      String text = raw.asText().strip();
      if (text.length() > 40) throw new ApiException(400, "字段数值过长");
      try { number = new BigDecimal(text); }
      catch (NumberFormatException e) { throw new ApiException(400, "字段 " + binding.field() + " 不是有效数值"); }
      if (Math.abs((long)number.scale()) > 12 || number.precision() > 20) throw new ApiException(400,"字段数值精度超出支持范围");
      number = MetricCatalog.normalize(binding.metric(), number, binding.unit());
      MetricCatalog.validate(binding.metric(), number);
      samples.add(new Sample(times.getOrDefault(binding.field(), time), new WorkspaceInputs.Reading(binding.metric(), number)));
    }
    if (samples.isEmpty()) throw new ApiException(400, "未找到已映射的有效读数，未生成零值或更新设备在线状态");
    return new Decoded(samples, missing);
  }

  public static Map<String, Object> command(Map<String, Object> command, String adapter) {
    String action = command.get("action").toString();
    String field;
    Object value;
    switch (action) {
      case "SET_OPENING" -> { field = "setOpening"; value = command.get("value"); }
      case "PUMP_START" -> { field = "mainPumpManual"; value = 1; }
      case "PUMP_STOP" -> { field = "mainPumpManual"; value = 0; }
      case "SET_FREQUENCY" -> { field = "mainPumpFreqSet"; value = command.get("value"); }
      case "EMERGENCY_STOP" -> { field = "emergencyStop"; value = 1; }
      default -> throw new ApiException(400, "无法转换设备指令");
    }
    if (adapter.equals("LAN_DTU") || action.equals("SET_OPENING") != adapter.equals("GATE_MQTT"))
      throw new ApiException(409, "指令与已绑定的设备协议不匹配");
    return Map.of("rw_prot", Map.of("Ver", "1.0.1", "dir", "down", "id", command.get("id"),
      "w_data", List.of(Map.of("name", field, "value", value.toString()))));
  }
}
