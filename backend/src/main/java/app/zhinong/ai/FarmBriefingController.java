package app.zhinong.ai;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/briefings")
public class FarmBriefingController {
  private final FarmBriefingService briefings;

  public FarmBriefingController(FarmBriefingService briefings) { this.briefings = briefings; }

  @GetMapping("/today")
  public Map<String, Object> today(@RequestParam String farmId) { return briefings.today(farmId); }

  @GetMapping("/history")
  public List<Map<String, Object>> history(@RequestParam String farmId) { return briefings.history(farmId); }
}
