package io.github.chalmery.loopcraft.llm.service;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.llm.mapper.LlmModelMapper;
import io.github.chalmery.loopcraft.llm.mapper.LlmSourceMapper;
import io.github.chalmery.loopcraft.llm.model.LlmModel;
import io.github.chalmery.loopcraft.llm.model.LlmSource;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 链路校验测试：模板引用的模型/来源任一环节失效都明确 503，不静默回退其他模型。 */
class ChatModelFactoryTests {

    private static final long TENANT = 1L;

    private static final String ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final LlmModelMapper models = mock(LlmModelMapper.class);

    private final LlmSourceMapper sources = mock(LlmSourceMapper.class);

    private final LlmCredentialCipher cipher = spy(new LlmCredentialCipher(ENCRYPTION_KEY));

    private ChatModelFactory factory;

    @BeforeEach
    void setup() {
        factory =
                new ChatModelFactory(
                        serving(models),
                        serving(sources),
                        cipher,
                        new AgentProperties(
                                Duration.ofSeconds(45),
                                Duration.ofSeconds(15),
                                32,
                                8000,
                                1000,
                                Duration.ofMinutes(10)));
    }

    @Test
    void prepareBuildsOpenAiModelWithOptionsFromTemplate() {
        String ciphertext = encryptedKey();
        when(models.selectOne(any())).thenReturn(model(true));
        when(sources.selectOne(any())).thenReturn(source("OPENAI_COMPATIBLE", ciphertext));
        Map<String, ToolCallback> tools = Map.of("list_skills", tool("list_skills"));

        ChatModelFactory.Prepared prepared = factory.prepare(TENANT, template(), tools);

        assertThat(prepared.chatModel()).isNotNull();
        OpenAiChatOptions options = (OpenAiChatOptions) prepared.options();
        assertThat(options.getModel()).isEqualTo("stub-model");
        assertThat(options.getTemperature()).isEqualTo(0.3d);
        assertThat(options.getToolCallbacks()).containsExactly(tools.get("list_skills"));
        // 凭证解密只发生在链路内，绑定租户参与运算。
        verify(cipher).decrypt(TENANT, ciphertext);
    }

    @Test
    void missingOrDisabledModelReturns503WithoutTouchingSource() {
        when(models.selectOne(any())).thenReturn(null, model(false));

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThatThrownBy(() -> factory.prepare(TENANT, template(), Map.of()))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            error -> {
                                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                                assertThat(error.getReason()).isEqualTo("模板引用的模型不存在或已停用");
                            });
        }
        verify(sources, never()).selectOne(any());
    }

    @Test
    void missingOrDisabledSourceReturns503() {
        String ciphertext = encryptedKey();
        LlmSource disabled = source("OPENAI_COMPATIBLE", ciphertext);
        disabled.setEnabled(false);
        when(models.selectOne(any())).thenReturn(model(true));
        when(sources.selectOne(any())).thenReturn(null, disabled);

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThatThrownBy(() -> factory.prepare(TENANT, template(), Map.of()))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            error -> {
                                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                                assertThat(error.getReason()).isEqualTo("模板引用的模型连接不存在或已停用");
                            });
        }
    }

    @Test
    void sourceWithoutApiKeyReturns503() {
        when(models.selectOne(any())).thenReturn(model(true));
        when(sources.selectOne(any())).thenReturn(source("OPENAI_COMPATIBLE", null));

        assertThatThrownBy(() -> factory.prepare(TENANT, template(), Map.of()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void anthropicProtocolIsRejectedUntilImplemented() {
        String ciphertext = encryptedKey();
        when(models.selectOne(any())).thenReturn(model(true));
        when(sources.selectOne(any())).thenReturn(source("ANTHROPIC_COMPATIBLE", ciphertext));

        assertThatThrownBy(() -> factory.prepare(TENANT, template(), Map.of()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(error.getReason()).isEqualTo("ANTHROPIC_COMPATIBLE 协议的运行时暂未接入");
                        });
    }

    @Test
    void modelFromOtherTenantIsInvisibleAndFailsClosed() {
        // 查询按租户过滤，跨租户模型等价于不存在，绝不回退到其他来源。
        when(models.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> factory.prepare(TENANT, template(), Map.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(sources, never()).selectOne(any());
    }

    @Test
    void undecryptableCredentialReturns503() {
        when(models.selectOne(any())).thenReturn(model(true));
        when(sources.selectOne(any())).thenReturn(source("OPENAI_COMPATIBLE", "v1.not-valid-cipher"));

        assertThatThrownBy(() -> factory.prepare(TENANT, template(), Map.of()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(error.getReason()).contains("模型凭证加密服务不可用");
                        });
    }

    private String encryptedKey() {
        return cipher.encrypt(TENANT, "sk-stub-key");
    }

    private AgentTemplate template() {
        AgentTemplate template = new AgentTemplate();
        template.setId(5L);
        template.setTenantId(TENANT);
        template.setModelId(11L);
        template.setTemperature(new BigDecimal("0.3"));
        return template;
    }

    private LlmModel model(boolean enabled) {
        LlmModel model = new LlmModel();
        model.setId(11L);
        model.setTenantId(TENANT);
        model.setSourceId(21L);
        model.setModelId("stub-model");
        model.setEnabled(enabled);
        return model;
    }

    private LlmSource source(String protocol, String ciphertext) {
        LlmSource source = new LlmSource();
        source.setId(21L);
        source.setTenantId(TENANT);
        source.setProtocol(protocol);
        source.setBaseUrl("http://127.0.0.1:9/v1");
        source.setApiKeyCiphertext(ciphertext);
        source.setEnabled(true);
        return source;
    }

    private ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("测试工具")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "{\"ok\":true}";
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> serving(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
