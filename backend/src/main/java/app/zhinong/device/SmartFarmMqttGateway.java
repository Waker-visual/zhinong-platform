package app.zhinong.device;

import app.zhinong.api.ApiException;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Optional bridge. Disabled by default; credentials and routing come from a private local file. */
@Component
@ConditionalOnProperty(name="farm.mqtt.enabled",havingValue="true")
public class SmartFarmMqttGateway {
  public record Route(String externalId,String upstreamTopic,String downstreamTopic,String deviceKeyEnv,boolean commandsEnabled) {}
  public record Broker(String url,String clientId,String usernameEnv,String passwordEnv,String timeZone,List<Route> devices) {}
  public record Configuration(List<Broker> brokers) {}
  public record Pending(String deviceKeyEnv,SmartFarmIntegration.Packet packet) {}
  private record Link(MqttClient client,MqttConnectOptions options,Broker config) {}
  private static final Logger log=LoggerFactory.getLogger(SmartFarmMqttGateway.class);
  private final ObjectMapper json;
  private final SmartFarmIntegration integrations;
  private final Path configPath, inbox;
  private final List<Link> links=new ArrayList<>();
  private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r -> {
    var thread=new Thread(r,"smart-farm-mqtt-bridge"); thread.setDaemon(true); return thread;
  });

  public SmartFarmMqttGateway(ObjectMapper json,SmartFarmIntegration integrations,
      @Value("${farm.mqtt.config:}") String config,
      @Value("${farm.mqtt.inbox:.cache/mqtt-inbox}") String inbox) {
    this.json=json;this.integrations=integrations;this.configPath=Path.of(config);this.inbox=Path.of(inbox).toAbsolutePath().normalize();
    if(config.isBlank()) throw new IllegalArgumentException("启用 MQTT 网关需指定 farm.mqtt.config 私有配置文件");
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() throws Exception {
    Configuration config=json.readValue(Files.readString(configPath),Configuration.class);
    if(config.brokers()==null || config.brokers().isEmpty() || config.brokers().size()>16) throw new IllegalArgumentException("MQTT broker 配置数量无效");
    Files.createDirectories(inbox);
    var identities=new HashSet<String>();
    for(Broker broker:config.brokers()) {
      if(broker.url()==null || broker.clientId()==null || broker.clientId().isBlank() || broker.clientId().length()>100
          || !identities.add(broker.url()+"|"+broker.clientId())) throw new IllegalArgumentException("MQTT 客户端编号无效或重复");
      ZoneId.of(broker.timeZone());
      if(broker.devices()==null || broker.devices().isEmpty() || broker.devices().size()>200) throw new IllegalArgumentException("MQTT 设备配置数量无效");
      var topics=new HashSet<String>();
      for(Route route:broker.devices()) {
        if(route.externalId()==null || route.externalId().isBlank() || route.upstreamTopic()==null || route.upstreamTopic().isBlank()
            || route.upstreamTopic().contains("#") || route.upstreamTopic().contains("+") || !topics.add(route.upstreamTopic()))
          throw new IllegalArgumentException("MQTT 设备必须使用独占的完整上行主题");
        if(route.commandsEnabled() && (route.downstreamTopic()==null || route.downstreamTopic().isBlank()
            || route.downstreamTopic().contains("#") || route.downstreamTopic().contains("+") || route.downstreamTopic().equals(route.upstreamTopic())))
          throw new IllegalArgumentException("允许控制的设备必须在私有网关配置中明确授权下行主题");
        secret(route.deviceKeyEnv());
      }
      var options=new MqttConnectOptions();
      options.setCleanSession(false); options.setConnectionTimeout(5);options.setKeepAliveInterval(30);
      options.setUserName(secret(broker.usernameEnv())); options.setPassword(secret(broker.passwordEnv()).toCharArray());
      var client=new MqttClient(broker.url(),broker.clientId(),new MemoryPersistence());
      client.setTimeToWait(5000);
      client.setCallback(new MqttCallback() {
        public void connectionLost(Throwable cause) { log.warn("MQTT 连接中断，等待重连；设备状态仍以采样时间为准"); }
        public void deliveryComplete(IMqttDeliveryToken token) {}
        public void messageArrived(String topic,MqttMessage message) throws Exception {
          // Retained packets without a proven current sample must not make a device appear online.
          if(message.isRetained()) return;
          Route route=broker.devices().stream().filter(r -> r.upstreamTopic().equals(topic)).findFirst().orElse(null);
          if(route==null || message.getPayload().length>32768) return;
          JsonNode payload;
          try { payload=json.readTree(message.getPayload()); }
          catch(Exception e) { log.warn("MQTT 报文不是有效 JSON，已忽略");return; }
          if(payload==null || !payload.isObject()) return;
          Instant received=Instant.now(), sampled;
          try { sampled=sampleTime(payload,received,ZoneId.of(broker.timeZone())); }
          catch(ApiException e) { log.warn("MQTT 设备时间无效，已忽略该报文");return; }
          String id="mqtt-"+UUID.randomUUID();
          // Durable before acknowledging MQTT. HTTP/database failures retry this exact envelope.
          var pending=new Pending(route.deviceKeyEnv(),new SmartFarmIntegration.Packet(id,sampled,route.externalId(),topic,payload));
          Path temp=inbox.resolve(id+".tmp"), ready=inbox.resolve(id+".json");
          Files.writeString(temp,json.writeValueAsString(pending),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
          try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)) { channel.force(true); }
          Files.move(temp,ready,StandardCopyOption.ATOMIC_MOVE);
        }
      });
      links.add(new Link(client,options,broker));
    }
    worker.scheduleWithFixedDelay(this::cycle,0,3,TimeUnit.SECONDS);
  }

  static Instant sampleTime(JsonNode payload,Instant received,ZoneId zone) {
    JsonNode stamp=payload.path("params").get("sys_time");
    if(stamp==null || stamp.isNull() || stamp.asText().isBlank()) return received;
    String text=stamp.asText();
    try {
      Instant result;
      if(text.matches("\\d{13}")) result=Instant.ofEpochMilli(Long.parseLong(text));
      else if(text.matches("\\d{10}")) result=Instant.ofEpochSecond(Long.parseLong(text));
      else if(text.contains("T")) result=Instant.parse(text);
      else result=LocalDateTime.parse(text,DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(zone).toInstant();
      if(result.isAfter(received) || result.isBefore(Instant.parse("2000-01-01T00:00:00Z"))) throw new IllegalArgumentException();
      return result;
    } catch(RuntimeException e) { throw new ApiException(400,"设备 sys_time 无效"); }
  }

  private void cycle() {
    try {
      for(Link link:links) {
        if(!link.client().isConnected()) {
          try {
            link.client().connect(link.options());
            for(Route route:link.config().devices()) link.client().subscribe(route.upstreamTopic(),1);
          } catch(MqttException e) {
            try { if(link.client().isConnected()) link.client().disconnectForcibly(1000,1000,false); } catch(MqttException ignored) {}
            log.warn("MQTT 暂未连接，原因码 {}",e.getReasonCode());continue;
          }
        }
        for(Route route:link.config().devices()) if(route.commandsEnabled()) dispatch(link,route);
      }
      try(var files=Files.list(inbox)) {
        for(Path file:files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().limit(100).toList()) drain(file);
      }
    } catch(Exception e) { log.warn("MQTT 网关本轮处理未完成，已持久化报文将保留重试"); }
  }

  private void drain(Path file) throws Exception {
    Pending pending=json.readValue(Files.readString(file),Pending.class);
    try {
      JsonNode rw=pending.packet().payload().path("rw_prot");
      if(rw.has("w_data")) {
        integrations.receipt(secret(pending.deviceKeyEnv()),rw.path("id").asText(),
          new SmartFarmIntegration.NativeReceipt(pending.packet().externalId(),pending.packet().topic(),pending.packet().payload()));
        if(rw.path("r_data").isArray() && !rw.path("r_data").isEmpty()) integrations.ingest(secret(pending.deviceKeyEnv()),pending.packet());
      } else integrations.ingest(secret(pending.deviceKeyEnv()),pending.packet());
      Files.delete(file);
    } catch(ApiException e) {
      // Keep rejected envelopes for local diagnosis; credentials never appear in these files.
      Files.move(file,file.resolveSibling(file.getFileName()+".rejected"));
      log.warn("MQTT 报文校验未通过（状态 {}），已保留本地拒收记录",e.status());
    }
  }

  @SuppressWarnings("unchecked")
  private void dispatch(Link link,Route route) {
    try {
      var result=integrations.poll(secret(route.deviceKeyEnv()));
      for(var command:(List<Map<String,Object>>)result.get("commands")) {
        if(!Instant.parse(command.get("expiresAt").toString()).isAfter(Instant.now())) continue;
        if(!allows(route,command)) continue;
        String id=command.get("id").toString();
        if(!id.matches("[a-f0-9-]{36}")) continue;
        Path sent=inbox.resolve("sent-"+id+".marker");
        // Mark before publishing: after an uncertain transport failure never blindly replay a physical action.
        try { Files.writeString(sent,Instant.now().toString(),StandardOpenOption.CREATE_NEW); }
        catch(FileAlreadyExistsException e) { continue; }
        link.client().publish(command.get("topic").toString(),json.writeValueAsBytes(command.get("payload")),1,false);
      }
    } catch(Exception e) { log.warn("MQTT 指令尚未完成，等待有效回执或平台超时；未自动标记成功"); }
  }

  static boolean allows(Route route,Map<String,Object> command) {
    // A tenant may edit its integration, but cannot use shared broker credentials to publish to another device.
    return route.commandsEnabled() && route.externalId().equals(command.get("externalId"))
      && route.downstreamTopic()!=null && route.downstreamTopic().equals(command.get("topic"));
  }

  private static String secret(String name) {
    if(name==null || !name.matches("[A-Z][A-Z0-9_]{2,100}")) throw new IllegalArgumentException("凭据需引用环境变量名称");
    String value=System.getenv(name);
    if(value==null || value.isBlank()) throw new IllegalArgumentException("MQTT 所需的环境变量未设置");
    return value;
  }

  @PreDestroy public void stop() {
    worker.shutdownNow();
    for(Link link:links) try { link.client().disconnectForcibly(1000,1000,false);link.client().close(); } catch(MqttException ignored) {}
  }
}
