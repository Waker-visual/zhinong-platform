package app.zhinong.ai;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
      assertThrows(app.zhinong.api.ApiException.class,()->gateway.saveConfig("http://example.invalid",null,null));
      assertThrows(app.zhinong.api.ApiException.class,()->gateway.saveConfig("https://example.invalid",null,null));
    } finally {server.stop(0);}
  }
}
