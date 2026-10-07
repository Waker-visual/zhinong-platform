package app.zhinong.api;

import app.zhinong.device.SmartFarmIntegration;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SmartFarmController {
  private final SmartFarmIntegration service;
  public SmartFarmController(SmartFarmIntegration service) { this.service=service; }
  @GetMapping("/assets/{id}/integration") Object get(@PathVariable String id) { return service.get(id); }
  @PutMapping("/assets/{id}/integration") Object save(@PathVariable String id,@Valid @RequestBody SmartFarmIntegration.Config input) { return service.save(id,input); }
  @PostMapping("/ingest/smart-farm") Object ingest(@RequestHeader(value="X-Device-Key",required=false) String key,
      @Valid @RequestBody SmartFarmIntegration.Packet input) { return service.ingest(key,input); }
  @PostMapping("/ingest/smart-farm/commands/poll") Object poll(@RequestHeader(value="X-Device-Key",required=false) String key) { return service.poll(key); }
  @PostMapping("/ingest/smart-farm/commands/{id}/receipt") Object receipt(@RequestHeader(value="X-Device-Key",required=false) String key,
      @PathVariable String id,@Valid @RequestBody SmartFarmIntegration.NativeReceipt input) { return service.receipt(key,id,input); }
}
