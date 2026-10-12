package app.zhinong.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 流式对话的工作线程池：请求线程只负责鉴权和幂等检查后立即返回 SseEmitter，
 * 真正的模型调用、SSE 读取和落库都在这个执行器的线程上完成，不会占用 HTTP 请求线程，
 * 也不会让数据库事务在整个流式响应期间保持打开。虚拟线程只承载已通过
 * AiStreamService 的 8 个运行槽准入的任务；模型请求另有 3 个并发名额。
 */
@Configuration
class AiStreamExecutorConfig {

  // Context destruction follows the HTTP graceful-stop window. Interrupt any
  // remaining run instead of relying on a deadline scheduler that is also closing.
  @Bean(destroyMethod = "shutdownNow")
  ExecutorService aiStreamExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService aiStreamDeadlineScheduler() {
    var scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
      Thread thread = new Thread(runnable, "ai-stream-deadline");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return scheduler;
  }
}
