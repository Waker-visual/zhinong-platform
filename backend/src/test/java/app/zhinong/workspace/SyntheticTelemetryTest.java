package app.zhinong.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SyntheticTelemetryTest {
  @Test
  void daylightSoilLayersAndDiscreteValuesStayPhysicallyConsistent() {
    var state = new HashMap<String, BigDecimal>();
    Instant start = Instant.parse("2026-09-01T16:00:00Z"); // local midnight
    for (int i = 0; i < 30 * 96; i++) {
      Instant at = start.plusSeconds(i * 900L);
      var values = SyntheticTelemetry.sample("WEATHER", "invented-farm", at, state, 900);
      values.forEach(MetricCatalog::validate);
      int hour = at.atOffset(ZoneOffset.ofHours(8)).getHour();
      if (hour < 6 || hour >= 18) assertEquals(0, values.get("LIGHT").signum());
      if (!state.isEmpty()) assertTrue(values.get("RAINFALL").compareTo(state.get("RAINFALL")) >= 0);
      state.putAll(values);
    }
    var noon = SyntheticTelemetry.sample("SOIL", "invented-farm", start.plusSeconds(12 * 3600), Map.of(), 900);
    var night = SyntheticTelemetry.sample("SOIL", "invented-farm", start, Map.of(), 900);
    assertTrue(noon.get("TEMPERATURE").compareTo(night.get("TEMPERATURE")) > 0);
    assertTrue(noon.get("HUMIDITY").compareTo(night.get("HUMIDITY")) < 0);
    assertNotEquals(noon.get("SOIL_TEMPERATURE"), noon.get("SOIL_TEMPERATURE_3"));
  }

  @Test
  void pumpCountersNeverDriftOrDecreaseAndControlStateSurvivesSampling() {
    Instant at = Instant.parse("2026-10-07T00:00:00Z");
    var idle = SyntheticTelemetry.sample("PUMP", "farm", at, Map.of(), 900);
    assertEquals(0, idle.get("FLOW").signum());
    assertEquals(0, idle.get("CURRENT").signum());
    assertEquals(idle.get("ENERGY"), SyntheticTelemetry.sample("PUMP", "farm", at.plusSeconds(900), idle, 900).get("ENERGY"));
    idle.put("PUMP_RUNNING", BigDecimal.ONE);
    idle.put("PUMP_FREQUENCY", BigDecimal.valueOf(35));
    idle.put("GATE_OPENING", BigDecimal.valueOf(40));
    var running = SyntheticTelemetry.sample("PUMP", "farm", at.plusSeconds(1800), idle, 900);
    assertEquals(new BigDecimal("5.000"), running.get("WATER_TOTAL").subtract(idle.get("WATER_TOTAL")));
    assertTrue(running.get("ENERGY").compareTo(idle.get("ENERGY")) > 0);
    assertEquals(new BigDecimal("40.000"), running.get("GATE_OPENING"));
    running.put("PUMP_RUNNING", BigDecimal.ZERO);
    var stopped = SyntheticTelemetry.sample("PUMP", "farm", at.plusSeconds(2700), running, 900);
    assertEquals(0, stopped.get("FLOW").signum());
    assertEquals(running.get("WATER_TOTAL"), stopped.get("WATER_TOTAL"));
    assertEquals(running.get("ENERGY"), stopped.get("ENERGY"));
  }
}
