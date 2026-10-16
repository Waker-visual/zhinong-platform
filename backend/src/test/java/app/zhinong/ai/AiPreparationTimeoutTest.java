package app.zhinong.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import app.zhinong.api.ApiException;
import app.zhinong.business.FarmClock;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AiPreparationTimeoutTest {
  @Test void preparationUsesOneCumulativeSqlBudgetAndExpiredTransactionsReleaseAdmissionSlots() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:ai-preparation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var db = new JdbcTemplate(source);
    db.setQueryTimeout(10);
    db.execute("CREATE TABLE ai_conversations(id VARCHAR PRIMARY KEY, tenant_id VARCHAR, member_id VARCHAR, farm_id VARCHAR, title_source VARCHAR)");
    db.execute("CREATE TABLE ai_messages(id VARCHAR, tenant_id VARCHAR, conversation_id VARCHAR, request_id VARCHAR, role VARCHAR, content VARCHAR, mode VARCHAR, diagnostic VARCHAR, created_at TIMESTAMP)");
    db.execute("CREATE TABLE ai_message_activities(tenant_id VARCHAR, conversation_id VARCHAR, message_id VARCHAR, activity_id VARCHAR, sequence_no INT, kind VARCHAR, label VARCHAR, status VARCHAR, detail VARCHAR, result_summary VARCHAR, started_at TIMESTAMP, finished_at TIMESTAMP)");
    db.update("INSERT INTO ai_conversations VALUES('chat','tenant','member','farm','auto')");
    var analysis = mock(AgronomyAnalysis.class);
    var gateway = mock(LlmGateway.class);
    var target = new AiConversations(mock(Store.class), db, gateway, analysis, new ObjectMapper(), FarmClock.system());
    // Match Spring's class-based proxy support for package-visible transactional methods.
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource(false)));
    var conversations = (AiConversations) factory.getProxy();
    var executor = mock(ExecutorService.class);
    var deadlines = mock(ScheduledExecutorService.class);
    when(deadlines.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(ScheduledFuture.class));
    var service = new AiStreamService(conversations, gateway, mock(AiStreamTools.class), mock(IrrigationService.class), executor, deadlines,
      FarmClock.system());
    var identity = new Identity("member", "tenant", "演示租户", "operator", "演示用户", "OPERATOR");
    var question = new AiConversations.Question("今日农事", "preparation-test");

    when(analysis.report("farm")).thenAnswer(call -> {
      var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(source);
      assertNotNull(holder);
      assertTrue(holder.hasTimeout(), "prepareStream must run inside its annotated transaction");
      long remaining = holder.getTimeToLiveInMillis();
      assertTrue(remaining > 0 && remaining <= 10_000, "the whole preparation receives a 10-second budget");
      long deadline = holder.getDeadline().getTime();
      assertEquals(1, db.queryForObject("SELECT 1", Integer.class));
      assertEquals(2, db.queryForObject("SELECT 2", Integer.class));
      assertEquals(deadline, holder.getDeadline().getTime(), "individual SQL statements must not reset the cumulative deadline");
      // Deterministically exhaust the shared TTL without a slow/flaky 10-second sleep.
      // JdbcTemplate's next real JDBC call must check that exhausted transaction budget.
      holder.setTimeoutInMillis(0);
      db.queryForObject("SELECT 3", Integer.class);
      fail("expired preparation must not continue to build a model context");
      return null;
    });

    try {
      for (int attempt = 0; attempt < 12; attempt++) {
        assertThrows(TransactionTimedOutException.class,
          () -> Identity.runAs(identity, () -> service.stream("chat", question)));
        assertFalse(TransactionSynchronizationManager.hasResource(source), "failed transaction must unbind its connection");
      }
      verifyNoInteractions(executor);
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ai_messages", Integer.class));
      doReturn(Map.of("weather", List.of(), "sensors", List.of())).when(analysis).report("farm");
      for (int n = 0; n < 8; n++) {
        AtomicReference<SseEmitter> emitter = new AtomicReference<>();
        Identity.runAs(identity, () -> emitter.set(service.stream("chat", question)));
        assertNotNull(emitter.get(), "all eight slots remain usable after repeated preparation timeouts");
      }
      var busy = assertThrows(ApiException.class,
        () -> Identity.runAs(identity, () -> service.stream("chat", question)));
      assertEquals(503, busy.status());
      verify(executor, times(8)).execute(any(Runnable.class));
    } finally {
      db.execute("SHUTDOWN");
    }
  }
}
