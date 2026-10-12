package app.zhinong.api;

import app.zhinong.security.Identity;
import app.zhinong.workspace.MapTileService;
import org.springframework.http.*;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/map-tiles")
public class MapTileController {

  private final MapTileService tiles;

  public MapTileController(MapTileService tiles) {
    this.tiles = tiles;
  }

  @GetMapping("/{provider}/{z}/{x}/{y}")
  public DeferredResult<ResponseEntity<?>> get(
    @PathVariable String provider,
    @PathVariable int z,
    @PathVariable int x,
    @PathVariable int y,
    @RequestHeader(value = "Referer", required = false) String referer
  ) {
    Identity.tenant();
    // Authenticate on the request thread before dispatching public imagery work.
    var future = tiles.getAsync(provider, z, x, y, referer);
    var result = new DeferredResult<ResponseEntity<?>>(27000L,
      failure(503, "底图连接超时，请稍后重试"));
    future.whenComplete((tile, error) -> {
      if (error == null) result.setResult(ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(tile.contentType()))
        .cacheControl(CacheControl.noStore())
        .body(tile.bytes()));
      else {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        result.setResult(cause instanceof ApiException api
          ? failure(api.status(), api.getMessage())
          : failure(502, "在线底图暂时无法连接，请稍后重试"));
      }
    });
    return result;
  }

  private static ResponseEntity<?> failure(int status, String message) {
    return ResponseEntity.status(status)
      .header(HttpHeaders.RETRY_AFTER, "5")
      .cacheControl(CacheControl.noStore())
      .body(Map.of("message", message));
  }
}
