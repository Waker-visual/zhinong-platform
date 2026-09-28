package app.zhinong.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class WorkspaceInputs {

  private WorkspaceInputs() {}

  public record Channel(@NotBlank String metric, BigDecimal lowerLimit, BigDecimal upperLimit) {}

  public record Asset(
    @NotBlank String farmId,
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,60}") String code,
    @NotBlank String deviceType,
    @NotBlank String protocol,
    @NotBlank @Pattern(regexp = "ACTIVE|MAINTENANCE|DISABLED") String lifecycle,
    String plotId,
    BigDecimal planX,
    BigDecimal planY,
    @NotNull @Size(max = 100) String model,
    @NotNull @Size(max = 500) String notes,
    @Min(30) @Max(86400) int intervalSeconds,
    @NotEmpty @Size(max = 12) List<@Valid Channel> channels,
    @Min(0) int revision
  ) {}

  public record Shape(
    @NotBlank String plotId,
    @NotNull @Size(max = 80) List<List<Double>> boundary
  ) {}

  public record Position(@NotBlank String deviceId, BigDecimal x, BigDecimal y) {}

  public record Layout(
    @Min(0) int revision,
    @NotNull @Size(max = 200) List<@Valid Shape> shapes,
    @NotNull @Size(max = 500) List<@Valid Position> positions
  ) {}

  public record Profile(
    @NotBlank @Size(max = 100) String name,
    @NotNull @Size(max = 500) String description,
    @NotNull @Size(max = 120) String region,
    @NotBlank @Pattern(regexp = "FIELD|GREENHOUSE|ORCHARD|MIXED") String farmType
  ) {}

  public record Reading(@NotBlank String metric, @NotNull BigDecimal value) {}

  public record Measurements(
    @NotNull @PastOrPresent Instant measuredAt,
    @NotEmpty @Size(max = 12) List<@Valid Reading> readings
  ) {}

  public record Ingest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,80}") String messageId,
    @NotNull @PastOrPresent Instant measuredAt,
    @NotEmpty @Size(max = 12) List<@Valid Reading> readings
  ) {}

  public record AlertAction(
    @NotBlank @Pattern(regexp = "ACKNOWLEDGED|RESOLVED") String status,
    @NotBlank @Size(max = 300) String note
  ) {}
}
