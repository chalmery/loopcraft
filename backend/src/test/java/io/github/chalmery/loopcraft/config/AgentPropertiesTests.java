package io.github.chalmery.loopcraft.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AgentConfiguration.class);

    @Test
    void bindsDefaultsFromApplicationConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AgentProperties properties = context.getBean(AgentProperties.class);
            assertThat(properties.maxIterations()).isEqualTo(12);
            assertThat(properties.runTimeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(properties.toolTimeout()).isEqualTo(Duration.ofSeconds(15));
        });
    }

    @Test
    void bindsOverrides() {
        runner.withPropertyValues("loopcraft.agent.max-iterations=4", "loopcraft.agent.tool-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentProperties.class).maxIterations()).isEqualTo(4);
                    assertThat(context.getBean(AgentProperties.class).toolTimeout()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Test
    void rejectsInvalidBudgetsAndMisspelledProperties() {
        for (String property : new String[] {
                "loopcraft.agent.max-iterations=0",
                "loopcraft.agent.max-iterations=101",
                "loopcraft.agent.run-timeout=0s",
                "loopcraft.agent.tool-timeout=-1s",
                "loopcraft.agent.tool-timeout=121s",
                "loopcraft.agent.max-iteration=4"
        }) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }
}
