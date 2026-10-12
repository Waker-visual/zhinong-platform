package app.zhinong.workspace;

import static org.junit.jupiter.api.Assertions.*;

import app.zhinong.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import com.sun.net.httpserver.HttpServer;
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
    assertEquals(400, assertThrows(ApiException.class, () ->
      service.get(null, 16, 1, 1, null)).status());
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

  @Test
  void sameTileSharesOneRequestAndSubscriberCancellationDoesNotCancelOthers() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var service = new MapTileService(directory, json, (u, h) -> {
      calls.incrementAndGet();
      entered.countDown();
      assertTrue(release.await(2, TimeUnit.SECONDS));
      return ok(Map.of("cache-control", "max-age=60"));
    }, clock, 1);
    try {
      var first = service.getAsync("SATELLITE", 2, 1, 1, null);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var second = service.getAsync("SATELLITE", 2, 1, 1, null);
      first.cancel(true);
      release.countDown();
      assertArrayEquals(image, second.get(2, TimeUnit.SECONDS).bytes());
      assertEquals(1, calls.get());
    } finally { release.countDown(); service.stop(); }
  }

  @Test
  void saturatedExternalWorkersRejectOtherTilesQuicklyButFreshCacheStillWorks() throws Exception {
    var cached = new MapTileService(directory, json, (u, h) -> ok(Map.of("cache-control", "max-age=60")), clock);
    cached.get("STREET", 2, 0, 0, null);
    cached.stop();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service = new MapTileService(directory, json, (u, h) -> {
      entered.countDown();
      assertTrue(release.await(2, TimeUnit.SECONDS));
      return ok(Map.of());
    }, clock, 1);
    try {
      var active = service.getAsync("STREET", 2, 1, 1, null);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var overloaded = service.getAsync("STREET", 2, 2, 1, null);
      assertTrue(overloaded.isDone(), "Overload must not join an unbounded worker queue");
      var error = assertThrows(CompletionException.class, overloaded::join);
      assertEquals(503, ((ApiException) error.getCause()).status());
      assertArrayEquals(image, service.get("STREET", 2, 0, 0, null).bytes());
      release.countDown();
      assertArrayEquals(image, active.get(2, TimeUnit.SECONDS).bytes());
    } finally { release.countDown(); service.stop(); }
  }

  @Test
  void unrelatedTilesWithSameOldLockHashDoNotBlockOneAnother() throws Exception {
    String[] colliding = null;
    var slots = new HashMap<Integer, String[]>();
    for (int x = 0; x < 16 && colliding == null; x++) for (int y = 0; y < 16; y++) {
      String[] candidate = { String.valueOf(x), String.valueOf(y) };
      int slot = Math.floorMod(("STREET-4-" + x + "-" + y).hashCode(), 64);
      var old = slots.putIfAbsent(slot, candidate);
      if (old != null) { colliding = new String[] {old[0], old[1], candidate[0], candidate[1]}; break; }
    }
    assertNotNull(colliding);
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var service = new MapTileService(directory, json, (u, h) -> {
      entered.countDown();
      assertTrue(release.await(2, TimeUnit.SECONDS));
      return ok(Map.of());
    }, clock, 2);
    try {
      var first = service.getAsync("STREET", 4, Integer.parseInt(colliding[0]), Integer.parseInt(colliding[1]), null);
      var second = service.getAsync("STREET", 4, Integer.parseInt(colliding[2]), Integer.parseInt(colliding[3]), null);
      assertTrue(entered.await(2, TimeUnit.SECONDS), "Unrelated tiles must reach separate upstream slots");
      release.countDown();
      first.get(2, TimeUnit.SECONDS);
      second.get(2, TimeUnit.SECONDS);
    } finally { release.countDown(); service.stop(); }
  }

  @Test
  void failedTileBacksOffBrieflyThenRecovers() throws Exception {
    var now = new java.util.concurrent.atomic.AtomicLong(clock.millis());
    Clock movingClock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    var calls = new AtomicInteger();
    var service = new MapTileService(directory, json, (u, h) -> {
      if (calls.incrementAndGet() == 1) throw new IOException("offline");
      return ok(Map.of());
    }, movingClock);
    try {
      assertThrows(ApiException.class, () -> service.get("STREET", 2, 1, 1, null));
      assertThrows(ApiException.class, () -> service.get("STREET", 2, 1, 1, null));
      assertEquals(1, calls.get());
      now.addAndGet(5001);
      assertArrayEquals(image, service.get("STREET", 2, 1, 1, null).bytes());
      assertEquals(2, calls.get());
    } finally { service.stop(); }
  }

  @Test
  void responseBodyIsRejectedBeforeOversizedAllocation() {
    var body = new MapTileService.LimitedImageBody();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    body.onSubscribe(new Flow.Subscription() {
      public void request(long n) {}
      public void cancel() { cancelled.set(true); }
    });
    body.onNext(List.of(ByteBuffer.wrap(new byte[1024 * 1024]), ByteBuffer.wrap(new byte[1])));
    assertTrue(cancelled.get());
    assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
  }

  @Test
  void smallResponseChunksKeepImageBytesIntact() {
    var body = new MapTileService.LimitedImageBody();
    body.onSubscribe(new Flow.Subscription() {
      public void request(long n) {}
      public void cancel() { fail("Small image must not be cancelled"); }
    });
    body.onNext(List.of(ByteBuffer.wrap(Arrays.copyOfRange(image, 0, 5))));
    body.onNext(List.of(ByteBuffer.wrap(Arrays.copyOfRange(image, 5, image.length))));
    body.onComplete();
    assertArrayEquals(image, body.getBody().toCompletableFuture().join());
  }


  @Test
  void failureCacheHasBoundedCardinality() {
    var calls = new AtomicInteger();
    var service = new MapTileService(directory, json, (u, h) -> {
      calls.incrementAndGet();
      throw new IOException("offline");
    }, clock);
    try {
      for (int x = 0; x < 257; x++) {
        int column = x;
        assertThrows(ApiException.class, () -> service.get("STREET", 9, column, 0, null));
      }
      assertEquals(257, calls.get());
      assertThrows(ApiException.class, () -> service.get("STREET", 9, 0, 0, null));
      assertEquals(258, calls.get(), "Old failure entries must be evicted when cache reaches its bound");
    } finally { service.stop(); }
  }


  @Test
  void stalledResponseBodyTimesOutAndTheSameWorkerCanLoadAnotherTile() throws Exception {
    var headersSent = new CountDownLatch(1);
    var releaseServer = new CountDownLatch(1);
    var requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(serverWorkers);
    server.createContext("/image", exchange -> {
      try (exchange) {
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, image.length);
        if (requests.incrementAndGet() == 1) {
          // Sending a real first byte ensures this is body starvation, not slow headers.
          exchange.getResponseBody().write(image, 0, 1);
          exchange.getResponseBody().flush();
          headersSent.countDown();
          try { releaseServer.await(5, TimeUnit.SECONDS); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        } else exchange.getResponseBody().write(image);
      } catch (IOException disconnected) { /* Timed-out client has cancelled its transport. */ }
    });
    server.start();
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    var fetch = MapTileService.httpFetcher(client, Duration.ofMillis(600));
    URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image");
    var service = new MapTileService(directory, json, (u, h) -> fetch.get(endpoint, h), clock, 1);
    try {
      long start = System.nanoTime();
      var stalled = service.getAsync("STREET", 2, 0, 0, null);
      assertTrue(headersSent.await(2, TimeUnit.SECONDS));
      var timeout = assertThrows(ExecutionException.class, () -> stalled.get(2, TimeUnit.SECONDS));
      assertEquals(502, ((ApiException) timeout.getCause()).status());
      assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500);
      assertEquals(1, releaseServer.getCount(), "Server is still withholding the rest of the body");
      // A completion can race the executor's final hand-off; allow that brief 503 only.
      MapTileService.Tile recovered = null;
      long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (recovered == null && System.nanoTime() < recoveryDeadline) {
        try { recovered = service.get("STREET", 2, 1, 0, null); }
        catch (ApiException busy) {
          assertEquals(503, busy.status());
          Thread.sleep(10);
        }
      }
      assertNotNull(recovered, "The timed-out exchange must release the sole worker");
      assertArrayEquals(image, recovered.bytes());
      assertEquals(2, requests.get());
    } finally {
      releaseServer.countDown();
      service.stop();
      server.stop(0);
      serverWorkers.shutdownNow();
      client.shutdownNow();
    }
  }

}
