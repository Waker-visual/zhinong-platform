package app.zhinong.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class RuntimeSchedulingConfigurationTest {

  @Test
  void blockedBusinessTasksDoNotDelayTheIrrigationSafetyTimer() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var context = new AnnotationConfigApplicationContext(RuntimeSchedulingConfiguration.class)) {
      var ordinary = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
      var safety = context.getBean("irrigationSafetyScheduler", ThreadPoolTaskScheduler.class);
      try {
        for (int n = 0; n < 2; n++) ordinary.execute(() -> {
          entered.countDown();
          try { release.await(); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS), "Both ordinary workers must be blocked");
        var fired = new CompletableFuture<String>();
        safety.schedule(() -> fired.complete(Thread.currentThread().getName()), Instant.now());
        assertTrue(fired.get(2, TimeUnit.SECONDS).startsWith("irrigation-safety-"));
      } finally { release.countDown(); }
    }
  }

  @Test
  void unqualifiedScheduledWorkDoesNotRunOnTheAiDeadlineExecutor() throws Exception {
    try (var context = new AnnotationConfigApplicationContext(
        RuntimeSchedulingConfiguration.class, ScheduledProbeConfiguration.class)) {
      var probe = context.getBean(DefaultProbe.class);
      assertTrue(probe.thread.get(2, TimeUnit.SECONDS).startsWith("farm-background-"));
    }
  }

  @Test
  void shutdownInterruptsActiveTimersAndCancelsDelayedWork() throws Exception {
    var context = new AnnotationConfigApplicationContext(RuntimeSchedulingConfiguration.class);
    // Production permits a graceful drain; shorten that phase only for this blocked-task test.
    context.getBean("lifecycleProcessor", DefaultLifecycleProcessor.class).setTimeoutPerShutdownPhase(100);
    var entered = new CountDownLatch(2);
    var interrupted = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var ordinary = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
    var safety = context.getBean("irrigationSafetyScheduler", ThreadPoolTaskScheduler.class);
    Runnable block = () -> {
      entered.countDown();
      try { release.await(); }
      catch (InterruptedException stopped) { interrupted.countDown(); Thread.currentThread().interrupt(); }
    };
    try {
      ordinary.execute(block);
      safety.execute(block);
      var delayed = safety.schedule(() -> fail("Shutdown must not run a future timer"), Instant.now().plusSeconds(60));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      context.close();
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertTrue(delayed.isCancelled());
      assertTrue(ordinary.getScheduledThreadPoolExecutor().isTerminated());
      assertTrue(safety.getScheduledThreadPoolExecutor().isTerminated());
    } finally { release.countDown(); context.close(); }
  }

  @Configuration
  @EnableScheduling
  static class ScheduledProbeConfiguration {
    @Bean DefaultProbe defaultProbe() { return new DefaultProbe(); }
    @Bean(destroyMethod = "shutdownNow") ScheduledExecutorService aiStreamDeadlineScheduler() {
      return Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "ai-test-deadline"));
    }
  }

  static class DefaultProbe {
    final CompletableFuture<String> thread = new CompletableFuture<>();
    @Scheduled(fixedDelay = 3600000)
    public void tick() { thread.complete(Thread.currentThread().getName()); }
  }
}
