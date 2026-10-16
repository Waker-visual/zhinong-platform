package app.zhinong.business;

import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The farm's business calendar. "Today", "now" and day boundaries follow {@code farm.zone}
 * (default Asia/Shanghai), never the server's default zone: a UTC host would otherwise move
 * every date eight hours late. A {@link Clock} bean, when one exists (tests), replaces the
 * system clock; business code reads time only through this component.
 */
@Component
public class FarmClock {
  public static final String DEFAULT_ZONE = "Asia/Shanghai";
  private final Clock clock;

  @Autowired
  public FarmClock(@Value("${farm.zone:" + DEFAULT_ZONE + "}") String zone, ObjectProvider<Clock> source) {
    // An empty FARM_ZONE keeps the default; an unknown zone fails at startup instead of mid-request.
    this(source.getIfAvailable(Clock::systemUTC).withZone(ZoneId.of(zone.isBlank() ? DEFAULT_ZONE : zone.strip())));
  }

  /** Manual wiring (unit tests): the clock's own zone is the farm zone. */
  public FarmClock(Clock clock) { this.clock = clock; }

  public static FarmClock system() { return new FarmClock(Clock.system(ZoneId.of(DEFAULT_ZONE))); }

  public ZoneId zone() { return clock.getZone(); }
  public Instant instant() { return clock.instant(); }
  /** The current instant at the farm's offset. */
  public OffsetDateTime now() { return OffsetDateTime.now(clock); }
  public LocalDate today() { return LocalDate.now(clock); }
  public LocalTime time() { return LocalTime.now(clock); }
  /** The first instant of a farm calendar day, for "today's" counters and date windows. */
  public OffsetDateTime startOf(LocalDate day) { return day.atStartOfDay(zone()).toOffsetDateTime(); }
}
