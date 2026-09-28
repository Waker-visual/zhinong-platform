package app.zhinong.simulation;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.Map;

public record SimulationInput(
  @NotBlank String farmId,
  @NotBlank @Size(max = 100) String label,
  @NotNull LocalDate startDate,
  @Min(90) @Max(365) int days,
  @Min(0) @Max(80) int initialAge,
  @Min(0) @Max(50) int drones,
  @DecimalMin("1") @DecimalMax("10000") double droneCapacity,
  @DecimalMin("0") @DecimalMax("10000") double manualCapacity,
  @Min(1) @Max(30) int inspectionInterval,
  @Min(1) @Max(30) int responseDays,
  @Min(0) @Max(364) int outbreakDay,
  @Min(0) @Max(365) int outageDays,
  @DecimalMin("0") @DecimalMax("1") double severity,
  @DecimalMin("0") @DecimalMax("1000000") double irrigationM3,
  @DecimalMin("0") @DecimalMax("1") double fertilizerCoverage,
  @Size(max = 100) Map<String, String> cropModels
) {}
