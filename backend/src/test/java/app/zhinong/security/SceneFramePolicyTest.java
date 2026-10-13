package app.zhinong.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SceneFramePolicyTest {
  @Test
  void onlySceneDocumentCanBeEmbeddedByTheSameOrigin() throws Exception {
    var filter = new SessionFilter(mock(AuthService.class), new ObjectMapper(), mock(ApplicationAvailability.class));
    for (String path : new String[] { "/", "/index.html", "/farm-scene/index.html", "/farm-scene/other.html" }) {
      var request = new MockHttpServletRequest("GET", path);
      request.setServletPath(path);
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      assertEquals(path.equals("/farm-scene/index.html") ? "SAMEORIGIN" : "DENY", response.getHeader("X-Frame-Options"));
    }
  }
}
