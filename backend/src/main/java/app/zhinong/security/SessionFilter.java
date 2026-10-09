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
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

@Component
public class SessionFilter extends OncePerRequestFilter {

  private final AuthService auth;
  private final ObjectMapper json;
  private final ApplicationAvailability availability;

  public SessionFilter(AuthService auth, ObjectMapper json, ApplicationAvailability availability) {
    this.auth = auth;
    this.json = json;
    this.availability = availability;
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
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        if (path.equals("/api/health") && request.getMethod().equals("GET")) {
          response.setStatus(ready ? 200 : 503);
          response.setContentType("application/json;charset=UTF-8");
          json.writeValue(response.getWriter(), Map.of("ready", ready));
          return;
        }
        if (!ready) throw new ApiException(503, "项目正在初始化或停止，请稍候重试");
        boolean deviceIngest =
          (path.equals("/api/ingest/telemetry") || path.equals("/api/ingest/commands/poll")
            || path.matches("/api/ingest/commands/[A-Za-z0-9-]+/receipt")) &&
          request.getMethod().equals("POST");
        if (deviceIngest && (request.getHeader("X-Tenant-Id") != null || request.getHeader("tenantId") != null
            || request.getParameter("tenantId") != null)) {
          throw new ApiException(403, "设备租户由接入凭据决定，不能通过请求指定");
        }
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
