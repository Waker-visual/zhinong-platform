package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Fetches only visible tiles from fixed public providers. No arbitrary URL or bulk download endpoint. */
@Service
public class MapTileService {

  public record Tile(
    byte[] bytes,
    String contentType,
    long expiresAt,
    String etag,
    String modified
  ) {}

  record Remote(int status, byte[] bytes, Map<String, String> headers) {}

  @FunctionalInterface
  interface Fetcher {
    Remote get(URI uri, Map<String, String> headers)
      throws IOException, InterruptedException;
  }

  private final Path directory;
  private final ObjectMapper json;
  private final Fetcher fetcher;
  private final Clock clock;
  private final Object[] locks = new Object[64];
  private static final int MAX_BYTES = 1024 * 1024;
  private static final Pattern MAX_AGE = Pattern.compile(
    "(?:^|,)\\s*max-age=(\\d+)"
  );

  @Autowired
  public MapTileService(Environment env, ObjectMapper json) {
    this(
      Path.of(env.getProperty("FARM_MAP_CACHE_DIR", "../.cache/map-tiles")),
      json,
      httpFetcher(),
      Clock.systemUTC()
    );
  }

  MapTileService(
    Path directory,
    ObjectMapper json,
    Fetcher fetcher,
    Clock clock
  ) {
    this.directory = directory.toAbsolutePath().normalize();
    this.json = json;
    this.fetcher = fetcher;
    this.clock = clock;
    Arrays.setAll(locks, n -> new Object());
  }

  private static Fetcher httpFetcher() {
    var client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(4))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
    return (uri, headers) -> {
      var request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(6))
        .header("User-Agent", "ZhinongPlatform/0.3 LocalFarmMap")
        .header("Accept", "image/png,image/jpeg");
      headers.forEach(request::header);
      var response = client.send(
        request.GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()
      );
      var metadata = new HashMap<String, String>();
      for (String key : List.of(
        "content-type",
        "cache-control",
        "expires",
        "etag",
        "last-modified",
        "age"
      ))
        response
          .headers()
          .firstValue(key)
          .ifPresent(v -> metadata.put(key, v));
      return new Remote(response.statusCode(), response.body(), metadata);
    };
  }

  public Tile get(String provider, int z, int x, int y, String referer) {
    if (
      !Set.of("SATELLITE", "STREET").contains(provider) ||
      z < 0 ||
      z > 19 ||
      x < 0 ||
      y < 0 ||
      x >= (1 << z) ||
      y >= (1 << z)
    ) throw new ApiException(400, "底图类型或瓦片坐标无效");
    String key = provider + "-" + z + "-" + x + "-" + y;
    synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
      Path file = directory.resolve(key + ".json");
      Tile previous = read(file);
      if (
        previous != null && previous.expiresAt() > clock.millis()
      ) return previous;
      var headers = new HashMap<String, String>();
      // Preserve browser referrer without forwarding credentials, tenant IDs, or arbitrary headers.
      if (
        referer != null &&
        referer.length() < 2048 &&
        !referer.contains("\r") &&
        !referer.contains("\n")
      ) headers.put("Referer", referer);
      if (previous != null) {
        if (previous.etag() != null) headers.put(
          "If-None-Match",
          previous.etag()
        );
        if (previous.modified() != null) headers.put(
          "If-Modified-Since",
          previous.modified()
        );
      }
      var sources = provider.equals("SATELLITE")
        ? List.of(
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
              z +
              "/" +
              y +
              "/" +
              x,
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
              z +
              "/" +
              y +
              "/" +
              x
          )
        : List.of(
            "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png"
          );
      for (String source : sources) {
        try {
          Remote response = fetcher.get(
            URI.create(source),
            Map.copyOf(headers)
          );
          if (response.status() == 429) throw new ApiException(
            503,
            "底图服务繁忙，请稍后重试"
          );
          Tile tile;
          if (response.status() == 304 && previous != null) {
            tile = new Tile(
              previous.bytes(),
              previous.contentType(),
              expires(response.headers(), provider),
              response.headers().getOrDefault("etag", previous.etag()),
              response
                .headers()
                .getOrDefault("last-modified", previous.modified())
            );
          } else {
            String type = response
              .headers()
              .getOrDefault("content-type", "")
              .split(";")[0].trim()
              .toLowerCase(Locale.ROOT);
            if (
              response.status() != 200 || !validImage(response.bytes(), type)
            ) continue;
            tile = new Tile(
              response.bytes(),
              type,
              expires(response.headers(), provider),
              response.headers().get("etag"),
              response.headers().get("last-modified")
            );
          }
          if (
            !response
              .headers()
              .getOrDefault("cache-control", "")
              .toLowerCase(Locale.ROOT)
              .contains("no-store")
          ) write(file, tile);
          return tile;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ApiException(503, "底图请求已中断，请重试");
        } catch (IOException | IllegalArgumentException ignored) {
          /* Try only the same provider's configured secondary host. */
        }
      }
      throw new ApiException(
        502,
        "在线底图暂时无法连接，请点击重试，或使用离线布局查看地块与设备"
      );
    }
  }

  private long expires(Map<String, String> headers, String provider) {
    String control = headers
      .getOrDefault("cache-control", "")
      .toLowerCase(Locale.ROOT);
    if (
      control.contains("no-cache") || control.contains("no-store")
    ) return clock.millis();
    var match = MAX_AGE.matcher(control);
    try {
      if (match.find()) return (
        clock.millis() +
        Math.max(
          0,
          Long.parseLong(match.group(1)) -
            Long.parseLong(headers.getOrDefault("age", "0"))
        ) *
        1000
      );
      if (headers.containsKey("expires")) return ZonedDateTime.parse(
        headers.get("expires"),
        DateTimeFormatter.RFC_1123_DATE_TIME
      )
        .toInstant()
        .toEpochMilli();
    } catch (RuntimeException ignored) {}
    return (
      clock.millis() +
      Duration.ofDays(provider.equals("STREET") ? 7 : 1).toMillis()
    );
  }

  private static boolean validImage(byte[] bytes, String type) {
    if (
      bytes == null || bytes.length < 8 || bytes.length > MAX_BYTES
    ) return false;
    return (
      (type.equals("image/png") &&
        bytes[0] == (byte) 137 &&
        bytes[1] == 80 &&
        bytes[2] == 78 &&
        bytes[3] == 71) ||
      (type.equals("image/jpeg") &&
        bytes[0] == (byte) 255 &&
        bytes[1] == (byte) 216 &&
        bytes[2] == (byte) 255)
    );
  }

  private Tile read(Path file) {
    try {
      if (
        !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES * 2L
      ) return null;
      var tile = json.readValue(file.toFile(), Tile.class);
      return validImage(tile.bytes(), tile.contentType()) ? tile : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private void write(Path file, Tile tile) {
    Path temp = null;
    try {
      Files.createDirectories(directory);
      temp = Files.createTempFile(directory, "tile-", ".tmp");
      json.writeValue(temp.toFile(), tile);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      trim();
    } catch (IOException ignored) {
      /* A full/read-only cache must not prevent a successful live tile from displaying. */
    } finally {
      if (temp != null) try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {}
    }
  }

  private synchronized void trim() throws IOException {
    try (var stream = Files.list(directory)) {
      var files = stream
        .filter(p ->
          p
            .getFileName()
            .toString()
            .matches("(SATELLITE|STREET)-\\d+-\\d+-\\d+\\.json")
        )
        .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
        .toList();
      for (int i = 0; i < files.size() - 256; i++) Files.deleteIfExists(
        files.get(i)
      );
    }
  }
}
