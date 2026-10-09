package app.zhinong;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "spring.datasource.url=jdbc:h2:mem:research-tests;DB_CLOSE_DELAY=-1",
  "farm.demo=true","farm.demo-rich=true","farm.research-history=true","farm.demo-live=false",
  "farm.simulation.use-primary=true","farm.bootstrap-password=Test-Only-Password-429!"
})
class ResearchDataIntegrationTest extends ResearchScenarioContract {}
