package app.zhinong.security;

import app.zhinong.api.ApiException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

@Component
public class SessionFilter extends OncePerRequestFilter {

  private final AuthService auth;
  private final ObjectMapper json;
  private final ApplicationAvailability availability;
  private final boolean demo;

  public SessionFilter(
    AuthService auth,
    ObjectMapper json,
    ApplicationAvailability availability,
    @Value("${farm.demo:false}") boolean demo
  ) {
    this.auth = auth;
    this.json = json;
    this.availability = availability;
    this.demo = demo;
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
    response.setHeader("X-Frame-Options", request.getServletPath().equals("/farm-scene/index.html") ? "SAMEORIGIN" : "DENY");
    try {
      // Use the container's decoded servlet path so encoded endpoint names cannot
      // bypass the same authorization rules that MVC applies after URL decoding.
      String path = request.getServletPath();
      if (path.startsWith("/api/")) {
        response.setHeader("Cache-Control", "no-store");
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        if (path.equals("/api/health") && request.getMethod().equals("GET")) {
          response.setStatus(ready ? 200 : 503);
          response.setContentType("application/json;charset=UTF-8");
          // demo only lets the login page prefill the public demo tenant; it reveals no account data.
          json.writeValue(response.getWriter(), Map.of("ready", ready, "demo", demo));
          return;
        }
        if (!ready) throw new ApiException(503, "项目正在初始化或停止，请稍候重试");
        boolean deviceIngest =
          (path.equals("/api/ingest/telemetry") || path.equals("/api/ingest/commands/poll")
            || path.equals("/api/ingest/smart-farm") || path.equals("/api/ingest/smart-farm/commands/poll")
            || path.matches("/api/ingest/smart-farm/commands/[A-Za-z0-9-]+/receipt")
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
          AuthService.Session session = auth.session(header.substring(7));
          Identity identity = session.identity();
          for (String name : new String[] { "tenantId", "X-Tenant-Id" }) {
            String selected = request.getHeader(name);
            if (selected != null && !selected.equals(identity.tenantId())) {
              throw new ApiException(403, "不能指定其他租户");
            }
          }
          Identity.set(identity);
          // Operational metrics are process-wide, so tenant accounts must not read them.
          // Only health/metrics endpoints are exposed; environment/configuration dumps stay disabled.
          if (path.equals("/api/ops") || path.startsWith("/api/ops/")) {
            Identity.require("PLATFORM_ADMIN");
          }
          if (
            session.mustChangePassword() &&
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
    } catch (DataAccessResourceFailureException | TransientDataAccessException e) {
      // Authentication also borrows a database connection before MVC advice can run.
      response.setStatus(503);
      response.setHeader("Retry-After", "2");
      response.setContentType("application/json;charset=UTF-8");
      json.writeValue(response.getWriter(), Map.of("message", "数据服务暂时繁忙，请稍后重试"));
    } finally {
      Identity.clear();
    }
  }
}
