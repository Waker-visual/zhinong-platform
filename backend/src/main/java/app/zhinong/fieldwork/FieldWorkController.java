package app.zhinong.fieldwork;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/field-work")
public class FieldWorkController {

  private final FieldWorkService work;
  private final AlertFieldWorkService alertWork;

  public FieldWorkController(FieldWorkService work, AlertFieldWorkService alertWork) {
    this.work = work;
    this.alertWork = alertWork;
  }

  @GetMapping
  Object overview(@RequestParam String farmId) {
    return work.overview(farmId);
  }

  @PostMapping("/issues")
  Object report(@Valid @RequestBody FieldWorkService.IssueInput input) {
    return work.report(input);
  }

  @PostMapping("/alerts/{id}/issue")
  Object fromAlert(@PathVariable String id,@Valid @RequestBody AlertFieldWorkService.Input input) {
    return alertWork.report(id,input);
  }

  @PostMapping("/tasks")
  Object create(@Valid @RequestBody FieldWorkService.PlanInput input) {
    return work.create(input, null);
  }

  @PostMapping("/issues/{id}/task")
  Object handle(
    @PathVariable String id,
    @Valid @RequestBody FieldWorkService.PlanInput input
  ) {
    return work.create(input, id);
  }

  @PutMapping("/tasks/{id}/plan")
  Object assign(
    @PathVariable String id,
    @Valid @RequestBody FieldWorkService.PlanInput input
  ) {
    return work.assign(id, input);
  }

  @PatchMapping("/tasks/{id}/progress")
  Object progress(
    @PathVariable String id,
    @Valid @RequestBody FieldWorkService.ProgressInput input
  ) {
    return work.progress(id, input);
  }

  @GetMapping("/tasks/{id}/logs")
  Object logs(@PathVariable String id) {
    return work.logs(id);
  }

  @PostMapping("/issues/{id}/resolve")
  Object resolve(
    @PathVariable String id,
    @Valid @RequestBody FieldWorkService.ReviewInput input
  ) {
    return work.resolve(id, input);
  }
}
