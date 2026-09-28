package app.zhinong.api;

import app.zhinong.business.FarmService;
import app.zhinong.business.Records;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FarmController {

  private final FarmService farms;

  public FarmController(FarmService farms) {
    this.farms = farms;
  }

  @GetMapping("/dashboard")
  Object dashboard() {
    return farms.dashboard();
  }

  @GetMapping("/farms")
  Object farms() {
    return farms.list("farms");
  }

  @PostMapping("/farms")
  Object farm(@Valid @RequestBody Records.Farm input) {
    return farms.farm(input);
  }

  @PutMapping("/farms/{id}")
  Object editFarm(
    @PathVariable String id,
    @Valid @RequestBody Records.Farm input
  ) {
    return farms.updateFarm(id, input);
  }

  @DeleteMapping("/farms/{id}")
  Object deleteFarm(@PathVariable String id) {
    farms.deleteFarm(id);
    return Map.of("ok", true);
  }

  @GetMapping("/plots")
  Object plots(@RequestParam(required = false) String farmId) {
    return farms.scopedList("plots", farmId);
  }

  @PostMapping("/plots")
  Object plot(@Valid @RequestBody Records.Plot input) {
    return farms.plot(input);
  }

  @PutMapping("/plots/{id}")
  Object editPlot(
    @PathVariable String id,
    @Valid @RequestBody Records.Plot input
  ) {
    return farms.updatePlot(id, input);
  }

  @DeleteMapping("/plots/{id}")
  Object deletePlot(@PathVariable String id) {
    farms.deletePlot(id);
    return Map.of("ok", true);
  }

  @GetMapping("/plantings")
  Object plantings(@RequestParam(required = false) String farmId) {
    return farms.scopedList("plantings", farmId);
  }

  @PostMapping("/plantings")
  Object planting(@Valid @RequestBody Records.Planting input) {
    return farms.planting(input);
  }

  @PatchMapping("/plantings/{id}/status")
  Object plantingStatus(
    @PathVariable String id,
    @Valid @RequestBody Records.Status input
  ) {
    return farms.plantingStatus(id, input.status());
  }

  @GetMapping("/tasks")
  Object tasks(@RequestParam(required = false) String farmId) {
    return farms.scopedList("farm_tasks", farmId);
  }

  @PostMapping("/tasks")
  Object task(@Valid @RequestBody Records.Task input) {
    return farms.task(input);
  }

  @PatchMapping("/tasks/{id}/status")
  Object taskStatus(
    @PathVariable String id,
    @Valid @RequestBody Records.Status input
  ) {
    return farms.taskStatus(id, input.status());
  }

  @GetMapping("/production")
  Object production(@RequestParam(required = false) String farmId) {
    return farms.scopedList("production", farmId);
  }

  @PostMapping("/production")
  Object production(@Valid @RequestBody Records.Production input) {
    return farms.production(input);
  }

  @GetMapping("/devices")
  Object devices() {
    return farms.list("devices");
  }

  @PostMapping("/devices")
  Object device(@Valid @RequestBody Records.Device input) {
    return farms.device(input);
  }

  @GetMapping("/observations")
  Object observations() {
    return farms.list("observations");
  }

  @PostMapping("/devices/{id}/observations")
  Object observation(
    @PathVariable String id,
    @Valid @RequestBody Records.Observation input
  ) {
    return farms.observe(id, input);
  }

  @PostMapping("/devices/{id}/sample")
  Object sample(@PathVariable String id) {
    return farms.sample(id);
  }

  @GetMapping("/audit")
  Object audit() {
    return farms.list("audit_events");
  }
}
