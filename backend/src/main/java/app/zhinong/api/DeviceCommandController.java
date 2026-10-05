package app.zhinong.api;

import app.zhinong.workspace.DeviceCommandService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DeviceCommandController {
  private final DeviceCommandService commands;
  public DeviceCommandController(DeviceCommandService commands) { this.commands = commands; }

  @GetMapping("/assets/{id}/commands")
  Object list(@PathVariable String id) { return commands.list(id); }

  @PostMapping("/assets/{id}/commands")
  Object create(@PathVariable String id, @Valid @RequestBody DeviceCommandService.Input input) { return commands.create(id, input); }

  @PostMapping("/assets/{id}/commands/{commandId}/cancel")
  Object cancel(@PathVariable String id, @PathVariable String commandId) { return commands.cancel(id, commandId); }

  @PostMapping("/ingest/commands/poll")
  Object poll(@RequestHeader(value = "X-Device-Key", required = false) String key) { return commands.poll(key); }

  @PostMapping("/ingest/commands/{commandId}/receipt")
  Object receipt(@RequestHeader(value = "X-Device-Key", required = false) String key,
      @PathVariable String commandId, @Valid @RequestBody DeviceCommandService.Receipt input) {
    return commands.receipt(key, commandId, input);
  }
}
