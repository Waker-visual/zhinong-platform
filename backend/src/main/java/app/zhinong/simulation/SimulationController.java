package app.zhinong.simulation;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulations")
public class SimulationController {

  private final Store core;
  private final SimulationStore storage;
  private final SimulationEngine engine;
  private final WeatherData weather;

  public SimulationController(
    Store core,
    SimulationStore storage,
    SimulationEngine engine,
    WeatherData weather
  ) {
    this.core = core;
    this.storage = storage;
    this.engine = engine;
    this.weather = weather;
  }

  @GetMapping("/catalog")
  Object catalog() {
    Identity.tenant();
    return Map.of(
      "crops",
      SimulationEngine.CROPS,
      "weather",
      weather.provenance,
      "storage",
      storage.status(),
      "assumptions",
      SimulationEngine.assumptions(),
      "modelVersion",
      SimulationEngine.VERSION,
      "faoSource",
      "https://www.fao.org/4/X5647E/x5647e0e.htm"
    );
  }

  @GetMapping
  Object list(@RequestParam(required = false) String farmId) {
    if (farmId != null && !farmId.isBlank()) core.get("farms", farmId);
    return storage.list(farmId);
  }

  @GetMapping("/{id}")
  Object get(@PathVariable String id) {
    return storage.get(id);
  }

  @PostMapping
  Object run(@Valid @RequestBody SimulationInput input) {
    Identity.require("ADMIN", "OPERATOR");
    var farm = core.get("farms", input.farmId());
    var models = input.cropModels() == null
      ? Map.<String, String>of()
      : input.cropModels();
    var plots = core
      .db()
      .query(
        "SELECT id,name,crop,area_mu FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY id",
        (rs, n) -> {
          String crop = rs.getString("crop"),
            model = models.get(rs.getString("id"));
          if (model == null) model = crop.contains("稻")
            ? "RICE"
            : crop.contains("玉米")
              ? "MAIZE"
              : crop.contains("麦")
                ? "WHEAT"
                : "VEGETABLE";
          return new SimulationEngine.Plot(
            rs.getString("id"),
            rs.getString("name"),
            crop,
            rs.getDouble("area_mu"),
            model
          );
        },
        Identity.tenant(),
        input.farmId()
      );
    if (
      models
        .keySet()
        .stream()
        .anyMatch(id -> plots.stream().noneMatch(p -> p.id().equals(id)))
    ) throw new ApiException(400, "作物模型包含不属于当前农场的地块");
    var result = new LinkedHashMap<>(engine.run(input, plots));
    result.put("farmName", farm.get("name"));
    result.put("storage", storage.status());
    String id = storage.save(input, result, weather.provenance);
    return Map.of("id", id, "result", result);
  }
}
