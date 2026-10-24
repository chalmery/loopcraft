package io.github.chalmery.loopcraft.llm.service;

import io.github.chalmery.loopcraft.llm.dto.LlmViews;

import jakarta.annotation.PreDestroy;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 仅查询模型目录，不执行推理、不保存模型；不跟随重定向转发凭证。 */
@Component
public class LlmModelDiscovery {

    private static final int MAX_BODY = 2 * 1024 * 1024;

    private static final int MAX_MODELS = 2000;

    private final CloseableHttpClient client;

    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectMapper json;

    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public LlmModelDiscovery(ObjectMapper json, LlmOutboundDnsResolver dns) {
        this(json, Duration.ofSeconds(15), dns);
    }

    LlmModelDiscovery(ObjectMapper json, Duration timeout, LlmOutboundDnsResolver dns) {
        this.json = json;
        this.timeout = timeout;
        var connections =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(dns)
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.ofSeconds(5))
                                        .build())
                        .build();
        client =
                HttpClients.custom()
                        .setConnectionManager(connections)
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setConnectionRequestTimeout(Timeout.of(timeout))
                                        .setResponseTimeout(Timeout.of(timeout))
                                        .build())
                        .disableRedirectHandling()
                        .disableAutomaticRetries()
                        .disableCookieManagement()
                        .disableAuthCaching()
                        .build();
    }

    public LlmViews.Discovery discover(
            String protocol, String baseUrl, String apiKey, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor.length() > 160)) {
            throw LlmInputs.invalid("查询分页参数不合法");
        }
        boolean anthropic = "ANTHROPIC_COMPATIBLE".equals(protocol);
        String suffix = "";
        int offset = 0;
        if (anthropic) {
            suffix = "?limit=" + limit;
            if (cursor != null && !cursor.isBlank()) {
                suffix += "&after_id=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            }
        } else if ("OPENAI_COMPATIBLE".equals(protocol)) {
            if (cursor != null && !cursor.isBlank()) {
                try {
                    if (!cursor.matches("o:[0-9]{1,4}")) {
                        throw new IllegalArgumentException();
                    }
                    offset = Integer.parseInt(cursor.substring(2));
                    if (offset >= MAX_MODELS) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException exception) {
                    throw LlmInputs.invalid("查询游标不合法");
                }
            }
        } else {
            throw LlmInputs.invalid("未知协议");
        }
        var request = new HttpGet(URI.create(LlmInputs.baseUrl(baseUrl) + "/models" + suffix));
        request.setHeader("Accept", "application/json");
        if (anthropic) {
            request.setHeader("x-api-key", apiKey);
            request.setHeader("anthropic-version", "2023-06-01");
        } else {
            request.setHeader("Authorization", "Bearer " + apiKey);
        }
        var pending =
                requests.submit(
                        () ->
                                client.execute(
                                        request,
                                        upstream -> {
                                            var entity = upstream.getEntity();
                                            byte[] body =
                                                    entity == null
                                                            ? new byte[0]
                                                            : entity.getContent()
                                                                    .readNBytes(MAX_BODY + 1);
                                            if (body.length > MAX_BODY) {
                                                request.cancel();
                                                throw new IOException("响应超过上限");
                                            }
                                            return new Upstream(upstream.getCode(), body);
                                        }));
        Upstream response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            request.cancel();
            pending.cancel(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "供应商查询超时，请重试或手动添加");
        } catch (InterruptedException exception) {
            request.cancel();
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure("供应商查询已取消");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof SocketTimeoutException) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "供应商查询超时，请重试或手动添加");
            }
            throw failure("供应商连接失败、地址受限或响应过大，请检查连接配置");
        }
        int status = response.status();
        if (status == 404 || status == 405 || status == 501) {
            return new LlmViews.Discovery(List.of(), null, false);
        }
        if (status == 401 || status == 403) {
            throw failure("供应商认证失败，请检查 API Key 和访问权限");
        }
        if (status < 200 || status >= 300) {
            throw failure("供应商查询失败，请稍后重试或手动添加");
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray() || data.size() > MAX_MODELS) {
                throw failure("供应商模型列表格式不合法或数量超过上限");
            }
            var candidates = new LinkedHashMap<String, LlmViews.Candidate>();
            for (JsonNode item : data) {
                JsonNode idNode = item.get("id");
                if (idNode == null || !idNode.isString()) {
                    throw failure("供应商模型列表缺少有效模型 ID");
                }
                String id = idNode.asString().strip();
                if (id.isEmpty() || id.length() > 160) {
                    throw failure("供应商模型 ID 格式不合法");
                }
                JsonNode display = item.get(anthropic ? "display_name" : "name");
                String name =
                        display != null && display.isString() && !display.asString().isBlank()
                                ? display.asString().strip()
                                : id;
                // 显示名可缩短，实际模型 ID 保持原大小写。
                if (name.length() > 80) {
                    name = name.substring(0, 80);
                }
                candidates.putIfAbsent(id, new LlmViews.Candidate(name, id));
            }
            var records = new ArrayList<>(candidates.values());
            String next = null;
            if (anthropic) {
                JsonNode more = root.get("has_more");
                if (more == null || !more.isBoolean()) {
                    throw failure("供应商分页信息不合法");
                }
                if (more.asBoolean()) {
                    JsonNode last = root.get("last_id");
                    if (last == null
                            || !last.isString()
                            || last.asString().isBlank()
                            || last.asString().length() > 160
                            || last.asString().equals(cursor)
                            || records.isEmpty()) {
                        throw failure("供应商分页游标不合法");
                    }
                    next = last.asString();
                }
                if (records.size() > limit) {
                    throw failure("供应商未遵守分页上限");
                }
            } else {
                if (offset > records.size()) {
                    throw LlmInputs.invalid("目录已变更，请重新查询");
                }
                int end = Math.min(offset + limit, records.size());
                next = end < records.size() ? "o:" + end : null;
                records = new ArrayList<>(records.subList(offset, end));
            }
            return new LlmViews.Discovery(records, next, true);
        } catch (JacksonException exception) {
            throw failure("供应商返回了无效的模型列表");
        }
    }

    private ResponseStatusException failure(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    @PreDestroy
    public void close() {
        requests.shutdownNow();
        client.close(CloseMode.IMMEDIATE);
    }

    private record Upstream(int status, byte[] body) {}
}
