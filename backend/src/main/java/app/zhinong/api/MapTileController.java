package app.zhinong.api;

import app.zhinong.security.Identity;
import app.zhinong.workspace.MapTileService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/map-tiles")
public class MapTileController {

  private final MapTileService tiles;

  public MapTileController(MapTileService tiles) {
    this.tiles = tiles;
  }

  @GetMapping("/{provider}/{z}/{x}/{y}")
  public ResponseEntity<byte[]> get(
    @PathVariable String provider,
    @PathVariable int z,
    @PathVariable int x,
    @PathVariable int y,
    @RequestHeader(value = "Referer", required = false) String referer
  ) {
    Identity.tenant();
    var tile = tiles.get(provider, z, x, y, referer);
    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType(tile.contentType()))
      .cacheControl(CacheControl.noStore())
      .body(tile.bytes());
  }
}
