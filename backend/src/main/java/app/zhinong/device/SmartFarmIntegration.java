package app.zhinong.device;

import app.zhinong.api.ApiException;
import app.zhinong.business.*;
import app.zhinong.security.Identity;
import app.zhinong.workspace.*;
import app.zhinong.device.SmartFarmProtocol.Binding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SmartFarmIntegration {
  public record Config(@NotBlank @Pattern(regexp="PUMP_MQTT|GATE_MQTT|LAN_DTU") String adapterType,
      @NotBlank @Size(max=100) String externalId, @NotNull @Size(max=240) String upstreamTopic,
      @NotNull @Size(max=240) String downstreamTopic, @Min(0) int revision,
      @NotEmpty @Size(max=32) List<@Valid Binding> bindings) {}
  public record Packet(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,80}") String messageId,
      @NotNull @PastOrPresent Instant measuredAt, @NotBlank @Size(max=100) String externalId,
      @NotNull @Size(max=240) String topic, @NotNull JsonNode payload) {}
  public record NativeReceipt(@NotBlank @Size(max=100) String externalId,
      @NotNull @Size(max=240) String topic, @NotNull JsonNode payload) {}

  private final Store store;
  private final AssetService assets;
  private final TelemetryService telemetry;
  private final DeviceCredentials credentials;
  private final DeviceCommandService commands;
  private final ObjectMapper json;

  public SmartFarmIntegration(Store store, AssetService assets, TelemetryService telemetry,
      DeviceCredentials credentials, DeviceCommandService commands, ObjectMapper json) {
    this.store=store; this.assets=assets; this.telemetry=telemetry; this.credentials=credentials;
    this.commands=commands; this.json=json;
  }

  public Map<String,Object> get(String id) {
    var asset = assets.detail(id);
    var result = new LinkedHashMap<String,Object>();
    var config = find(Identity.tenant(), id);
    result.put("configured", config != null);
    result.put("config", config);
    var options = new ArrayList<Map<String,Object>>();
    for (String adapter : List.of("PUMP_MQTT","GATE_MQTT","LAN_DTU")) {
      if (compatible(adapter, asset.get("deviceType").toString())) options.add(Map.of("code", adapter,
        "bindings", SmartFarmProtocol.defaults(adapter, asset.get("deviceType").toString())));
    }
    result.put("adapters", options);
    var events = store.db().queryForList("""
      SELECT message_id AS "messageId",adapter_type AS "adapterType",mapping_revision AS "mappingRevision",
        reading_count AS "readingCount",missing_json AS "missingJson",measured_at AS "measuredAt",received_at AS "receivedAt"
      FROM device_ingest_events WHERE tenant_id=? AND device_id=? ORDER BY received_at DESC LIMIT 20
      """, Identity.tenant(), id);
    for (var e : events) {
      e.put("missing", decode(e.remove("missingJson").toString(), new TypeReference<List<String>>() {}));
      e.put("measuredAt", DatabaseTime.utc(e.get("measuredAt")));
      e.put("receivedAt", DatabaseTime.utc(e.get("receivedAt")));
    }
    result.put("events", events);
    return result;
  }

  private static boolean compatible(String adapter, String type) {
    return switch (adapter) {
      case "PUMP_MQTT" -> type.equals("PUMP");
      case "GATE_MQTT" -> type.equals("GATE");
      case "LAN_DTU" -> Set.of("SOIL","WEATHER","WATER","OTHER").contains(type);
      default -> false;
    };
  }

  public Map<String,Object> save(String id, Config input) {
    Identity.require("ADMIN");
    store.lock("devices", id);
    var asset = assets.detail(id);
    if (!"HTTP_PUSH".equals(asset.get("protocol"))) throw new ApiException(409,"先将设备接入方式改为 HTTP 数据上报");
    if (!compatible(input.adapterType(), asset.get("deviceType").toString())) throw new ApiException(400,"接入协议与设备类型不匹配");
    if (!input.externalId().equals(input.externalId().strip())) throw new ApiException(400,"外部设备编号两端不能包含空格");
    if (input.adapterType().equals("LAN_DTU")) {
      if (!input.upstreamTopic().isEmpty() || !input.downstreamTopic().isEmpty()) throw new ApiException(400,"局域网采集不使用 MQTT 主题");
    } else {
      validateTopic(input.upstreamTopic(), false);
      validateTopic(input.downstreamTopic(), true);
      if (input.upstreamTopic().equals(input.downstreamTopic())) throw new ApiException(400,"上下行主题不能相同");
    }
    var available = assets.channels(Identity.tenant(), id).stream().map(c -> c.get("metric")).toList();
    var seen = new HashSet<String>();
    for (var binding : input.bindings()) {
      SmartFarmProtocol.validateBinding(input.adapterType(), binding);
      if (!seen.add(binding.metric()) || !available.contains(binding.metric()))
        throw new ApiException(400,"映射指标不能重复，且必须先加入设备监测指标");
    }
    var old = find(Identity.tenant(), id);
    if ((old == null ? 0 : old.revision()) != input.revision()) throw new ApiException(409,"接入配置已更新，请刷新后重试");
    // A second unique key owns the external identity. MySQL's generic upsert could update
    // another device on that conflict, so keep inserts and tenant/device-scoped updates separate.
    if (old == null) store.db().update("""
      INSERT INTO device_integrations(tenant_id,device_id,adapter_type,external_id,upstream_topic,downstream_topic,bindings_json,revision,updated_at)
      VALUES(?,?,?,?,?,?,?,?,?)
      """, Identity.tenant(), id, input.adapterType(), input.externalId(), input.upstreamTopic(), input.downstreamTopic(),
      encode(input.bindings()), input.revision()+1, OffsetDateTime.now(ZoneOffset.UTC));
    else store.db().update("""
      UPDATE device_integrations SET adapter_type=?,external_id=?,upstream_topic=?,downstream_topic=?,bindings_json=?,revision=?,updated_at=?
      WHERE tenant_id=? AND device_id=?
      """, input.adapterType(), input.externalId(), input.upstreamTopic(), input.downstreamTopic(),
      encode(input.bindings()), input.revision()+1, OffsetDateTime.now(ZoneOffset.UTC), Identity.tenant(), id);
    DeviceCommandService.cancelPending(store.db(), Identity.tenant(), id, "设备协议映射已更新，原指令失效");
    store.audit("SAVE_DEVICE_INTEGRATION", id);
    return get(id);
  }

  private static void validateTopic(String topic, boolean optional) {
    if (optional && topic.isEmpty()) return;
    if (topic.isBlank() || !topic.equals(topic.strip()) || topic.contains("+") || topic.contains("#") || topic.chars().anyMatch(Character::isISOControl))
      throw new ApiException(400,"请使用设备的完整主题，不能含通配符或控制字符");
  }

  private Config find(String tenant, String id) {
    var rows = store.db().queryForList("SELECT * FROM device_integrations WHERE tenant_id=? AND device_id=?", tenant, id);
    if (rows.isEmpty()) return null;
    var r=rows.getFirst();
    return new Config(r.get("ADAPTER_TYPE").toString(), r.get("EXTERNAL_ID").toString(), r.get("UPSTREAM_TOPIC").toString(),
      r.get("DOWNSTREAM_TOPIC").toString(), ((Number)r.get("REVISION")).intValue(),
      decode(r.get("BINDINGS_JSON").toString(), new TypeReference<List<SmartFarmProtocol.Binding>>() {}));
  }

  private Config bound(DeviceCredentials.Device device, String external, String topic) {
    Config config = find(device.tenant(), device.id());
    if (config == null) throw new ApiException(409,"设备尚未配置 smart-farm 协议映射");
    if (!config.externalId().equals(external) || !config.upstreamTopic().equals(topic))
      throw new ApiException(403,"设备编号或上行主题与接入凭据绑定的设备不一致");
    String type = store.db().queryForObject("SELECT device_type FROM asset_profiles WHERE tenant_id=? AND device_id=?",
      String.class, device.tenant(), device.id());
    if (!compatible(config.adapterType(),type)) throw new ApiException(409,"设备类型已变化，请重新配置协议映射");
    return config;
  }

  public Map<String,Object> ingest(String key, Packet input) {
    var device = credentials.authenticate(key);
    var config = bound(device,input.externalId(),input.topic());
    validatePayload(input.payload());
    if (input.measuredAt().isAfter(Instant.now()) || input.measuredAt().isBefore(Instant.parse("2000-01-01T00:00:00Z")))
      throw new ApiException(400,"采样时间无效");
    if (config.adapterType().equals("LAN_DTU") && input.payload().has("deviceAddr") &&
        !input.externalId().equals(input.payload().get("deviceAddr").asText())) throw new ApiException(403,"局域网报文设备编号不匹配");
    String hash = TelemetryService.hash(input.measuredAt()+"|"+input.externalId()+"|"+input.topic()+"|"+canonical(input.payload()));
    var prior = store.db().queryForList("SELECT payload_hash,reading_count FROM device_ingest_events WHERE tenant_id=? AND device_id=? AND message_id=?",
      device.tenant(),device.id(),input.messageId());
    if (!prior.isEmpty()) {
      if (!hash.equals(prior.getFirst().get("PAYLOAD_HASH"))) throw new ApiException(409,"消息编号已使用，重试内容必须相同");
      return Map.of("accepted",true,"duplicate",true,"count",prior.getFirst().get("READING_COUNT"));
    }
    var decoded=SmartFarmProtocol.decode(config.adapterType(),config.bindings(),input.payload(),input.measuredAt());
    var batches = new TreeMap<Instant,List<WorkspaceInputs.Reading>>();
    for (var sample : decoded.samples()) batches.computeIfAbsent(sample.time(), ignored -> new ArrayList<>()).add(sample.reading());
    int index=0;
    for (var batch : batches.entrySet()) telemetry.ingest(key,new WorkspaceInputs.Ingest(
      "native:"+TelemetryService.hash(input.messageId())+":"+(index++),batch.getKey(),batch.getValue()));
    store.db().update("""
      INSERT INTO device_ingest_events(tenant_id,device_id,message_id,adapter_type,payload_hash,mapping_revision,reading_count,missing_json,measured_at,received_at)
      VALUES(?,?,?,?,?,?,?,?,?,?)
      """,device.tenant(),device.id(),input.messageId(),config.adapterType(),hash,config.revision(),decoded.samples().size(),
      encode(decoded.missing()),input.measuredAt().atOffset(ZoneOffset.UTC),OffsetDateTime.now(ZoneOffset.UTC));
    return Map.of("accepted",true,"duplicate",false,"count",decoded.samples().size(),"missing",decoded.missing());
  }

  @SuppressWarnings("unchecked")
  public Map<String,Object> poll(String key) {
    var device=credentials.authenticate(key);
    var config=find(device.tenant(),device.id());
    if(config==null || config.downstreamTopic().isBlank()) throw new ApiException(409,"设备尚未配置控制下行主题");
    bound(device,config.externalId(),config.upstreamTopic());
    var result=commands.poll(key);
    var out=new ArrayList<Map<String,Object>>();
    for(var command:(List<Map<String,Object>>)result.get("commands")) {
      var row=new LinkedHashMap<>(command);
      row.put("topic",config.downstreamTopic()); row.put("externalId",config.externalId());
      row.put("payload",SmartFarmProtocol.command(command,config.adapterType())); out.add(row);
    }
    return Map.of("commands",out);
  }

  public Map<String,Object> receipt(String key,String id,NativeReceipt input) {
    var device=credentials.authenticate(key);
    var config=bound(device,input.externalId(),input.topic());
    validatePayload(input.payload());
    JsonNode rw=input.payload().path("rw_prot");
    if (!rw.isObject() || !id.equals(rw.path("id").asText()) || !"up".equals(rw.path("dir").asText()))
      throw new ApiException(400,"回执编号或方向不匹配");
    var rows=store.db().queryForList("SELECT id AS \"id\",action AS \"action\",command_value AS \"value\" FROM device_commands WHERE tenant_id=? AND device_id=? AND id=?",
      device.tenant(),device.id(),id);
    if(rows.isEmpty()) throw ApiException.missing();
    JsonNode expected=json.valueToTree(SmartFarmProtocol.command(rows.getFirst(),config.adapterType()));
    JsonNode wanted=expected.at("/rw_prot/w_data/0");
    JsonNode actual=rw.path("w_data");
    if(!actual.isArray() || actual.size()!=1 || !wanted.path("name").equals(actual.get(0).path("name")))
      throw new ApiException(400,"回执缺少对应的写入确认");
    try {
      if(new java.math.BigDecimal(wanted.path("value").asText()).compareTo(new java.math.BigDecimal(actual.get(0).path("value").asText()))!=0)
        throw new NumberFormatException();
    } catch(NumberFormatException e) { throw new ApiException(400,"回执确认值与指令不一致"); }
    return commands.receipt(key,id,new DeviceCommandService.Receipt("SUCCEEDED","设备已确认写入；实际开度和运行状态以监测反馈为准"));
  }

  private void validatePayload(JsonNode payload) {
    if (!payload.isObject() || payload.toString().length()>32768) throw new ApiException(400,"设备报文必须为 JSON 对象且不超过 32K 字符");
  }
  private JsonNode canonical(JsonNode value) {
    if(value.isObject()) {
      var sorted=json.createObjectNode(); var names=new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
      names.forEach(n -> sorted.set(n,canonical(value.get(n)))); return sorted;
    }
    if(value.isArray()) { var array=json.createArrayNode(); value.forEach(v -> array.add(canonical(v))); return array; }
    return value;
  }
  private String encode(Object value) {
    try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Cannot encode integration data",e); }
  }
  private <T> T decode(String value,TypeReference<T> type) {
    try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException("Invalid saved integration data",e); }
  }
}
