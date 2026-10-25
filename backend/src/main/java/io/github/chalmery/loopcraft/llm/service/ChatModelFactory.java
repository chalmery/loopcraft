package io.github.chalmery.loopcraft.llm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.llm.mapper.LlmModelMapper;
import io.github.chalmery.loopcraft.llm.mapper.LlmSourceMapper;
import io.github.chalmery.loopcraft.llm.model.LlmModel;
import io.github.chalmery.loopcraft.llm.model.LlmSource;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 按模板引用构建本次运行的 ChatModel 与请求选项；链路不可用即明确失败，不静默回退。 */
@Component
public class ChatModelFactory {

    private final ObjectProvider<LlmModelMapper> modelMappers;

    private final ObjectProvider<LlmSourceMapper> sourceMappers;

    private final LlmCredentialCipher cipher;

    private final AgentProperties properties;

    public ChatModelFactory(
            ObjectProvider<LlmModelMapper> modelMappers,
            ObjectProvider<LlmSourceMapper> sourceMappers,
            LlmCredentialCipher cipher,
            AgentProperties properties) {
        this.modelMappers = modelMappers;
        this.sourceMappers = sourceMappers;
        this.cipher = cipher;
        this.properties = properties;
    }

    /**
     * 校验模板引用的模型与来源并构建本次运行使用的模型实例和选项。
     * 凭证解密只发生在此链路内，实例不注册为全局 Bean，超时随本次请求传入。
     */
    public Prepared prepare(long tenantId, AgentTemplate template, Map<String, ToolCallback> tools) {
        LlmModel model =
                modelMappers.getObject()
                        .selectOne(
                                Wrappers.<LlmModel>lambdaQuery()
                                        .eq(LlmModel::getTenantId, tenantId)
                                        .eq(LlmModel::getId, template.getModelId()));
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())) {
            throw unavailable("模板引用的模型不存在或已停用");
        }
        LlmSource source =
                sourceMappers.getObject()
                        .selectOne(
                                Wrappers.<LlmSource>lambdaQuery()
                                        .eq(LlmSource::getTenantId, tenantId)
                                        .eq(LlmSource::getId, model.getSourceId()));
        if (source == null || !Boolean.TRUE.equals(source.getEnabled())) {
            throw unavailable("模板引用的模型连接不存在或已停用");
        }
        if (source.getApiKeyCiphertext() == null) {
            throw unavailable("模板引用的模型连接未配置 API Key");
        }
        return build(source, model, template, tools);
    }

    private Prepared build(
            LlmSource source, LlmModel model, AgentTemplate template, Map<String, ToolCallback> tools) {
        String apiKey = cipher.decrypt(source.getTenantId(), source.getApiKeyCiphertext());
        if ("OPENAI_COMPATIBLE".equals(source.getProtocol())) {
            // Spring AI 2.0 在未显式提供 async client 时会按选项重建并因缺少凭证失败，
            // 因此同步与异步 client 都在此显式构建，凭证只保留在 client 实例内。
            OpenAIClient client =
                    OpenAIOkHttpClient.builder()
                            .baseUrl(source.getBaseUrl())
                            .apiKey(apiKey)
                            .timeout(properties.modelRequestTimeout())
                            .build();
            OpenAIClientAsync asyncClient =
                    OpenAIOkHttpClientAsync.builder()
                            .baseUrl(source.getBaseUrl())
                            .apiKey(apiKey)
                            .timeout(properties.modelRequestTimeout())
                            .build();
            OpenAiChatModel chatModel =
                    OpenAiChatModel.builder()
                            .openAiClient(client)
                            .openAiClientAsync(asyncClient)
                            .build();
            return new Prepared(chatModel, options(model, template, tools));
        }
        // Anthropic 兼容协议的运行时构建暂未接入，明确失败优于半可用；接入时补充离线桩验证。
        throw unavailable("ANTHROPIC_COMPATIBLE 协议的运行时暂未接入");
    }

    private ChatOptions options(LlmModel model, AgentTemplate template, Map<String, ToolCallback> tools) {
        BigDecimal temperature = template.getTemperature();
        OpenAiChatOptions.Builder options =
                OpenAiChatOptions.builder()
                        .model(model.getModelId())
                        .toolCallbacks(List.copyOf(tools.values()));
        if (temperature != null) {
            options.temperature(temperature.doubleValue());
        }
        return options.build();
    }

    private ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    /** 一次运行的模型实例与预构建选项；工具表与温度在运行开始前固定。 */
    public record Prepared(ChatModel chatModel, ChatOptions options) {
    }
}
