package app.zhinong.ai;
import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.api.ApiException;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
class LlmGatewayTest {
  @Test void chatOmitsEmptyToolsAndReportsOnlyRedactedFailureCodes() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var body=new AtomicReference<String>(); var code=new java.util.concurrent.atomic.AtomicInteger(200);
    server.createContext("/chat/completions",exchange->{
      body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      byte[] response=(code.get()==200?"{\"choices\":[{\"message\":{\"content\":\"测试回答\"}}]}":"{\"error\":{\"message\":\"private-upstream-error\"}}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(code.get(),response.length);exchange.getResponseBody().write(response);exchange.close();
    });server.start();
    try {
      var gateway=new LlmGateway();gateway.url="http://127.0.0.1:"+server.getAddress().getPort();gateway.apiKey="test-placeholder";gateway.model="test-model";gateway.timeoutSeconds=5;
      assertEquals("测试回答",gateway.chat("测试","问题").orElseThrow());
      assertFalse(new ObjectMapper().readTree(body.get()).has("tools"));
      assertEquals("OK",gateway.diagnostic());
      code.set(402);assertTrue(gateway.chat("测试","问题").isEmpty());assertEquals("BALANCE_REQUIRED",gateway.diagnostic());
      code.set(401);assertTrue(gateway.chat("测试","问题").isEmpty());assertEquals("AUTH_FAILED",gateway.diagnostic());
      assertThrows(app.zhinong.api.ApiException.class,()->gateway.saveConfig("http://example.invalid",null,null,false));
      assertThrows(app.zhinong.api.ApiException.class,()->gateway.saveConfig("https://example.invalid",null,null,false));
    } finally {server.stop(0);}
  }

  @Test void saveConfigKeepsKeyWhenBlankAndClearsOnlyWhenRequested() throws Exception {
    var gateway = new LlmGateway();
    gateway.url = "https://example.invalid";
    gateway.apiKey = "sk-test-placeholder-key";
    gateway.model = "test-model";
    gateway.configPath = Files.createTempDirectory("llm-gateway-test").resolve("llm.properties");

    // 留空密钥：保持不变
    gateway.saveConfig(null, null, null, false);
    assertTrue(gateway.apiKeySet());
    assertEquals("test-model", gateway.model());

    // 显式清除：忽略同时传入的 apiKey
    gateway.saveConfig(null, "sk-should-be-ignored-when-clearing", null, true);
    assertFalse(gateway.apiKeySet());

    // 密钥已清空后更换地址必须重新提供密钥
    assertThrows(ApiException.class, () -> gateway.saveConfig("https://changed.invalid", null, null, false));
    gateway.saveConfig("https://changed.invalid", "sk-test-placeholder-key-2", null, false);
    assertEquals("https://changed.invalid", gateway.url());
    assertTrue(gateway.apiKeySet());

    // 持久化文件可被重新加载还原
    var reloaded = new LlmGateway();
    reloaded.configPath = gateway.configPath;
    reloaded.fileEnabled = true;
    reloaded.loadFileConfig();
    assertEquals("https://changed.invalid", reloaded.url());
    assertTrue(reloaded.apiKeySet());
  }

  @Test void modelOptionsValidateLengthCountAndDuplicatesThenPersist() throws Exception {
    var gateway = new LlmGateway();
    gateway.configPath = Files.createTempDirectory("llm-gateway-test").resolve("llm.properties");

    var merged = gateway.mergeModelOptions(Arrays.asList("model-a", "model-b", "model-a", "  ", null));
    assertEquals(List.of("model-a", "model-b"), merged);

    assertThrows(ApiException.class, () -> gateway.mergeModelOptions(List.of("x".repeat(101))));
    assertEquals(List.of("model-a", "model-b"), gateway.modelOptions());

    List<String> many = new ArrayList<>();
    for (int i = 0; i < 60; i++) many.add("overflow-model-" + i);
    assertThrows(ApiException.class, () -> gateway.mergeModelOptions(many));
    assertEquals(List.of("model-a", "model-b"), gateway.modelOptions());

    gateway.model = "model-a";
    assertThrows(ApiException.class, () -> gateway.removeModelOption("model-a"));
    var afterRemove = gateway.removeModelOption("model-b");
    assertEquals(List.of("model-a"), afterRemove);

    var reloaded = new LlmGateway();
    reloaded.configPath = gateway.configPath;
    reloaded.fileEnabled = true;
    reloaded.loadFileConfig();
    assertEquals(List.of("model-a"), reloaded.modelOptions());
  }

  @Test void fetchModelsReturnsSortedDedupedIdsOrMappedDiagnosticCodes() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var code = new AtomicInteger(200);
    server.createContext("/models", exchange -> {
      byte[] response = (code.get() == 200
        ? "{\"data\":[{\"id\":\"deepseek-v4-pro\"},{\"id\":\"deepseek-flash\"},{\"id\":\"deepseek-flash\"}]}"
        : "{\"error\":{\"message\":\"private-upstream-error\"}}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(code.get(), response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var gateway = new LlmGateway();
      gateway.timeoutSeconds = 5;
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var ok = gateway.fetchModels(base, "sk-test-placeholder-key");
      assertEquals("OK", ok.diagnostic());
      assertEquals(List.of("deepseek-flash", "deepseek-v4-pro"), ok.models());
      assertFalse(ok.toString().contains("sk-test-placeholder-key"));

      code.set(401);
      assertEquals("AUTH_FAILED", gateway.fetchModels(base, "sk-test-placeholder-key").diagnostic());
      code.set(429);
      assertEquals("RATE_LIMITED", gateway.fetchModels(base, "sk-test-placeholder-key").diagnostic());

      assertEquals("NOT_CONFIGURED", gateway.fetchModels(null, null).diagnostic());
    } finally {
      server.stop(0);
    }
  }
}
