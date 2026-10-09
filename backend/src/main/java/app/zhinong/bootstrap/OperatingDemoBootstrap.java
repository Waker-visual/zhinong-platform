package app.zhinong.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
public class OperatingDemoBootstrap implements ApplicationRunner {
  private final OperatingDemoData demo;
  private final boolean live;
  private final ResearchDemoData research;
  public OperatingDemoBootstrap(OperatingDemoData demo, ResearchDemoData research, @Value("${farm.demo-live:false}") boolean live) {
    this.demo = demo;
    this.research = research;
    this.live = live;
  }
  @Override public void run(ApplicationArguments args) {
    research.initialize();
    demo.initialize();
    if (live) demo.tick();
  }
}
