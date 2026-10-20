package app.zhinong.business;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class Records {

  private Records() {}

  public record Farm(
    @NotBlank @Size(max = 100) String name,
    @NotNull @Size(max = 500) String description
  ) {}

  public record Plot(
    @NotBlank String farmId,
    @NotBlank @Size(max = 100) String name,
    @NotNull @DecimalMin("0.01") @DecimalMax("99999999") BigDecimal areaMu,
    @NotNull @Size(max = 80) String crop
  ) {}

  public record Planting(
    @NotBlank String plotId,
    @NotBlank @Size(max = 80) String crop,
    @NotNull @Size(max = 80) String variety,
    @NotNull @DecimalMin("0.01") @DecimalMax("99999999") BigDecimal areaMu,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate
  ) {}

  public record Task(
    @NotBlank String plotId,
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Pattern(
      regexp = "SOWING|IRRIGATION|FERTILIZING|HARVEST|INSPECTION|PROTECTION"
    ) String taskType,
    @NotNull LocalDate dueDate,
    @NotNull @Size(max = 500) String note
  ) {}

  public record Production(
    @NotBlank String plotId,
    @NotNull LocalDate recordDate,
    @NotNull @DecimalMin("0.01") @DecimalMax("999999999") @Digits(integer = 9, fraction = 2) BigDecimal yieldKg,
    @NotNull @Size(max = 500) String note,
    @Size(max = 36) String plantingId,
    @Size(max = 36) String taskId,
    @Pattern(regexp = "[A-Za-z0-9._:-]{1,80}") String requestId
  ) {
    public Production(String plotId, LocalDate recordDate, BigDecimal yieldKg, String note) {
      this(plotId, recordDate, yieldKg, note, null, null, null);
    }
  }

  public record ProductionCorrection(
    @NotBlank String plotId,
    @NotNull LocalDate recordDate,
    @NotNull @DecimalMin("0.01") @DecimalMax("999999999") @Digits(integer = 9, fraction = 2) BigDecimal yieldKg,
    @NotNull @Size(max = 500) String note,
    @Size(max = 36) String plantingId,
    @Size(max = 36) String taskId,
    @NotBlank @Size(max = 500) String reason,
    @NotNull @Min(0) Integer expectedRevision
  ) {}

  public record ProductionVoid(
    @NotBlank @Size(max = 500) String reason,
    @NotNull @Min(0) Integer expectedRevision
  ) {}

  public record Device(
    @NotBlank String farmId,
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Pattern(
      regexp = "TEMPERATURE|HUMIDITY|SOIL_MOISTURE"
    ) String metric,
    @NotBlank @Pattern(regexp = "MANUAL|SIMULATED") String adapter
  ) {}

  public record Observation(
    @NotNull @Digits(integer = 11, fraction = 3) BigDecimal value,
    @NotNull LocalDateTime measuredAt
  ) {}

  public record Status(@NotBlank String status) {}

  public record Tenant(
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{2,39}") String code,
    @NotBlank @Size(max = 120) String name,
    @NotBlank @Size(min = 12, max = 72) String adminPassword
  ) {}

  public record Enabled(boolean enabled) {}

  public record Member(
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{2,59}") String username,
    @NotBlank @Size(max = 80) String displayName,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotBlank @Pattern(regexp = "ADMIN|OPERATOR|VIEWER") String role
  ) {}
}
