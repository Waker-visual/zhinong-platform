package app.zhinong.weather;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenMeteoForecastGatewayTest {
  final ObjectMapper json=new ObjectMapper();
  final ZoneId zone=ZoneId.of("Asia/Shanghai");
  final LocalDate today=LocalDate.of(2026,10,17);

  byte[] response() {
    return """
      {"timezone":"Asia/Shanghai","daily_units":{"weather_code":"wmo code","temperature_2m_max":"°C","temperature_2m_min":"°C","precipitation_probability_max":"%","precipitation_sum":"mm","wind_speed_10m_max":"m/s","wind_gusts_10m_max":"m/s","et0_fao_evapotranspiration":"mm"},
       "daily":{"time":["2026-10-17","2026-10-18"],"weather_code":[61,null],"temperature_2m_max":[24,null],"temperature_2m_min":[15,16],"precipitation_probability_max":[80,null],"precipitation_sum":[0,null],"wind_speed_10m_max":[3.5,null],"wind_gusts_10m_max":[6.2,null],"et0_fao_evapotranspiration":[2.3,null]}}
      """.getBytes(StandardCharsets.UTF_8);
  }

  @Test void fixedOriginSendsOnlyCoordinatesTimezoneAndExplicitWeatherParameters() {
    var seen=new ArrayList<URI>();
    var gateway=new OpenMeteoForecastGateway(json,uri -> {seen.add(uri);return new OpenMeteoForecastGateway.Remote(200,response());});
    var days=gateway.fetch(31.2,121.5,zone,today);assertEquals(2,days.size());
    assertEquals(0.0,days.get(0).precipitationMm());assertNull(days.get(1).precipitationMm());assertNull(days.get(1).weatherCode());
    URI uri=seen.getFirst();assertEquals("https",uri.getScheme());assertEquals("api.open-meteo.com",uri.getHost());assertEquals("/v1/forecast",uri.getPath());
    var names=new HashSet<String>();for(String parameter:uri.getRawQuery().split("&"))names.add(parameter.split("=",2)[0]);
    assertEquals(Set.of("latitude","longitude","timezone","forecast_days","temperature_unit","wind_speed_unit","precipitation_unit","daily"),names);
    assertTrue(uri.getRawQuery().contains("timezone=Asia%2FShanghai"));assertTrue(uri.getQuery().contains("wind_speed_unit=ms"));
    assertTrue(uri.getQuery().contains("forecast_days=7"));
  }

  @Test void wrongUnitsWrongTimezoneMalformedJsonAndEmptyDataAreUnavailable() {
    for(String body:List.of(new String(response(),StandardCharsets.UTF_8).replace("m/s","km/h"),
        new String(response(),StandardCharsets.UTF_8).replace("Asia/Shanghai","UTC"),"not-json",
        "{\"timezone\":\"Asia/Shanghai\",\"daily\":{\"time\":[\"2026-10-17\"]}}")) {
      var gateway=new OpenMeteoForecastGateway(json,uri -> new OpenMeteoForecastGateway.Remote(200,body.getBytes(StandardCharsets.UTF_8)));
      assertEquals("INVALID_RESPONSE",assertThrows(WeatherForecastGateway.Unavailable.class,()->gateway.fetch(31.2,121.5,zone,today)).diagnostic);
    }
  }

  @Test void redirectsAndOversizedResponsesNeverBecomeForecasts() {
    var redirect=new OpenMeteoForecastGateway(json,uri -> new OpenMeteoForecastGateway.Remote(302,response()));
    assertEquals("UPSTREAM_HTTP_ERROR",assertThrows(WeatherForecastGateway.Unavailable.class,()->redirect.fetch(31.2,121.5,zone,today)).diagnostic);
    var huge=new OpenMeteoForecastGateway(json,uri -> new OpenMeteoForecastGateway.Remote(200,new byte[OpenMeteoForecastGateway.MAX_BYTES+1]));
    assertThrows(WeatherForecastGateway.Unavailable.class,()->huge.fetch(31.2,121.5,zone,today));
  }

  @Test void streamingBodyLimitCancelsTheUpstreamBeforeReadingMoreBytes() {
    var body=new OpenMeteoForecastGateway.LimitedJsonBody();var cancelled=new AtomicBoolean();
    body.onSubscribe(new Flow.Subscription() {public void request(long n) {}public void cancel() {cancelled.set(true);}});
    body.onNext(List.of(ByteBuffer.wrap(new byte[OpenMeteoForecastGateway.MAX_BYTES]),ByteBuffer.wrap(new byte[1])));
    assertTrue(cancelled.get());assertThrows(CompletionException.class,()->body.getBody().toCompletableFuture().join());
  }

  @Test @SuppressWarnings({"unchecked","rawtypes"})
  void wholeResponseTimeoutCancelsTheExchangeRatherThanLeavingAnUnboundedBodyRead() throws Exception {
    var client=mock(HttpClient.class);var exchange=new CompletableFuture<HttpResponse<byte[]>>();
    when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn((CompletableFuture)exchange);
    var transport=OpenMeteoForecastGateway.http(client,Duration.ofMillis(20));
    assertThrows(HttpTimeoutException.class,()->transport.get(URI.create(OpenMeteoForecastGateway.ORIGIN)));
    assertTrue(exchange.isCancelled());
  }
}
