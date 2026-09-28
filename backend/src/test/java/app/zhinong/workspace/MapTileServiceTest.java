package app.zhinong.workspace;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapTileServiceTest {

  @TempDir
  Path directory;

  final ObjectMapper json = new ObjectMapper();
  final Clock clock = Clock.fixed(
    Instant.parse("2026-09-20T00:00:00Z"),
    ZoneOffset.UTC
  );
  final byte[] image = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6x0AAAAASUVORK5CYII="
  );

  MapTileService.Remote ok(Map<String, String> headers) {
    var h = new HashMap<>(headers);
    h.put("content-type", "image/png");
    return new MapTileService.Remote(200, image, h);
  }

  @Test
  void storedTileSurvivesServiceRestartWithoutNetwork() {
    var calls = new AtomicInteger();
    var service = new MapTileService(
      directory,
      json,
      (url, headers) -> {
        calls.incrementAndGet();
        return ok(Map.of("cache-control", "max-age=86400"));
      },
      clock
    );
    assertArrayEquals(
      image,
      service
        .get("SATELLITE", 16, 56930, 22981, "http://127.0.0.1:9175/")
        .bytes()
    );
    var restored = new MapTileService(
      directory,
      json,
      (url, headers) -> {
        fail("Fresh cache must not fetch");
        return null;
      },
      clock
    );
    assertArrayEquals(
      image,
      restored.get("SATELLITE", 16, 56930, 22981, null).bytes()
    );
    assertEquals(1, calls.get());
  }

  @Test
  void satelliteFallbackAndReferrerAreBounded() {
    var calls = new AtomicInteger();
    var service = new MapTileService(
      directory,
      json,
      (url, headers) -> {
        assertEquals("http://127.0.0.1:9175/", headers.get("Referer"));
        assertFalse(headers.containsKey("Authorization"));
        if (calls.incrementAndGet() == 1) throw new java.io.IOException(
          "timeout"
        );
        assertEquals("server.arcgisonline.com", url.getHost());
        return ok(Map.of("cache-control", "max-age=20"));
      },
      clock
    );
    assertArrayEquals(
      image,
      service
        .get("SATELLITE", 16, 56930, 22981, "http://127.0.0.1:9175/")
        .bytes()
    );
    assertEquals(2, calls.get());
  }

  @Test
  void invalidCoordinatesAndProvidersNeverReachNetwork() {
    var service = new MapTileService(
      directory,
      json,
      (u, h) -> {
        fail("Invalid tile reached provider");
        return null;
      },
      clock
    );
    assertThrows(ApiException.class, () ->
      service.get("https://localhost", 16, 1, 1, null)
    );
    assertThrows(ApiException.class, () ->
      service.get("STREET", 20, 1, 1, null)
    );
    assertThrows(ApiException.class, () ->
      service.get("STREET", 2, 4, 1, null)
    );
    assertThrows(ApiException.class, () ->
      service.get("STREET", 2, 1, -1, null)
    );
  }

  @Test
  void expiredCacheRevalidatesAndNoStoreIsNotWritten() throws Exception {
    var first = new MapTileService(
      directory,
      json,
      (u, h) -> ok(Map.of("cache-control", "max-age=1", "etag", "test-tag")),
      clock
    );
    first.get("STREET", 1, 1, 1, null);
    var later = new MapTileService(
      directory,
      json,
      (u, h) -> {
        assertEquals("test-tag", h.get("If-None-Match"));
        return new MapTileService.Remote(
          304,
          new byte[0],
          Map.of("cache-control", "max-age=600")
        );
      },
      Clock.offset(clock, Duration.ofSeconds(2))
    );
    assertArrayEquals(image, later.get("STREET", 1, 1, 1, null).bytes());
    var noStore = new MapTileService(
      directory,
      json,
      (u, h) -> ok(Map.of("cache-control", "no-store")),
      clock
    );
    noStore.get("STREET", 2, 1, 1, null);
    assertFalse(Files.exists(directory.resolve("STREET-2-1-1.json")));
  }

  @Test
  void badContentAndOutageFailClearlyWithoutCaching() throws Exception {
    var service = new MapTileService(
      directory,
      json,
      (u, h) ->
        new MapTileService.Remote(
          200,
          "<html>Blocked</html>".getBytes(),
          Map.of("content-type", "text/html")
        ),
      clock
    );
    var e = assertThrows(ApiException.class, () ->
      service.get("SATELLITE", 2, 1, 1, null)
    );
    assertEquals(502, e.status());
    assertFalse(Files.exists(directory.resolve("SATELLITE-2-1-1.json")));
    var rateLimit = new MapTileService(
      directory,
      json,
      (u, h) -> new MapTileService.Remote(429, new byte[0], Map.of()),
      clock
    );
    assertEquals(
      503,
      assertThrows(ApiException.class, () ->
        rateLimit.get("STREET", 2, 1, 1, null)
      ).status()
    );
  }
}
