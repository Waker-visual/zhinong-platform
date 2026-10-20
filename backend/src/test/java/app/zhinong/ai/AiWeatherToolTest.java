package app.zhinong.ai;
import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.weather.WeatherForecastService;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiWeatherToolTest {
 @Test void futureForecastHasASeparateReadOnlyToolAndPreservesAvailabilityAndMissingValues(){
  var payload=new LinkedHashMap<String,Object>();payload.put("status","UNCONFIGURED");payload.put("fetchedAt",null);payload.put("days",List.of());
  var requested=new ArrayList<String>();
  var forecast=new WeatherForecastService(null,null,null,false){@Override public Map<String,Object> get(String farm){requested.add(farm);return payload;}};
  var tools=new AiStreamTools(null,null,null,new ObjectMapper(),null,forecast);
  assertTrue(tools.definitions().stream().anyMatch(t->((Map<?,?>)t.get("function")).get("name").equals("get_weather_forecast")));
  assertTrue(tools.definitions().stream().anyMatch(t->((Map<?,?>)t.get("function")).get("name").equals("get_weather_conditions")));
  assertSame(payload,tools.run("get_weather_forecast","session-bound-farm"));assertEquals(List.of("session-bound-farm"),requested);
  assertTrue(tools.toJson(payload).contains("\"fetchedAt\":null"));
  assertFalse(tools.resultSummary("get_weather_forecast",payload).contains("成功"));
 }
}
