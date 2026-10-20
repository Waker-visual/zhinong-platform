package app.zhinong.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/decisions")
public class EvidenceDecisionController {
  private final EvidenceDecisionService decisions;
  public EvidenceDecisionController(EvidenceDecisionService decisions) { this.decisions = decisions; }
  public record Draft(@NotBlank String farmId, @NotBlank String plotId,
    @NotBlank @Size(max=500) String question,
    @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,80}") String requestId) {}
  public record Approval(@NotBlank @Size(max=120) String title, @NotBlank String assigneeId,
    @NotNull LocalDate dueDate, @NotBlank @Size(max=400) String note, @Min(0) int expectedRevision) {}
  public record Review(@NotBlank @Size(max=500) String note,
    @DecimalMin("0") @DecimalMax("100") BigDecimal manualValue, OffsetDateTime observedAt,
    @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,80}") String requestId, @Min(0) int expectedRevision) {}
  @GetMapping Object list(@RequestParam String farmId) { return decisions.list(farmId); }
  @GetMapping("/{id}") Object get(@PathVariable String id) { return decisions.get(id); }
  @PostMapping Object draft(@RequestBody @Valid Draft input) { return decisions.draft(input); }
  @PostMapping("/{id}/explain") Object explain(@PathVariable String id) { return decisions.explain(id); }
  @PostMapping("/{id}/approve") Object approve(@PathVariable String id, @RequestBody @Valid Approval input) { return decisions.approve(id,input); }
  @PostMapping("/{id}/reviews") Object review(@PathVariable String id, @RequestBody @Valid Review input) { return decisions.review(id,input); }
}
