package app.zhinong.risk;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeatherRiskRulesTest {
  final Instant now=Instant.parse("2026-10-17T01:00:00Z");
  Map<String,Object> forecast(List<Map<String,Object>> days){return new LinkedHashMap<>(Map.of("status","READY","expiresAt",now.plusSeconds(60).toString(),"days",days));}
  Map<String,Object> day(String date,Object...pairs){var d=new LinkedHashMap<String,Object>();d.put("date",date);for(int i=0;i<pairs.length;i+=2)d.put(pairs[i].toString(),pairs[i+1]);return d;}
  @Test void exactThresholdsProduceEvidenceAndSeparateDatesWithoutDuplicateCards(){
    var f=forecast(List.of(day("2026-10-17","tempMaxC",35,"tempMinC",0,"precipitationMm",25,"gustMaxMs",10,"weatherCode",95),
      day("2026-10-18","tempMaxC",26),day("2026-10-19","tempMaxC",36)));
    var items=WeatherRiskRules.evaluate(f,now);assertEquals(6,items.size());
    var heat=items.stream().filter(i->"weather:HEAT".equals(i.get("id"))).findFirst().orElseThrow();
    assertEquals(List.of("2026-10-17","2026-10-19"),heat.get("affectedDates"));
    assertEquals(2,((List<?>)heat.get("evidence")).size());assertEquals("FORECAST",heat.get("status"));
    assertTrue(items.stream().allMatch(i->i.get("recommendation")!=null&&i.get("action")!=null));
  }
  @Test void missingNumbersNeverBecomeFrostOrRainAndRealZeroStillTriggersLowTemperature(){
    assertTrue(WeatherRiskRules.evaluate(forecast(List.of(day("2026-10-17","tempMinC",null,"precipitationMm",null))),now).isEmpty());
    assertEquals(1,WeatherRiskRules.evaluate(forecast(List.of(day("2026-10-17","tempMinC",0))),now).size());
  }
  @Test void staleOrExactlyExpiredForecastCannotGenerateNewRisk(){
    var f=forecast(List.of(day("2026-10-17","tempMaxC",40)));f.put("status","STALE");assertTrue(WeatherRiskRules.evaluate(f,now).isEmpty());
    f.put("status","READY");f.put("expiresAt",now.toString());assertTrue(WeatherRiskRules.evaluate(f,now).isEmpty());
    f.put("expiresAt",null);assertTrue(WeatherRiskRules.evaluate(f,now).isEmpty());
  }
  @Test void missingDateDoesNotImplyAdjacentDayCooling(){
    assertTrue(WeatherRiskRules.evaluate(forecast(List.of(day("2026-10-17","tempMaxC",34),day("2026-10-19","tempMaxC",10))),now).isEmpty());
  }
}
