package app.zhinong.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AiStreamExecutorConfigTest {
  @Test void closingTheContextInterruptsActiveWorkersAndStopsPendingDeadlines() throws Exception {
    var context = new AnnotationConfigApplicationContext(AiStreamExecutorConfig.class);
    var worker = context.getBean("aiStreamExecutor", ExecutorService.class);
    var deadlines = context.getBean("aiStreamDeadlineScheduler", ScheduledThreadPoolExecutor.class);
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var neverCompletes = new CountDownLatch(1);
    var deadlineRan = new AtomicBoolean();
    try {
      var active = worker.submit(() -> {
        entered.countDown();
        try { neverCompletes.await(); }
        catch (InterruptedException expected) {
          interrupted.countDown();
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      deadlines.schedule(() -> deadlineRan.set(true), 1, TimeUnit.HOURS);
      assertEquals(1, deadlines.getQueue().size());

      // Exercise Spring's actual bean destruction order, including the case where
      // the deadline scheduler stops before the executor that owns active runs.
      context.close();

      assertTrue(interrupted.await(3, TimeUnit.SECONDS), "active AI work must not outlive shutdown with its deadline removed");
      active.get(3, TimeUnit.SECONDS);
      assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
      assertTrue(deadlines.awaitTermination(3, TimeUnit.SECONDS));
      assertTrue(worker.isShutdown());
      assertTrue(deadlines.isShutdown());
      assertTrue(deadlines.getQueue().isEmpty());
      assertFalse(deadlineRan.get());
      assertThrows(RejectedExecutionException.class, () -> worker.submit(() -> {}));
      assertThrows(RejectedExecutionException.class, () -> deadlines.schedule(() -> {}, 1, TimeUnit.SECONDS));
    } finally {
      neverCompletes.countDown();
      context.close();
    }
  }
}
