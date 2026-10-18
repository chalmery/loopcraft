package io.github.chalmery.loopcraft.llm.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class LlmModelDiscoveryTests {
    private HttpServer server;
    private LlmModelDiscovery discovery;
    private String baseUrl;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> version = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long delay;
    private volatile String body = "{\"data\":[{\"id\":\"Model-A\"},{\"id\":\"Model-B\"},{\"id\":\"Model-A\"}]}";

    @BeforeEach
    void setup() throws IOException {
        discovery = new LlmModelDiscovery(JsonMapper.builder().build(), Duration.ofMillis(400),
                new LlmOutboundDnsResolver("127.0.0.1"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/custom/v1/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (auth.get() == null) auth.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            path.set(exchange.getRequestURI().toString());
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1";
    }

    @AfterEach
    void cleanup() { server.stop(0); discovery.close(); }

    @Test
    void openAiAuthPathDedupAndLocalPaginationPreserveModelIds() {
        var first = discovery.discover("OPENAI_COMPATIBLE", baseUrl, "test-only-key", null, 1);
        assertThat(auth.get()).isEqualTo("Bearer test-only-key");
        assertThat(path.get()).isEqualTo("/custom/v1/models");
        assertThat(version.get()).isNull();
        assertThat(first.records().getFirst().modelId()).isEqualTo("Model-A");
        assertThat(first.nextCursor()).isEqualTo("o:1");
        var second = discovery.discover("OPENAI_COMPATIBLE", baseUrl, "test-only-key", first.nextCursor(), 1);
        assertThat(second.records().getFirst().modelId()).isEqualTo("Model-B");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void anthropicUsesVersionApiKeyAndEncodedCursor() {
        body = "{\"data\":[{\"id\":\"claude-test\",\"display_name\":\"测试模型\"}],\"has_more\":true,\"last_id\":\"next-test\"}";
        var result = discovery.discover("ANTHROPIC_COMPATIBLE", baseUrl, "test-only-key", "cursor+a/b", 10);
        assertThat(auth.get()).isEqualTo("test-only-key");
        assertThat(version.get()).isEqualTo("2023-06-01");
        assertThat(path.get()).isEqualTo("/custom/v1/models?limit=10&after_id=cursor%2Ba%2Fb");
        assertThat(result.nextCursor()).isEqualTo("next-test");
        assertThat(result.records().getFirst().name()).isEqualTo("测试模型");
    }

    @Test
    void distinguishesUnsupportedAuthenticationAndOtherFailuresWithoutEchoingUpstream() {
        body = "secret-upstream-test-only-key";
        for (int code : new int[] {404, 405, 501}) {
            status = code;
            assertThat(discovery.discover("OPENAI_COMPATIBLE", baseUrl, "test-only-key", null, 10).supported()).isFalse();
        }
        for (int code : new int[] {401, 403, 429, 500, 302}) {
            status = code;
            assertThatThrownBy(() -> discovery.discover("OPENAI_COMPATIBLE", baseUrl, "test-only-key", null, 10))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502))
                    .hasMessageNotContaining("secret").hasMessageNotContaining("test-only-key");
        }
    }

    @Test
    void boundsTimeoutAndResponseSizeAndRejectsMalformedAndRepeatedCursors() {
        delay = 1500;
        assertThatThrownBy(() -> discovery.discover("OPENAI_COMPATIBLE", baseUrl, "test-only-key", null, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(504));
        delay = 0;
        for (String invalid : new String[] {"not-json", "{\"error\":\"secret\"}", "{\"data\":[{}]}",
                "{\"data\":[{\"id\":7}]}", "x".repeat(2 * 1024 * 1024 + 1)}) {
            body = invalid;
            assertThatThrownBy(() -> discovery.discover("OPENAI_COMPATIBLE", baseUrl, "test-only-key", null, 10))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        }
        body = "{\"data\":[{\"id\":\"same\"}],\"has_more\":true,\"last_id\":\"same\"}";
        assertThatThrownBy(() -> discovery.discover("ANTHROPIC_COMPATIBLE", baseUrl, "test-only-key", "same", 10))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void blocksLoopbackLiteralsAndDnsNamesBeforeSendingCredentials() {
        discovery.close();
        discovery = new LlmModelDiscovery(JsonMapper.builder().build(), Duration.ofSeconds(2), new LlmOutboundDnsResolver(""));
        for (String root : new String[] {baseUrl, baseUrl.replace("127.0.0.1", "localhost"),
                baseUrl.replace("127.0.0.1", "2130706433"), baseUrl.replace("127.0.0.1", "[::ffff:127.0.0.1]")}) {
            assertThatThrownBy(() -> discovery.discover("OPENAI_COMPATIBLE", root, "test-only-key", null, 10))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        }
        assertThat(auth.get()).isNull();
        assertThat(path.get()).isNull();
    }

    @Test
    void connectsToValidatedDnsResultWhilePreservingProviderHost() throws Exception {
        discovery.close();
        DnsResolver dns = mock(DnsResolver.class);
        when(dns.resolve("provider.test")).thenReturn(new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        discovery = new LlmModelDiscovery(JsonMapper.builder().build(), Duration.ofSeconds(2),
                new LlmOutboundDnsResolver("provider.test", dns));
        var result = discovery.discover("OPENAI_COMPATIBLE", baseUrl.replace("127.0.0.1", "provider.test"), "test-only-key", null, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(auth.get()).isEqualTo("Bearer test-only-key");
        verify(dns, times(1)).resolve("provider.test");
    }
}
