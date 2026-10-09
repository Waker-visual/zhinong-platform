package app.zhinong.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link LlmGateway#stream} against a real (if tiny) local HTTP server, not the Spring
 * test fake used by AiIntegrationTest. This is the only place that exercises the actual OpenAI-compatible
 * SSE parsing and, most importantly, the watchdog that forcibly closes a stalled upstream connection:
 * HttpRequest.timeout() only bounds the time to response HEADERS, so a server that sends headers and a
 * first chunk and then goes silent forever would otherwise hold the concurrency semaphore for as long as
 * the caller's cancelled supplier isn't polled — which only happens between lines, i.e. never, once the
 * underlying read blocks. The fix adds a background watchdog thread that closes the response body when
 * cancelled or the deadline passes, which is what this test actually proves (by asserting the call
 * returns in well under the configured 20s timeout budget once cancelled).
 */
class LlmGatewayStreamTest {

  @Test
  void cancellationClosesAStalledUpstreamStreamPromptlyInsteadOfBlockingForTheFullTimeoutBudget() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      var out = exchange.getResponseBody();
      out.write("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
      // Simulate a stalled upstream: never sends [DONE] and never closes on its own. If the watchdog
      // did not forcibly close the connection, the test's gateway.stream() call would block here for
      // the full configured timeout instead of returning promptly once cancelled() flips to true.
      try {
        for (int i = 0; i < 50; i++) {
          Thread.sleep(200);
          out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
      } catch (Exception clientGoneOrInterrupted) {
        // Expected once the gateway's watchdog closes its side of the connection.
      } finally {
        exchange.close();
      }
    });
    server.start();
    try {
      LlmGateway gateway = new LlmGateway();
      gateway.url = "http://127.0.0.1:" + server.getAddress().getPort();
      gateway.apiKey = "test-key";
      gateway.model = "test-model";
      gateway.timeoutSeconds = 20; // a generous budget; cancellation must still cut the call short well before this

      AtomicBoolean cancelled = new AtomicBoolean(false);
      StringBuilder seen = new StringBuilder();
      Thread canceller = new Thread(() -> {
        try {
          Thread.sleep(300);
        } catch (InterruptedException ignored) {
          /* test thread only */
        }
        cancelled.set(true);
      });
      canceller.start();

      long startedNanos = System.nanoTime();
      LlmGateway.StreamResult result = gateway.stream(
        List.of(Map.of("role", "user", "content", "hi")),
        List.of(),
        seen::append,
        cancelled::get
      );
      long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
      canceller.join();

      assertTrue(
        elapsedMs < 5000,
        "cancellation should force-close the stalled stream quickly instead of waiting out the 20s timeout budget, took " + elapsedMs + "ms"
      );
      assertEquals("你好", seen.toString(), "the text already streamed before cancellation must still have reached the listener");
      assertEquals(LlmGateway.StreamOutcome.INTERRUPTED, result.outcome());
      assertEquals("CANCELLED", result.diagnostic());
    } finally {
      server.stop(0);
    }
  }
}
