package com.agenttavern.llmprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenttavern.agents.AgentDecisionProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeepSeekProviderConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DeepSeekProviderConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues(
                    "agent-tavern.agent-provider=deepseek",
                    "agent-tavern.deepseek.api-key=test-only-key",
                    "agent-tavern.deepseek.base-url=https://api.deepseek.com",
                    "agent-tavern.deepseek.model=deepseek-flash",
                    "agent-tavern.deepseek.timeout=2s");

    @Test
    void wiresTheProviderWithoutExposingTheConfiguredKey() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AgentDecisionProvider.class);
            assertThat(context.getBean(AgentDecisionProvider.class))
                    .isInstanceOf(DeepSeekDecisionProvider.class);
            assertThat(context.toString()).doesNotContain("test-only-key");
        });
    }
}
