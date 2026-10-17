package io.github.chalmery.loopcraft.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("llm")
@SpringBootTest(properties = {
        "LLM_API_KEY=test-placeholder",
        "LLM_BASE_URL=http://127.0.0.1:1/v1",
        "LLM_MODEL=test-model",
        "LLM_TIMEOUT=7s",
        "LLM_MAX_TOKENS=128"
})
class LlmConfigurationTests {

    @Autowired
    private OpenAiChatModel model;

    @Autowired
    private Environment environment;

    @Test
    void assemblesModelWithExplicitOptionsWithoutSendingRequests() {
        OpenAiChatOptions options = (OpenAiChatOptions) model.getOptions();
        assertThat(options.getModel()).isEqualTo("test-model");
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(options.getMaxRetries()).isZero();
        assertThat(options.getMaxTokens()).isEqualTo(128);
        assertThat(environment.getProperty("spring.ai.chat.client.tool-calling.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.tools.resolution.fallback.enabled", Boolean.class)).isFalse();
    }
}
