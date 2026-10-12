package app.zhinong.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import app.zhinong.security.Identity;
import app.zhinong.workspace.MapTileService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

class MapTileControllerTest {
  private final MapTileService tiles = mock(MapTileService.class);
  private final MapTileController controller = new MapTileController(tiles);
  private final Identity tenant = new Identity("member", "farm", "Demo", "viewer", "Viewer", "VIEWER");

  @Test void authenticationAndPlatformRestrictionHappenBeforeDispatch() {
    assertEquals(401, assertThrows(ApiException.class,
      () -> controller.get("STREET", 0, 0, 0, null)).status());
    Identity.runAs(new Identity("platform", null, null, "platform", "Platform", "PLATFORM_ADMIN"),
      () -> assertEquals(403, assertThrows(ApiException.class,
        () -> controller.get("STREET", 0, 0, 0, null)).status()));
    verifyNoInteractions(tiles);
  }

  @Test void pendingImageryReturnsAsynchronouslyAndDoesNotRequireThreadIdentityWhenCompleting() {
    var future = new CompletableFuture<MapTileService.Tile>();
    when(tiles.getAsync("STREET", 0, 0, 0, null)).thenReturn(future);
    var result = new AtomicReference<DeferredResult<ResponseEntity<?>>>();
    Identity.runAs(tenant, () -> result.set(controller.get("STREET", 0, 0, 0, null)));
    assertFalse(result.get().hasResult());
    assertThrows(ApiException.class, Identity::current);
    future.complete(new MapTileService.Tile(new byte[] {1, 2}, "image/png", 0, null, null));
    var response = (ResponseEntity<?>) result.get().getResult();
    assertEquals(200, response.getStatusCode().value());
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertArrayEquals(new byte[] {1, 2}, (byte[]) response.getBody());
  }

  @Test void overloadRetainsClearStatusAndRetryDelay() {
    when(tiles.getAsync("STREET", 0, 0, 0, null)).thenReturn(
      CompletableFuture.failedFuture(new ApiException(503, "底图请求较多，请稍后重试")));
    Identity.runAs(tenant, () -> {
      var response = (ResponseEntity<?>) controller.get("STREET", 0, 0, 0, null).getResult();
      assertEquals(503, response.getStatusCode().value());
      assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    });
  }
}
