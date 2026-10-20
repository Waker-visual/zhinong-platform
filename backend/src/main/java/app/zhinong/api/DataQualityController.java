package app.zhinong.api;

import app.zhinong.workspace.FarmDataQualityService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farms")
public class DataQualityController {
  private final FarmDataQualityService quality;
  public DataQualityController(FarmDataQualityService quality) { this.quality=quality; }
  @GetMapping("/{id}/data-quality") Object check(@PathVariable String id) { return quality.check(id); }
}
