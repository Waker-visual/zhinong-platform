package app.zhinong;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

/** Run against an EMPTY isolated schema, never the application's configured database. */
@EnabledIfEnvironmentVariable(named="FARM_MYSQL_TEST_URL",matches="jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/zhinong_test_[A-Za-z0-9_]+.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "spring.sql.init.schema-locations=classpath:schema-mysql.sql",
  "spring.datasource.hikari.connection-init-sql=SET SESSION sql_mode = CONCAT(@@sql_mode, ',ANSI_QUOTES')",
  "farm.demo=true","farm.demo-rich=true","farm.research-history=true","farm.demo-live=false",
  "farm.simulation.use-primary=true","farm.bootstrap-password=Test-Only-Password-429!"
})
class MySqlIntegrationTest extends ResearchScenarioContract {
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url",()->System.getenv("FARM_MYSQL_TEST_URL"));
    properties.add("spring.datasource.username",()->System.getenv("FARM_MYSQL_TEST_USER"));
    properties.add("spring.datasource.password",()->System.getenv("FARM_MYSQL_TEST_PASSWORD"));
  }
}
