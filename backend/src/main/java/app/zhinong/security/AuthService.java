package app.zhinong.security;

import app.zhinong.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
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

  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(11);
  private final String dummyHash = passwords.encode(
    "invalid-account-timing-equalizer"
  );
  private final SecureRandom random = new SecureRandom();

  public AuthService(JdbcTemplate db) {
    this.db = db;
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

  public boolean passwordChangeRequired(String memberId) {
    return (
      db.queryForObject(
        "SELECT COUNT(*) FROM member_preferences WHERE member_id=? AND must_change_password=TRUE",
        Long.class,
        memberId
      ) >
      0
    );
  }

  @Transactional
  public Map<String, Object> login(
    String tenantCode,
    String username,
    String password
  ) {
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
    if (matches.isEmpty() || !valid) throw new ApiException(
      401,
      "租户、账号或密码不正确"
    );
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(bytes);
    Instant expires = Instant.now().plusSeconds(7200);
    db.update(
      "DELETE FROM sessions WHERE expires_at<?",
      Timestamp.from(Instant.now())
    );
    db.update(
      "INSERT INTO sessions(token_hash,member_id,expires_at) VALUES(?,?,?)",
      digest(token),
      matches.getFirst().get("ID"),
      Timestamp.from(expires)
    );
    return Map.of(
      "token",
      token,
      "expiresAt",
      expires.toString(),
      "identity",
      authenticate(token),
      "mustChangePassword",
      passwordChangeRequired(matches.getFirst().get("ID").toString())
    );
  }

  public Identity authenticate(String token) {
    if (token == null || token.length() > 256) throw new ApiException(
      401,
      "请先登录"
    );
    var results = db.query(
      """
      SELECT m.id,m.tenant_id,t.name,m.username,m.display_name,m.role
      FROM sessions s JOIN members m ON m.id=s.member_id JOIN tenants t ON t.id=m.tenant_id
      WHERE s.token_hash=? AND s.expires_at>? AND m.enabled=TRUE AND t.enabled=TRUE
      """,
      (rs, n) ->
        new Identity(
          rs.getString(1),
          rs.getString(2),
          rs.getString(3),
          rs.getString(4),
          rs.getString(5),
          rs.getString(6)
        ),
      digest(token),
      Timestamp.from(Instant.now())
    );
    if (results.isEmpty()) throw new ApiException(
      401,
      "登录已过期或账号/租户已停用"
    );
    return results.getFirst();
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
