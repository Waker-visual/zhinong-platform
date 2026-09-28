package app.zhinong.simulation;

import com.fasterxml.jackson.databind.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class WeatherData {

  public record Day(
    LocalDate date,
    double temperature,
    double high,
    double low,
    double rain,
    double wind,
    double et0
  ) {}

  public final List<Day> days;
  public final Map<String, Object> provenance;

  public WeatherData(ObjectMapper json) throws Exception {
    byte[] bytes;
    try (
      var stream = new ClassPathResource(
        "simulation/weather-jiansanjiang-2025.json"
      ).getInputStream()
    ) {
      bytes = stream.readAllBytes();
    }
    var root = json.readTree(bytes);
    var daily = root.path("data").path("daily");
    var list = new ArrayList<Day>();
    for (int i = 0; i < daily.path("time").size(); i++) list.add(
      new Day(
        LocalDate.parse(daily.path("time").get(i).asText()),
        number(daily, "temperature_2m_mean", i),
        number(daily, "temperature_2m_max", i),
        number(daily, "temperature_2m_min", i),
        number(daily, "precipitation_sum", i),
        number(daily, "wind_speed_10m_max", i),
        number(daily, "et0_fao_evapotranspiration", i)
      )
    );
    days = List.copyOf(list);
    provenance = Map.of(
      "source",
      root.path("source").asText(),
      "url",
      root.path("sourceUrl").asText(),
      "license",
      "CC BY 4.0",
      "description",
      root.path("description").asText(),
      "sha256",
      HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes)
      ),
      "startDate",
      "2025-01-01",
      "endDate",
      "2025-12-31",
      "latitude",
      root.path("data").path("latitude").asDouble(),
      "longitude",
      root.path("data").path("longitude").asDouble()
    );
  }

  private static double number(JsonNode data, String key, int i) {
    var v = data.path(key).get(i);
    if (
      v == null || !v.isNumber() || !Double.isFinite(v.asDouble())
    ) throw new IllegalStateException("Missing weather: " + key + "/" + i);
    return v.asDouble();
  }
}
