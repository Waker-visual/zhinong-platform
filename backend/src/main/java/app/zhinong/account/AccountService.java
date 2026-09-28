package app.zhinong.account;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.AuthService;
import app.zhinong.security.Identity;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

  private final JdbcTemplate db;
  private final AuthService auth;
  private final Store store;

  public AccountService(Store store, AuthService auth) {
    this.store = store;
    this.db = store.db();
    this.auth = auth;
  }

  public Map<String, Object> current() {
    var me = Identity.current();
    return db
      .query(
        """
        SELECT m.id,m.username,m.display_name,m.role,m.enabled,t.code,t.name,
          p.avatar_data,p.theme_mode,p.accent,p.must_change_password
        FROM members m JOIN tenants t ON t.id=m.tenant_id
        LEFT JOIN member_preferences p ON p.member_id=m.id
        WHERE m.id=? AND m.tenant_id=?
        """,
        (rs, n) -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("id", rs.getString(1));
          result.put("username", rs.getString(2));
          result.put("displayName", rs.getString(3));
          result.put("role", rs.getString(4));
          result.put("enabled", rs.getBoolean(5));
          result.put("tenantCode", rs.getString(6));
          result.put("tenantName", rs.getString(7));
          result.put("avatarData", Objects.toString(rs.getString(8), ""));
          result.put("themeMode", Objects.toString(rs.getString(9), "SYSTEM"));
          result.put("accent", Objects.toString(rs.getString(10), "FOREST"));
          result.put("mustChangePassword", rs.getBoolean(11));
          return result;
        },
        me.memberId(),
        me.tenantId()
      )
      .getFirst();
  }

  public Map<String, Object> profile(AccountController.Profile input) {
    var me = Identity.current();
    lockSelf();
    ensurePreferences(me.memberId());
    String avatar = cleanAvatar(input.avatarData());
    db.update(
      "UPDATE members SET display_name=? WHERE tenant_id=? AND id=?",
      input.displayName().strip(),
      me.tenantId(),
      me.memberId()
    );
    db.update(
      "UPDATE member_preferences SET avatar_data=? WHERE member_id=?",
      avatar,
      me.memberId()
    );
    store.audit("UPDATE_ACCOUNT_PROFILE", me.memberId());
    return current();
  }

  public Map<String, Object> appearance(AccountController.Appearance input) {
    var me = Identity.current();
    lockSelf();
    ensurePreferences(me.memberId());
    db.update(
      "UPDATE member_preferences SET theme_mode=?,accent=? WHERE member_id=?",
      input.themeMode(),
      input.accent(),
      me.memberId()
    );
    return current();
  }

  public void changePassword(AccountController.Password input) {
    var me = Identity.current();
    String oldHash = lockSelf();
    if (
      !auth.matchesPassword(input.currentPassword(), oldHash)
    ) throw new ApiException(400, "原密码不正确");
    if (
      auth.matchesPassword(input.newPassword(), oldHash)
    ) throw new ApiException(400, "新密码不能与原密码相同");
    String hash = auth.hashPassword(input.newPassword());
    ensurePreferences(me.memberId());
    db.update(
      "UPDATE members SET password_hash=? WHERE tenant_id=? AND id=?",
      hash,
      me.tenantId(),
      me.memberId()
    );
    db.update(
      "UPDATE member_preferences SET must_change_password=FALSE WHERE member_id=?",
      me.memberId()
    );
    db.update("DELETE FROM sessions WHERE member_id=?", me.memberId());
    store.audit("CHANGE_PASSWORD", me.memberId());
  }

  public void resetPassword(String targetId, AccountController.Password input) {
    Identity.require("ADMIN");
    var me = Identity.current();
    if (me.memberId().equals(targetId)) throw new ApiException(
      400,
      "请在账号安全中修改自己的密码"
    );
    // Require the administrator's current password before changing another member's credential.
    String adminHash = lockSelf();
    if (
      !auth.matchesPassword(input.currentPassword(), adminHash)
    ) throw new ApiException(400, "管理员密码不正确");
    var member = store.lock("members", targetId);
    if (
      !Set.of("OPERATOR", "VIEWER").contains(member.get("ROLE"))
    ) throw new ApiException(403, "此入口仅用于重置本租户普通成员的密码");
    String hash = auth.hashPassword(input.newPassword());
    ensurePreferences(targetId);
    db.update(
      "UPDATE members SET password_hash=? WHERE tenant_id=? AND id=?",
      hash,
      me.tenantId(),
      targetId
    );
    db.update(
      "UPDATE member_preferences SET must_change_password=TRUE WHERE member_id=?",
      targetId
    );
    db.update("DELETE FROM sessions WHERE member_id=?", targetId);
    store.audit("RESET_MEMBER_PASSWORD", targetId);
  }

  private String lockSelf() {
    var me = Identity.current();
    return db.queryForObject(
      "SELECT password_hash FROM members WHERE tenant_id=? AND id=? FOR UPDATE",
      String.class,
      me.tenantId(),
      me.memberId()
    );
  }

  private void ensurePreferences(String id) {
    db.update(
      "INSERT INTO member_preferences(member_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM member_preferences WHERE member_id=?)",
      id,
      id
    );
  }

  private String cleanAvatar(String data) {
    if (data == null || data.isBlank()) return "";
    if (
      data.length() > 400000 ||
      !data.matches("^data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+$")
    ) throw new ApiException(
      400,
      "头像需为 PNG/JPEG 图片，不能使用外部链接或 SVG"
    );
    try {
      byte[] input = Base64.getDecoder().decode(
        data.substring(data.indexOf(',') + 1)
      );
      try (
        var stream = ImageIO.createImageInputStream(
          new ByteArrayInputStream(input)
        )
      ) {
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) throw new IOException("Unsupported image");
        var reader = readers.next();
        try {
          reader.setInput(stream);
          int width = reader.getWidth(0),
            height = reader.getHeight(0);
          if (
            width < 1 || height < 1 || width > 512 || height > 512
          ) throw new IOException("Image too large");
          var image = reader.read(0);
          var output = new ByteArrayOutputStream();
          ImageIO.write(image, "png", output);
          if (output.size() > 300000) throw new IOException("Image too large");
          return (
            "data:image/png;base64," +
            Base64.getEncoder().encodeToString(output.toByteArray())
          );
        } finally {
          reader.dispose();
        }
      }
    } catch (IOException | IllegalArgumentException e) {
      throw new ApiException(400, "头像无法读取，请重新选择图片");
    }
  }
}
