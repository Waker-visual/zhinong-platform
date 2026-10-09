package app.zhinong.ai;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/ai/irrigation")
public class IrrigationController {
  private final IrrigationService service;
  public IrrigationController(IrrigationService service) {this.service=service;}
  @GetMapping public Map<String,Object> workspace(@RequestParam String farmId) {return service.workspace(farmId);}
  @PutMapping("/policy") public Map<String,Object> save(@RequestBody @Valid IrrigationService.Policy input) {return service.save(input);}
  @PostMapping("/plots/{id}/propose") public Map<String,Object> propose(@PathVariable String id) {return service.propose(id,false);}
  @PostMapping("/runs/{id}/approve") public Map<String,Object> approve(@PathVariable String id) {return service.approve(id,false);}
  @PostMapping("/runs/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id) {return service.cancel(id);}
}
