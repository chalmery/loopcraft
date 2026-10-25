package io.github.chalmery.loopcraft.mcp.service;

import io.github.chalmery.loopcraft.mcp.dto.McpViews;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性连接探测：initialize 握手后拉取工具清单并立即关闭。
 * 只用于配置验证，不维护长连接与缓存；常驻复用属后续连接生命周期阶段。
 */
@Component
public class McpConnectionProbe {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private static final int REASON_MAX = 200;

    public McpViews.TestResult probe(
            String transport,
            String url,
            String command,
            List<String> args,
            List<String> envVars,
            Map<String, String> headers) {
        try (McpSyncClient client =
                McpClient.sync(transport(transport, url, command, args, envVars, headers))
                        .requestTimeout(PROBE_TIMEOUT)
                        .build()) {
            McpSchema.InitializeResult initialized = client.initialize();
            List<McpViews.ToolSummary> tools =
                    client.listTools().tools().stream()
                            .map(tool -> new McpViews.ToolSummary(tool.name(), tool.description()))
                            .toList();
            return McpViews.TestResult.ok(
                    initialized.protocolVersion(),
                    initialized.serverInfo().name(),
                    initialized.serverInfo().version(),
                    transport,
                    tools);
        } catch (Exception error) {
            return McpViews.TestResult.failed(reason(error));
        }
    }

    private McpClientTransport transport(
            String transport,
            String url,
            String command,
            List<String> args,
            List<String> envVars,
            Map<String, String> headers) {
        return switch (transport) {
            case "HTTP_STREAMABLE" ->
                    HttpClientStreamableHttpTransport.builder(url)
                            .customizeRequest(builder -> headers.forEach(builder::header))
                            .build();
            case "HTTP_SSE" ->
                    HttpClientSseClientTransport.builder(url)
                            .customizeRequest(builder -> headers.forEach(builder::header))
                            .build();
            case "STDIO" ->
                    new StdioClientTransport(
                            ServerParameters.builder(command)
                                    .args(args)
                                    .env(environment(envVars))
                                    .build(),
                            new JacksonMcpJsonMapper(new ObjectMapper()));
            default -> throw new IllegalArgumentException("未知传输类型");
        };
    }

    private Map<String, String> environment(List<String> envVars) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : envVars) {
            int eq = item.indexOf('=');
            result.put(item.substring(0, eq), item.substring(eq + 1));
        }
        return result;
    }

    /** 单行摘要，控制长度且不含堆栈；鉴权头值不会出现在异常消息中。 */
    private String reason(Exception error) {
        String message = error.getMessage();
        String text = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        String flat = text.replaceAll("[\\r\\n\\t]", " ").strip();
        return flat.length() > REASON_MAX ? flat.substring(0, REASON_MAX) + "…" : flat;
    }
}
