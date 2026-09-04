package com.agenttavern.llmprovider;

import com.agenttavern.agents.AgentDecisionProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent-tavern.agent-provider", havingValue = "deepseek")
class DeepSeekProviderConfiguration {
    @Bean
    AgentDecisionProvider deepSeekDecisionProvider(
            @Value("${agent-tavern.deepseek.api-key}") String apiKey,
            @Value("${agent-tavern.deepseek.base-url}") URI baseUrl,
            @Value("${agent-tavern.deepseek.model}") String model,
            @Value("${agent-tavern.deepseek.timeout}") String timeoutValue) {
        Duration timeout = DurationStyle.detectAndParse(timeoutValue);
        DeepSeekProperties properties = new DeepSeekProperties(baseUrl, apiKey, model, timeout);
        return new DeepSeekDecisionProvider(
                properties,
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                JsonMapper.builder().build());
    }
}
