package app.zhinong.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "farm.demo-live", havingValue = "true")
public class OperatingDemoTicker {
  private final OperatingDemoData demo;
  public OperatingDemoTicker(OperatingDemoData demo) { this.demo = demo; }
  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void tick() { demo.tick(); }
}
