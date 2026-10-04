package app.zhinong.api;

import app.zhinong.workspace.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {

  private final FarmWorkspaceService farms;
  private final AssetService assets;
  private final TelemetryService telemetry;
  private final OperationsService operations;

  public WorkspaceController(
    FarmWorkspaceService farms,
    AssetService assets,
    TelemetryService telemetry,
    OperationsService operations
  ) {
    this.farms = farms;
    this.assets = assets;
    this.telemetry = telemetry;
    this.operations = operations;
  }

  @GetMapping("/farm-workspaces")
  Object cards() {
    return farms.cards();
  }

  @PostMapping("/farm-workspaces")
  Object createFarm(@Valid @RequestBody WorkspaceInputs.Profile input) {
    return farms.create(input);
  }

  @GetMapping("/farms/{id}/workspace")
  Object workspace(@PathVariable String id, @RequestParam(defaultValue = "30") int days) {
    return farms.workspace(id, days);
  }

  @GetMapping("/farms/{id}/analytics")
  Object analytics(@PathVariable String id, @RequestParam(defaultValue = "30") int days) {
    return farms.analytics(id, days);
  }

  @GetMapping("/farms/{id}/operations")
  Object operations(@PathVariable String id, @RequestParam(defaultValue = "168") int hours) {
    return operations.overview(id, hours);
  }

  @PutMapping("/farms/{id}/profile")
  Object profile(@PathVariable String id, @Valid @RequestBody WorkspaceInputs.Profile input) {
    farms.profile(id, input);
    return Map.of("ok", true);
  }

  @PutMapping("/farms/{id}/layout")
  Object layout(@PathVariable String id, @Valid @RequestBody WorkspaceInputs.Layout input) {
    return farms.saveLayout(id, input);
  }

  @GetMapping("/assets/catalog")
  Object catalog() {
    return assets.catalog();
  }

  @GetMapping("/assets")
  Object assets(@RequestParam(required = false) String farmId) {
    return assets.list(farmId);
  }

  @PostMapping("/assets")
  Object create(@Valid @RequestBody WorkspaceInputs.Asset input) {
    return assets.save(null, input);
  }

  @GetMapping("/assets/{id}")
  Object detail(@PathVariable String id) {
    return assets.detail(id);
  }

  @PutMapping("/assets/{id}")
  Object update(@PathVariable String id, @Valid @RequestBody WorkspaceInputs.Asset input) {
    return assets.save(id, input);
  }

  @GetMapping("/assets/{id}/history")
  Object history(
    @PathVariable String id,
    @RequestParam String metric,
    @RequestParam(defaultValue = "24") int hours
  ) {
    return assets.history(id, metric, hours);
  }

  @PostMapping("/assets/{id}/collect")
  Object collect(@PathVariable String id) {
    return telemetry.collect(id);
  }

  @PostMapping("/assets/{id}/readings")
  Object manual(@PathVariable String id, @Valid @RequestBody WorkspaceInputs.Measurements input) {
    return telemetry.manual(id, input);
  }

  @PostMapping("/assets/{id}/credentials")
  Object credential(@PathVariable String id) {
    return telemetry.rotateKey(id);
  }

  @GetMapping("/alerts")
  Object alerts(
    @RequestParam(required = false) String farmId,
    @RequestParam(required = false) String status
  ) {
    return telemetry.alerts(farmId, status);
  }

  @PatchMapping("/alerts/{id}")
  Object handle(@PathVariable String id, @Valid @RequestBody WorkspaceInputs.AlertAction input) {
    telemetry.handle(id, input);
    return Map.of("ok", true);
  }

  @PostMapping("/ingest/telemetry")
  Object ingest(
    @RequestHeader(value = "X-Device-Key", required = false) String key,
    @Valid @RequestBody WorkspaceInputs.Ingest input,
    HttpServletRequest request
  ) {
    if (request.getHeader("X-Tenant-Id") != null || request.getHeader("tenantId") != null) {
      throw new ApiException(403, "设备租户由接入凭据决定，不能通过请求指定");
    }
    return telemetry.ingest(key, input);
  }
}
