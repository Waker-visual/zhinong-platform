package app.zhinong.security;

import app.zhinong.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-account backoff after repeated wrong passwords. The first attempts stay free so a typo
 * never locks anyone out; later failures wait 30 seconds, doubling up to 15 minutes. State is
 * in memory on purpose: a restart clears it and no attempt history is persisted.
 */
@Component
public class LoginThrottle {

  static final int FREE_ATTEMPTS = 5;
  static final Duration FIRST_LOCK = Duration.ofSeconds(30);
  static final Duration MAX_LOCK = Duration.ofMinutes(15);
  // Failures older than this no longer count, so occasional typos over weeks never add up.
  static final Duration FORGET_AFTER = Duration.ofMinutes(30);
  private static final int MAX_ENTRIES = 10_000;

  private record Failures(int count, Instant last, Instant lockedUntil) {}

  private final Clock clock;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  @Autowired
  public LoginThrottle() {
    this(Clock.systemUTC());
  }

  LoginThrottle(Clock clock) {
    this.clock = clock;
  }

  /** Rejects before the password hash is checked, which also keeps bcrypt work bounded. */
  public void check(String tenantCode, String username) {
    var entry = failures.get(key(tenantCode, username));
    Instant now = clock.instant();
    if (entry == null || entry.lockedUntil() == null || !entry.lockedUntil().isAfter(now)) return;
    long seconds = Math.max(1, Duration.between(now, entry.lockedUntil()).toSeconds());
    throw new ApiException(429, "登录失败次数过多，请 " + seconds + " 秒后再试");
  }

  public void failed(String tenantCode, String username) {
    Instant now = clock.instant();
    failures.compute(key(tenantCode, username), (k, previous) -> {
      int count = previous == null || previous.last().plus(FORGET_AFTER).isBefore(now) ? 1 : previous.count() + 1;
      return new Failures(count, now, count < FREE_ATTEMPTS ? null : now.plus(lockFor(count)));
    });
    if (failures.size() > MAX_ENTRIES) prune(now);
  }

  public void succeeded(String tenantCode, String username) {
    failures.remove(key(tenantCode, username));
  }

  static Duration lockFor(int count) {
    long seconds = FIRST_LOCK.toSeconds() << Math.min(20, count - FREE_ATTEMPTS);
    return Duration.ofSeconds(Math.min(seconds, MAX_LOCK.toSeconds()));
  }

  private void prune(Instant now) {
    failures.entrySet().removeIf(e -> e.getValue().last().plus(FORGET_AFTER).isBefore(now));
    // Still full means a flood of distinct names; dropping history is safer than growing without bound.
    if (failures.size() > MAX_ENTRIES) failures.clear();
  }

  private static String key(String tenantCode, String username) {
    return tenantCode.strip().toLowerCase(Locale.ROOT) + "\n" + username.strip().toLowerCase(Locale.ROOT);
  }
}
