package com.lookahead.learning.content.atdd;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
/** Match live scenarios without running hooks, HTTP requests or services. */
class LiveFeatureDiscoveryTest {
    @Test void allLiveScenariosHaveMatchingJavaSteps() throws Exception {
        String tags=System.getProperty("cucumber.filter.tags"), dry=System.getProperty("cucumber.execution.dry-run");
        Path report=Path.of("target/cucumber/live-discovery.json");
        Files.deleteIfExists(report);
        try {
            System.setProperty("cucumber.filter.tags","@live");
            System.setProperty("cucumber.execution.dry-run","true");
            byte result=io.cucumber.core.cli.Main.run(new String[]{"--dry-run","--tags","@live","--plugin","json:"+report,
                    "--glue","com.lookahead.learning.content.atdd","classpath:atdd/live-dependency"},Thread.currentThread().getContextClassLoader());
            assertThat(result).isZero();
            var features=new JsonMapper().readTree(Files.readString(report));
            int scenarios=0,steps=0;
            for(var feature:features) for(var scenario:feature.path("elements")) {
                if("scenario".equals(scenario.path("type").asText())) scenarios++;
                // Cucumber reports defined dry-run steps as passed; their bodies are not executed.
                for(var step:scenario.path("steps")) {
                    steps++;
                    assertThat(step.path("match").path("location").asText()).isNotBlank();
                    assertThat(step.path("result").path("status").asText()).isEqualTo("passed");
                }
            }
            assertThat(scenarios).isGreaterThanOrEqualTo(19);
            assertThat(steps).isGreaterThan(60);
        } finally {
            if(tags==null)System.clearProperty("cucumber.filter.tags");else System.setProperty("cucumber.filter.tags",tags);
            if(dry==null)System.clearProperty("cucumber.execution.dry-run");else System.setProperty("cucumber.execution.dry-run",dry);
        }
    }
}
