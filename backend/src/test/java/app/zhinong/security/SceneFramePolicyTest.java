package app.zhinong.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SceneFramePolicyTest {
  @Test
  void onlySceneDocumentCanBeEmbeddedByTheSameOrigin() throws Exception {
    var filter = new SessionFilter(mock(AuthService.class), new ObjectMapper(), mock(ApplicationAvailability.class), false);
    for (String path : new String[] { "/", "/index.html", "/farm-scene/index.html", "/farm-scene/other.html" }) {
      var request = new MockHttpServletRequest("GET", path);
      request.setServletPath(path);
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      assertEquals(path.equals("/farm-scene/index.html") ? "SAMEORIGIN" : "DENY", response.getHeader("X-Frame-Options"));
    }
  }

  @Test
  void healthReportsDemoModeOnlyWhenConfigured() throws Exception {
    var availability = mock(ApplicationAvailability.class);
    when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
    for (boolean demo : new boolean[] { false, true }) {
      var filter = new SessionFilter(mock(AuthService.class), new ObjectMapper(), availability, demo);
      var request = new MockHttpServletRequest("GET", "/api/health");
      request.setServletPath("/api/health");
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      var body = new ObjectMapper().readTree(response.getContentAsString());
      assertEquals(200, response.getStatus());
      assertEquals(demo, body.path("demo").asBoolean());
    }
  }
}
