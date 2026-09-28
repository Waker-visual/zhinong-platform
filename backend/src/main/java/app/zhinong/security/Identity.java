package app.zhinong.security;

import app.zhinong.api.ApiException;
import java.util.Arrays;

public record Identity(
  String memberId,
  String tenantId,
  String tenantName,
  String username,
  String displayName,
  String role
) {
  private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

  public static Identity current() {
    Identity identity = CURRENT.get();
    if (identity == null) throw new ApiException(401, "请先登录");
    return identity;
  }

  static void set(Identity identity) {
    CURRENT.set(identity);
  }

  static void clear() {
    CURRENT.remove();
  }

  public static String tenant() {
    Identity identity = current();
    if ("PLATFORM_ADMIN".equals(identity.role())) {
      throw new ApiException(403, "平台账号不能访问租户经营数据，请使用租户账号");
    }
    return identity.tenantId();
  }

  public static void require(String... roles) {
    if (!Arrays.asList(roles).contains(current().role())) {
      throw new ApiException(403, "当前角色没有操作权限");
    }
  }
}
