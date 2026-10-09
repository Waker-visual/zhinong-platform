package app.zhinong.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 流式对话的工作线程池：请求线程只负责鉴权和幂等检查后立即返回 SseEmitter，
 * 真正的模型调用、SSE 读取和落库都在这个执行器的线程上完成，不会占用 HTTP 请求线程，
 * 也不会让数据库事务在整个流式响应期间保持打开。使用虚拟线程，天然适合这种长时间
 * 阻塞在网络 IO 上但数量不多的任务。
 */
@Configuration
class AiStreamExecutorConfig {

  @Bean(destroyMethod = "shutdown")
  ExecutorService aiStreamExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
