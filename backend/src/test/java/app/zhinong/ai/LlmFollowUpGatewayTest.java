package app.zhinong.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LlmFollowUpGatewayTest {
  private final ObjectMapper json = new ObjectMapper();
  private static final List<Map<String, Object>> MESSAGES = List.of(Map.of("role", "user", "content", "追问资料"));

  @Test void optionalGenerationIsShortToolFreeAndNeverRetriesAgainstLocalFallback() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var request = new AtomicReference<JsonNode>();
    var status = new AtomicInteger(200); var fallbackCalls = new AtomicInteger();
    server.createContext("/chat/completions", exchange -> {
      request.set(json.readTree(exchange.getRequestBody()));
      byte[] response = "{\"choices\":[{\"message\":{\"content\":\"[\\\"怎样核查墒情？\\\",\\\"如何复核记录？\\\"]\"}}]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), response.length); exchange.getResponseBody().write(response); exchange.close();
    });
    server.createContext("/local/api/chat", exchange -> { fallbackCalls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
    server.start();
    try {
      var gateway = configured(server); gateway.fallbackUrl = gateway.url + "/local";
      assertTrue(gateway.followUpQuestions(MESSAGES).orElseThrow().startsWith("["));
      assertEquals(320, request.get().path("max_tokens").asInt());
      assertFalse(request.get().has("tools")); assertFalse(request.get().has("tool_choice"));
      status.set(503); assertTrue(gateway.followUpQuestions(MESSAGES).isEmpty());
      assertEquals(0, fallbackCalls.get());
    } finally { server.stop(0); }
  }

  @Test void auxiliaryRequestsLeaveARealGatewaySlotForMainConversation() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(executor);
      var entered = new CountDownLatch(2); var firstEntered = new CountDownLatch(1); var release = new CountDownLatch(1); var requests = new AtomicInteger();
      server.createContext("/chat/completions", exchange -> {
        JsonNode body = json.readTree(exchange.getRequestBody()); requests.incrementAndGet();
        if (body.path("max_tokens").asInt() == 320) {
          entered.countDown(); firstEntered.countDown();
          try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        byte[] response = "{\"choices\":[{\"message\":{\"content\":\"测试回答\"}}]}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
      }); server.start();
      try {
        var gateway = configured(server);
        var first = executor.submit(() -> gateway.followUpQuestions(MESSAGES));
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        var second = executor.submit(() -> gateway.followUpQuestions(MESSAGES));
        try {
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          assertTrue(gateway.followUpQuestions(MESSAGES).isEmpty());
          assertEquals("测试回答", gateway.complete(MESSAGES, List.of()).orElseThrow().content());
          assertEquals(3, requests.get());
        } finally { release.countDown(); }
        assertTrue(first.get(2, TimeUnit.SECONDS).isPresent()); assertTrue(second.get(2, TimeUnit.SECONDS).isPresent());
      } finally { server.stop(0); }
    }
  }

  private LlmGateway configured(HttpServer server) {
    var gateway = new LlmGateway(); gateway.url = "http://127.0.0.1:" + server.getAddress().getPort();
    gateway.apiKey = "test-placeholder"; gateway.model = "test-model"; gateway.timeoutSeconds = 20; return gateway;
  }
}
