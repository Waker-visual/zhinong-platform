package app.zhinong.weather;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** Fixed trusted origin, bounded complete response, and explicit standard units. */
@Component
final class OpenMeteoForecastGateway implements WeatherForecastGateway {
  static final int MAX_BYTES=128*1024;
  static final Duration RESPONSE_TIMEOUT=Duration.ofSeconds(6);
  static final String ORIGIN="https://api.open-meteo.com/v1/forecast";
  static final String DAILY="weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,et0_fao_evapotranspiration";
  record Remote(int status,byte[] body) {}
  @FunctionalInterface interface Transport { Remote get(URI uri) throws IOException,InterruptedException; }
  private final ObjectMapper json;
  private final Transport transport;

  @Autowired OpenMeteoForecastGateway(ObjectMapper json) {
    this(json,http(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),RESPONSE_TIMEOUT));
  }
  OpenMeteoForecastGateway(ObjectMapper json,Transport transport) { this.json=json;this.transport=transport; }

  @Override public List<Day> fetch(double latitude,double longitude,ZoneId zone,LocalDate firstDate) {
    if(!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude< -80 || latitude>80 || longitude< -180 || longitude>180)
      throw new Unavailable("INVALID_LOCATION");
    URI uri=URI.create(ORIGIN+"?latitude="+Double.toString(latitude)+"&longitude="+Double.toString(longitude)+
      "&timezone="+URLEncoder.encode(zone.getId(),StandardCharsets.UTF_8)+"&forecast_days=7&temperature_unit=celsius&wind_speed_unit=ms&precipitation_unit=mm&daily="+DAILY);
    try {
      var remote=transport.get(uri);
      if(remote.status()!=200)throw new Unavailable("UPSTREAM_HTTP_ERROR");
      if(remote.body()==null || remote.body().length>MAX_BYTES)throw new Unavailable("INVALID_RESPONSE");
      return parse(json.readTree(remote.body()),zone,firstDate);
    } catch(HttpTimeoutException timeout) { throw new Unavailable("TIMEOUT"); }
    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new Unavailable("INTERRUPTED"); }
    catch(IOException failed) { throw new Unavailable("NETWORK_ERROR"); }
    catch(Unavailable failure) { throw failure; }
    catch(RuntimeException invalid) { throw new Unavailable("INVALID_RESPONSE"); }
  }

  private List<Day> parse(JsonNode root,ZoneId requestedZone,LocalDate firstDate) {
    if(!root.isObject() || root.path("error").asBoolean(false))throw new Unavailable("INVALID_RESPONSE");
    try {
      if(!ZoneId.of(root.path("timezone").asText()).getRules().equals(requestedZone.getRules()))throw new Unavailable("INVALID_RESPONSE");
    } catch(DateTimeException invalid) { throw new Unavailable("INVALID_RESPONSE"); }
    var daily=root.path("daily");var units=root.path("daily_units");var times=daily.path("time");
    if(!times.isArray() || times.isEmpty() || times.size()>16)throw new Unavailable("INVALID_RESPONSE");
    var result=new ArrayList<Day>();var dates=new HashSet<LocalDate>();
    for(int i=0;i<times.size();i++) {
      LocalDate date=LocalDate.parse(times.get(i).asText());
      if(!dates.add(date))throw new Unavailable("INVALID_RESPONSE");
      if(date.isBefore(firstDate) || date.isAfter(firstDate.plusDays(6)))continue;
      Double code=number(daily,units,"weather_code","wmo code",i,0,99);
      Integer weather=code==null || code!=Math.rint(code)?null:code.intValue();
      Double min=number(daily,units,"temperature_2m_min","°C",i,-100,70),max=number(daily,units,"temperature_2m_max","°C",i,-100,70);
      if(min!=null && max!=null && min>max) {min=null;max=null;}
      result.add(new Day(date,weather,min,max,
        number(daily,units,"precipitation_probability_max","%",i,0,100),
        number(daily,units,"precipitation_sum","mm",i,0,3000),
        number(daily,units,"wind_speed_10m_max","m/s",i,0,150),
        number(daily,units,"wind_gusts_10m_max","m/s",i,0,150),
        number(daily,units,"et0_fao_evapotranspiration","mm",i,0,1000)));
    }
    if(result.stream().noneMatch(Day::hasData))throw new Unavailable("INVALID_RESPONSE");
    return List.copyOf(result);
  }

  private static Double number(JsonNode daily,JsonNode units,String field,String expected,int index,double min,double max) {
    var values=daily.path(field);var value=values.isArray() && index<values.size()?values.get(index):null;
    if(value==null || value.isNull() || !value.isNumber())return null;
    if(!expected.equals(units.path(field).asText()))throw new Unavailable("INVALID_RESPONSE");
    double number=value.asDouble();return Double.isFinite(number) && number>=min && number<=max?number:null;
  }

  static Transport http(HttpClient client,Duration timeout) {
    return uri -> {
      var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Accept","application/json")
        .header("User-Agent","ZhinongPlatform/0.3 WeatherReference").GET().build();
      var exchange=client.sendAsync(request,info -> new LimitedJsonBody());
      try {
        var response=exchange.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
        return new Remote(response.statusCode(),response.body());
      } catch(TimeoutException timeoutError) {
        exchange.cancel(true);throw new HttpTimeoutException("Forecast response timed out");
      } catch(InterruptedException interrupted) { exchange.cancel(true);throw interrupted; }
      catch(ExecutionException failed) {
        if(failed.getCause() instanceof IOException cause)throw cause;
        throw new IOException("Forecast transport failed",failed.getCause());
      }
    };
  }

  static final class LimitedJsonBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription next) { subscription=next;next.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
      if(body.isDone())return;
      for(var buffer:buffers) {
        if(buffer.remaining()>MAX_BYTES-bytes.size()) {
          subscription.cancel();body.completeExceptionally(new IOException("Forecast exceeds size limit"));return;
        }
        byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
      }
      subscription.request(1);
    }
    public void onError(Throwable failure) { body.completeExceptionally(failure); }
    public void onComplete() { body.complete(bytes.toByteArray()); }
  }
}
