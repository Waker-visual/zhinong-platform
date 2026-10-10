package app.zhinong.api;
import app.zhinong.workspace.FieldMapService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/farms/{farm}/field-map")
public class FieldMapController {
  private final FieldMapService service;
  public FieldMapController(FieldMapService service){this.service=service;}
  @GetMapping Object workspace(@PathVariable String farm){return service.workspace(farm);}
  @PostMapping("/parcels") Object parcel(@PathVariable String farm,@Valid @RequestBody FieldMapService.ParcelInput input){return service.addParcel(farm,input);}
  @PostMapping("/routes/preview") Object preview(@PathVariable String farm,@Valid @RequestBody FieldMapService.WorkInput input){return service.preview(farm,input);}
  @PostMapping("/jobs") Object dispatch(@PathVariable String farm,@Valid @RequestBody FieldMapService.WorkInput input){return service.dispatch(farm,input);}
  @PostMapping("/irrigation") Object water(@PathVariable String farm,@Valid @RequestBody FieldMapService.WaterInput input){return service.water(farm,input);}
  @PostMapping("/jobs/{id}/actions") Object act(@PathVariable String farm,@PathVariable String id,@Valid @RequestBody FieldMapService.JobAction input){return service.act(farm,id,input.action());}
}
