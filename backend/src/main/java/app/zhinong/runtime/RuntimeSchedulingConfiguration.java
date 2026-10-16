package app.zhinong.runtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Explicit schedulers keep business timers away from AI deadlines and irrigation recovery. */
@Configuration
public class RuntimeSchedulingConfiguration {

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    // The conventional name is intentional: unqualified @Scheduled tasks select this bean,
    // even when a separate ScheduledExecutorService exists for AI stream deadlines.
    return scheduler("farm-background-", 2);
  }

  @Bean
  public ThreadPoolTaskScheduler irrigationSafetyScheduler() {
    return scheduler("irrigation-safety-", 1);
  }

  /** A first retention pass over a large demo database can take minutes; it must not occupy a business timer thread. */
  @Bean
  public ThreadPoolTaskScheduler demoRetentionScheduler() {
    return scheduler("demo-retention-", 1);
  }

  private static ThreadPoolTaskScheduler scheduler(String prefix, int threads) {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(threads);
    scheduler.setThreadNamePrefix(prefix);
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }
}
