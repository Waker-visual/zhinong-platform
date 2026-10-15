package app.zhinong.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import app.zhinong.api.ApiException;
import app.zhinong.security.Identity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AiStreamServiceTest {
  private static final Identity DEMO = new Identity("member", "tenant", "演示租户", "operator", "演示用户", "OPERATOR");
  private static final AiConversations.Question QUESTION = new AiConversations.Question("今日农事", "test-request");
  private static AiConversations.PreparedRun replay() {
    return new AiConversations.PreparedRun("tenant", "farm", null, null, null, true,
      "message", "已保存的回答", "rule", "NOT_CONFIGURED", List.of(), false, null);
  }

  private static final class QueuedExecutor extends AbstractExecutorService {
    final List<Runnable> jobs = new ArrayList<>();
    boolean reject;
    @Override public void execute(Runnable command) {
      if (reject) throw new java.util.concurrent.RejectedExecutionException();
      jobs.add(command);
    }
    @Override public void shutdown() { reject = true; }
    @Override public List<Runnable> shutdownNow() { reject = true; return List.copyOf(jobs); }
    @Override public boolean isShutdown() { return reject; }
    @Override public boolean isTerminated() { return reject; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return reject; }
  }

  private static final class Fixture {
    final AiConversations conversations = mock(AiConversations.class);
    final QueuedExecutor executor = new QueuedExecutor();
    final ScheduledExecutorService deadlines = mock(ScheduledExecutorService.class);
    final List<Runnable> expirations = new ArrayList<>();
    final AiStreamService service;
    Fixture() { this(65_000); }
    Fixture(long budgetMillis) {
      when(conversations.prepareStream(anyString(), any())).thenReturn(replay());
      when(deadlines.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
        expirations.add(call.getArgument(0));
        return mock(ScheduledFuture.class);
      });
      service = new AiStreamService(conversations, mock(LlmGateway.class), mock(AiStreamTools.class),
        mock(IrrigationService.class), executor, deadlines, budgetMillis);
    }
    SseEmitter open() {
      AtomicReference<SseEmitter> emitter = new AtomicReference<>();
      Identity.runAs(DEMO, () -> emitter.set(service.stream("conversation", QUESTION)));
      return emitter.get();
    }
  }

  @Test void saturationRejectsBeforeBuildingContextAndACompletedRunReturnsItsSlot() {
    Fixture f = new Fixture();
    for (int n = 0; n < 8; n++) assertNotNull(f.open());
    ApiException busy = assertThrows(ApiException.class, f::open);
    assertEquals(503, busy.status());
    verify(f.conversations, times(8)).prepareStream(anyString(), any());
    f.executor.jobs.getFirst().run();
    assertNotNull(f.open());
    verify(f.conversations, times(9)).prepareStream(anyString(), any());
  }

  @Test void authorizationOrPreparationFailuresNeverLeakAdmissionSlots() {
    Fixture f = new Fixture();
    when(f.conversations.prepareStream(anyString(), any())).thenThrow(new ApiException(404, "记录不存在"));
    for (int n = 0; n < 12; n++) assertEquals(404, assertThrows(ApiException.class, f::open).status());
    doReturn(replay()).when(f.conversations).prepareStream(anyString(), any());
    for (int n = 0; n < 8; n++) assertNotNull(f.open());
    assertEquals(503, assertThrows(ApiException.class, f::open).status());
  }

  @Test void executorRejectionReturns503AndReleasesThePreparedRun() {
    Fixture f = new Fixture();
    f.executor.reject = true;
    for (int n = 0; n < 12; n++) assertEquals(503, assertThrows(ApiException.class, f::open).status());
    f.executor.reject = false;
    for (int n = 0; n < 8; n++) assertNotNull(f.open());
    verify(f.conversations, times(20)).prepareStream(anyString(), any());
    assertEquals(503, assertThrows(ApiException.class, f::open).status());
  }

  @Test void deadlineBeforeTaskStartCancelsWorkAndReturnsEachSlotExactlyOnce() {
    Fixture f = new Fixture();
    for (int n = 0; n < 8; n++) {
      SseEmitter emitter = f.open();
      assertTrue(emitter.getTimeout() > 0 && emitter.getTimeout() <= 65_000);
    }
    List.copyOf(f.expirations).forEach(Runnable::run);
    assertTrue(f.executor.jobs.stream().allMatch(job -> ((Future<?>) job).isCancelled()));
    // An executor may still invoke an already-cancelled task. It must not release twice.
    List.copyOf(f.executor.jobs).forEach(Runnable::run);
    for (int n = 0; n < 8; n++) assertNotNull(f.open());
    assertEquals(503, assertThrows(ApiException.class, f::open).status());
  }

  @Test void preparationConsumesTheSameOverallTimeBudget() throws Exception {
    Fixture f = new Fixture(30);
    when(f.conversations.prepareStream(anyString(), any())).thenAnswer(call -> { Thread.sleep(80); return replay(); });
    ApiException timeout = assertThrows(ApiException.class, f::open);
    assertEquals(503, timeout.status());
    assertTrue(timeout.getMessage().contains("超时"));
    assertEquals(0, f.executor.jobs.size());
    assertEquals(0, f.expirations.size());
  }

  @Test void cancellationKeepsTheSlotUntilTheRunningWorkerActuallyUnwinds() throws Exception {
    CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1), mayExit = new CountDownLatch(1);
    AtomicInteger released = new AtomicInteger();
    AiStreamService.StreamTask task = new AiStreamService.StreamTask(() -> {
      started.countDown();
      boolean stopped = false;
      while (!stopped) {
        try { mayExit.await(); stopped = true; }
        catch (InterruptedException expected) { interrupted.countDown(); }
      }
    }, released::incrementAndGet);
    Thread worker = Thread.ofVirtual().start(task);
    try {
      assertTrue(started.await(3, TimeUnit.SECONDS));
      task.cancel(true);
      assertTrue(interrupted.await(3, TimeUnit.SECONDS));
      assertEquals(0, released.get(), "a cancelled Future is not proof that the worker has stopped");
    } finally {
      mayExit.countDown(); worker.join(3000);
    }
    assertFalse(worker.isAlive());
    assertEquals(1, released.get());
  }

  @Test void ordinaryCompletionDoesNotInterruptTheFinishingWorker() {
    AtomicReference<Runnable> completion = new AtomicReference<>();
    AtomicBoolean interruptedDuringCompletion = new AtomicBoolean();
    try (var emitters = mockConstruction(SseEmitter.class, (emitter, context) -> {
      doAnswer(call -> { completion.set(call.getArgument(0)); return null; }).when(emitter).onCompletion(any(Runnable.class));
      doAnswer(call -> {
        completion.get().run();
        interruptedDuringCompletion.set(Thread.currentThread().isInterrupted());
        return null;
      }).when(emitter).complete();
    })) {
      Fixture f = new Fixture();
      f.open();
      f.executor.jobs.getFirst().run();
      assertFalse(interruptedDuringCompletion.get());
      assertFalse(((Future<?>) f.executor.jobs.getFirst()).isCancelled());
      assertEquals(1, emitters.constructed().size());
    } finally { Thread.interrupted(); }
  }

  @Test void clientDisconnectCancelsQueuedWorkAndMakesTheSlotReusable() {
    List<Runnable> completions = new ArrayList<>();
    try (var emitters = mockConstruction(SseEmitter.class, (emitter, context) -> {
      doAnswer(call -> { completions.add(call.getArgument(0)); return null; }).when(emitter).onCompletion(any(Runnable.class));
    })) {
      Fixture f = new Fixture();
      for (int n = 0; n < 8; n++) f.open();
      completions.getFirst().run();
      assertTrue(((Future<?>) f.executor.jobs.getFirst()).isCancelled());
      assertNotNull(f.open());
      assertEquals(503, assertThrows(ApiException.class, f::open).status());
    }
  }
}
