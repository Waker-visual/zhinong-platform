package app.zhinong.security;

import app.zhinong.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  // A session expires after two idle hours and never outlives twelve hours from sign-in,
  // so an unattended display or forgotten tab still requires a fresh login each day.
  static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
  static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);
  // Extend at most every few minutes; active pages poll often and need not write each time.
  static final Duration RENEW_STEP = Duration.ofMinutes(5);

  /** The authenticated identity plus the one account flag the request filter needs. */
  public record Session(Identity identity, boolean mustChangePassword) {}

  private final JdbcTemplate db;
  private final LoginThrottle throttle;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(11);
  private final String dummyHash = passwords.encode(
    "invalid-account-timing-equalizer"
  );
  private final SecureRandom random = new SecureRandom();

  public AuthService(JdbcTemplate db, LoginThrottle throttle) {
    this.db = db;
    this.throttle = throttle;
  }

  public String hashPassword(String password) {
    if (
      password == null ||
      password.length() < 12 ||
      password.getBytes(StandardCharsets.UTF_8).length > 72
    ) throw new ApiException(
      400,
      "密码至少 12 个字符，UTF-8 长度不能超过 72 字节"
    );
    return passwords.encode(password);
  }

  public boolean matchesPassword(String password, String hash) {
    return (
      password != null &&
      password.getBytes(StandardCharsets.UTF_8).length <= 72 &&
      passwords.matches(password, hash)
    );
  }

  @Transactional
  public Map<String, Object> login(
    String tenantCode,
    String username,
    String password
  ) {
    throttle.check(tenantCode, username);
    var matches = db.queryForList(
      """
      SELECT m.id, m.password_hash FROM members m JOIN tenants t ON t.id=m.tenant_id
      WHERE t.code=? AND m.username=? AND m.enabled=TRUE AND t.enabled=TRUE FOR UPDATE
      """,
      tenantCode,
      username
    );
    String encoded = matches.isEmpty()
      ? dummyHash
      : matches.getFirst().get("PASSWORD_HASH").toString();
    boolean valid = matchesPassword(password, encoded);
    if (matches.isEmpty() || !valid) {
      throttle.failed(tenantCode, username);
      throw new ApiException(401, "租户、账号或密码不正确");
    }
    throttle.succeeded(tenantCode, username);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(bytes);
    Instant now = Instant.now();
    Instant expires = now.plus(IDLE_TIMEOUT);
    db.update(
      "DELETE FROM sessions WHERE expires_at<?",
      Timestamp.from(now)
    );
    db.update(
      "INSERT INTO sessions(token_hash,member_id,expires_at,created_at) VALUES(?,?,?,?)",
      digest(token),
      matches.getFirst().get("ID"),
      Timestamp.from(expires),
      Timestamp.from(now)
    );
    var session = session(token);
    return Map.of(
      "token",
      token,
      "expiresAt",
      expires.toString(),
      "identity",
      session.identity(),
      "mustChangePassword",
      session.mustChangePassword()
    );
  }

  public Identity authenticate(String token) {
    return session(token).identity();
  }

  private record StoredSession(Session session, Instant expiresAt, Timestamp createdAt) {}

  /** One query per request: identity, the password-change flag and sliding renewal. */
  public Session session(String token) {
    if (token == null || token.length() > 256) throw new ApiException(
      401,
      "请先登录"
    );
    String hash = digest(token);
    Instant now = Instant.now();
    var results = db.query(
      """
      SELECT m.id,m.tenant_id,t.name,m.username,m.display_name,m.role,
        COALESCE(p.must_change_password,FALSE),s.expires_at,s.created_at
      FROM sessions s JOIN members m ON m.id=s.member_id JOIN tenants t ON t.id=m.tenant_id
      LEFT JOIN member_preferences p ON p.member_id=m.id
      WHERE s.token_hash=? AND s.expires_at>? AND m.enabled=TRUE AND t.enabled=TRUE
      """,
      (rs, n) ->
        new StoredSession(
          new Session(
            new Identity(
              rs.getString(1),
              rs.getString(2),
              rs.getString(3),
              rs.getString(4),
              rs.getString(5),
              rs.getString(6)
            ),
            rs.getBoolean(7)
          ),
          rs.getTimestamp(8).toInstant(),
          rs.getTimestamp(9)
        ),
      hash,
      Timestamp.from(now)
    );
    if (results.isEmpty()) throw new ApiException(
      401,
      "登录已过期或账号/租户已停用"
    );
    var stored = results.getFirst();
    renew(hash, stored.expiresAt(), stored.createdAt(), now);
    return stored.session();
  }

  private void renew(String hash, Instant expiresAt, Timestamp createdAt, Instant now) {
    // Sessions issued before created_at existed were never extended, so their start is
    // one idle window before expiry; the update pins it so the cap cannot keep moving.
    Instant created = createdAt == null ? expiresAt.minus(IDLE_TIMEOUT) : createdAt.toInstant();
    Instant cap = created.plus(ABSOLUTE_TIMEOUT);
    Instant target = now.plus(IDLE_TIMEOUT).isBefore(cap) ? now.plus(IDLE_TIMEOUT) : cap;
    if (!target.isAfter(expiresAt.plus(RENEW_STEP))) return;
    db.update(
      "UPDATE sessions SET expires_at=?,created_at=COALESCE(created_at,?) WHERE token_hash=? AND expires_at<?",
      Timestamp.from(target),
      Timestamp.from(created),
      hash,
      Timestamp.from(target)
    );
  }

  public void logout(String token) {
    db.update("DELETE FROM sessions WHERE token_hash=?", digest(token));
  }

  public static String digest(String value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          value.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
