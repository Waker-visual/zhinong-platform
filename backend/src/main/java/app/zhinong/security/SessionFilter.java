package app.zhinong.security;

import app.zhinong.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionFilter extends OncePerRequestFilter {

  private final AuthService auth;
  private final ObjectMapper json;

  public SessionFilter(AuthService auth, ObjectMapper json) {
    this.auth = auth;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain chain
  ) throws ServletException, IOException {
    Identity.clear();
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "same-origin");
    response.setHeader("X-Frame-Options", "DENY");
    try {
      String path = request.getRequestURI();
      if (path.startsWith("/api/")) {
        response.setHeader("Cache-Control", "no-store");
        boolean deviceIngest =
          path.equals("/api/ingest/telemetry") &&
          request.getMethod().equals("POST");
        if (!path.equals("/api/auth/login") && !deviceIngest) {
          String header = request.getHeader("Authorization");
          if (
            header == null || !header.startsWith("Bearer ")
          ) throw new ApiException(401, "请先登录");
          Identity identity = auth.authenticate(header.substring(7));
          for (String name : new String[] { "tenantId", "X-Tenant-Id" }) {
            String selected = request.getHeader(name);
            if (selected != null && !selected.equals(identity.tenantId())) {
              throw new ApiException(403, "不能指定其他租户");
            }
          }
          Identity.set(identity);
          if (
            auth.passwordChangeRequired(identity.memberId()) &&
            !java.util.Set.of(
              "/api/account",
              "/api/account/password",
              "/api/auth/me",
              "/api/auth/logout"
            ).contains(path)
          ) {
            throw new ApiException(
              403,
              "管理员已重置密码，请先在账号安全中设置新密码"
            );
          }
        }
      }
      chain.doFilter(request, response);
    } catch (ApiException e) {
      response.setStatus(e.status());
      response.setContentType("application/json;charset=UTF-8");
      json.writeValue(response.getWriter(), Map.of("message", e.getMessage()));
    } finally {
      Identity.clear();
    }
  }
}
