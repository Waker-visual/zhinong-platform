package app.zhinong.security;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-15T08:00:00Z");
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
    void advance(Duration duration) { now = now.plus(duration); }
  }

  @Test
  void typosStayFreeThenFailuresBackOffUpToFifteenMinutes() {
    var clock = new MutableClock();
    var throttle = new LoginThrottle(clock);
    for (int i = 1; i < LoginThrottle.FREE_ATTEMPTS; i++) {
      throttle.check("demo-a", "admin");
      throttle.failed("demo-a", "admin");
    }
    throttle.check("demo-a", "admin");
    throttle.failed("demo-a", "admin");
    var locked = assertThrows(ApiException.class, () -> throttle.check("demo-a", "admin"));
    assertEquals(429, locked.status());
    assertTrue(locked.getMessage().contains("30 秒"), locked.getMessage());

    // Case and spacing variants share the same counter.
    assertThrows(ApiException.class, () -> throttle.check(" DEMO-A ", "Admin"));
    // Other accounts are unaffected.
    throttle.check("demo-a", "operator");

    clock.advance(Duration.ofSeconds(31));
    throttle.check("demo-a", "admin");
    assertEquals(Duration.ofSeconds(60), LoginThrottle.lockFor(LoginThrottle.FREE_ATTEMPTS + 1));
    assertEquals(LoginThrottle.MAX_LOCK, LoginThrottle.lockFor(40));
  }

  @Test
  void successAndQuietPeriodsClearTheHistory() {
    var clock = new MutableClock();
    var throttle = new LoginThrottle(clock);
    for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS - 1; i++) throttle.failed("demo-a", "admin");
    throttle.succeeded("demo-a", "admin");
    throttle.failed("demo-a", "admin");
    throttle.check("demo-a", "admin");

    for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS - 2; i++) throttle.failed("demo-a", "admin");
    clock.advance(LoginThrottle.FORGET_AFTER.plusSeconds(1));
    throttle.failed("demo-a", "admin");
    throttle.check("demo-a", "admin");
  }
}
