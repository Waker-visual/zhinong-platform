package app.zhinong.api;
import app.zhinong.risk.FarmRiskService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/farms")
public class FarmRiskController {
  private final FarmRiskService risks;
  public FarmRiskController(FarmRiskService risks){this.risks=risks;}
  @GetMapping("/{id}/risk-overview") Object overview(@PathVariable String id){return risks.overview(id);}
}
