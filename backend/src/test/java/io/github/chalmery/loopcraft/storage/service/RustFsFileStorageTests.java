package io.github.chalmery.loopcraft.storage.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chalmery.loopcraft.storage.config.StorageConfiguration;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 S3 SDK 和本地 HTTP 桩验证寻址、流传输、签名及错误边界，不需要 RustFS。 */
class RustFsFileStorageTests {
    @TempDir
    Path directory;
    private HttpServer server;
    private final Map<String, ObjectData> objects = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int forcedStatus;
    private volatile String metadataOverride;
    private volatile String lastAuthorization;
    private volatile long delayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void uploadsBinaryStreamsMetadataAndSignsDownloadsWithoutChangingKeys() throws IOException {
        byte[] payload = new byte[512 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        Path source = directory.resolve("file.bin");
        Files.write(source, payload);
        var location = new ObjectLocation("files-one", "任意模块/a b+c.bin");
        runner().run(context -> {
            var storage = context.getBean(FileStorage.class);
            var uploaded = storage.upload(location, source, "报告.bin", "application/octet-stream");
            assertThat(uploaded.location()).isEqualTo(location);
            assertThat(uploaded.size()).isEqualTo(payload.length);
            assertThat(lastAuthorization).startsWith("AWS4-HMAC-SHA256 ").contains("/us-east-1/s3/aws4_request");
            assertThat(storage.stat(location)).isEqualTo(uploaded);
            try (var download = storage.open(location)) {
                assertThat(download.filename()).isEqualTo("报告.bin");
                assertThat(download.size()).isEqualTo(payload.length);
                assertThat(download.stream().readAllBytes()).isEqualTo(payload);
            }
            URI link = storage.presignDownload(location);
            assertThat(link.getPath()).isEqualTo("/files-one/任意模块/a b+c.bin");
            assertThat(link.getRawQuery()).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256", "X-Amz-Expires=300",
                    "X-Amz-Signature=", "response-content-disposition=");
            assertThat(objects.keySet()).containsExactly("/files-one/任意模块/a b+c.bin");
        });
    }

    @Test
    void supportsMultipleBucketsOverwriteEmptyFilesAndIdempotentDelete() throws IOException {
        Path source = directory.resolve("file.txt");
        Files.writeString(source, "original");
        var first = new ObjectLocation("bucket-one", "same-key");
        var second = new ObjectLocation("bucket-two", "same-key");
        runner().run(context -> {
            var storage = context.getBean(FileStorage.class);
            storage.upload(first, source, "a.txt", null);
            storage.upload(second, source, "b.txt", "text/plain");
            Files.writeString(source, "");
            storage.upload(first, source, "empty.txt", null);
            assertThat(storage.stat(first).size()).isZero();
            assertThat(storage.stat(first).filename()).isEqualTo("empty.txt");
            assertThat(storage.stat(first).contentType()).isEqualTo("application/octet-stream");
            assertThat(storage.stat(second).size()).isEqualTo(8);
            storage.delete(first);
            storage.delete(first);
            assertThat(storage.stat(second).filename()).isEqualTo("b.txt");
            assertThatThrownBy(() -> storage.open(first)).isInstanceOfSatisfying(FileStorageException.class,
                    exception -> assertThat(exception.reason()).isEqualTo(FileStorageException.Reason.NOT_FOUND));
        });
    }

    @Test
    void rejectsOversizedFilesAndInvalidNamesBeforeSendingRequests() throws IOException {
        Path source = directory.resolve("file.txt");
        Files.writeString(source, "longer-than-limit");
        var location = new ObjectLocation("bucket-one", "any-key");
        runner().withPropertyValues("loopcraft.storage.max-file-size=2B").run(context -> {
            var storage = context.getBean(FileStorage.class);
            assertThatIllegalArgumentException().isThrownBy(() -> storage.upload(location, source, "a.txt", "text/plain"));
            assertThatIllegalArgumentException().isThrownBy(() -> storage.upload(location, source, "a\r\n.txt", "text/plain"));
            assertThatIllegalArgumentException().isThrownBy(() -> storage.upload(location, source, "../a.txt", "text/plain"));
            assertThatIllegalArgumentException().isThrownBy(() -> storage.upload(location, source, "a.txt", "bad\r\ntype"));
            assertThat(requests).hasValue(0);
        });
    }

    @Test
    void validatesObjectAddressesWithoutImposingBusinessPrefixes() {
        for (String bucket : new String[]{"ab", "UPPER", "a..b", "a.-b", "127.0.0.1", "-bucket"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ObjectLocation(bucket, "file"));
        }
        for (String key : new String[]{"", " ", "a\n", "中".repeat(342)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ObjectLocation("bucket-one", key));
        }
        assertThat(new ObjectLocation("bucket-one", "platform/skills/v1/file").key()).isEqualTo("platform/skills/v1/file");
    }

    @Test
    void sanitizesUpstreamErrorsAndDoesNotPresignMissingFiles() throws IOException {
        Path source = directory.resolve("file.txt");
        Files.writeString(source, "test");
        var location = new ObjectLocation("bucket-one", "missing");
        runner().run(context -> {
            var storage = context.getBean(FileStorage.class);
            assertThatThrownBy(() -> storage.presignDownload(location)).isInstanceOfSatisfying(FileStorageException.class,
                    exception -> assertThat(exception.reason()).isEqualTo(FileStorageException.Reason.NOT_FOUND));
            forcedStatus = 403;
            for (Runnable operation : new Runnable[]{() -> storage.open(location), () -> storage.stat(location),
                    () -> storage.delete(location)}) {
                assertThatThrownBy(operation::run).isInstanceOfSatisfying(FileStorageException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(FileStorageException.Reason.UNAVAILABLE);
                    assertThat(exception.getMessage()).doesNotContain("sensitive-upstream-detail");
                    assertThat(exception.getCause()).isNull();
                });
            }
            assertThatThrownBy(() -> storage.upload(location, source, "file.txt", null)).isInstanceOf(FileStorageException.class);
        });
    }

    @Test
    void fallsBackForExternalObjectsAndRejectsCorruptFilenameMetadata() {
        objects.put("/bucket-one/external.txt", new ObjectData(new byte[]{1}, "text/plain", null));
        var location = new ObjectLocation("bucket-one", "external.txt");
        runner().run(context -> {
            var storage = context.getBean(FileStorage.class);
            assertThat(storage.stat(location).filename()).isEqualTo("external.txt");
            metadataOverride = Base64.getEncoder().encodeToString("bad\r\nfilename".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> storage.open(location)).isInstanceOf(FileStorageException.class);
        });
    }

    @Test
    void boundsSlowUpstreamRequests() {
        delayMillis = 2000;
        runner().withPropertyValues("loopcraft.storage.connect-timeout=1s", "loopcraft.storage.read-timeout=1s",
                "loopcraft.storage.request-timeout=1s").run(context -> {
                    var storage = context.getBean(FileStorage.class);
                    assertThatThrownBy(() -> storage.stat(new ObjectLocation("bucket-one", "slow")))
                            .isInstanceOfSatisfying(FileStorageException.class,
                                    exception -> assertThat(exception.reason()).isEqualTo(FileStorageException.Reason.UNAVAILABLE));
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(StorageConfiguration.class)
                .withPropertyValues("loopcraft.storage.enabled=true", "loopcraft.storage.access-key=test-access",
                        "loopcraft.storage.secret-key=test-secret",
                        "loopcraft.storage.endpoint=http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        try (exchange) {
            if (forcedStatus != 0) {
                error(exchange, forcedStatus);
                return;
            }
            String key = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("PUT".equals(method)) {
                objects.put(key, new ObjectData(exchange.getRequestBody().readAllBytes(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("x-amz-meta-filename-base64")));
                exchange.getResponseHeaders().add("ETag", "\"test-etag\"");
                exchange.sendResponseHeaders(200, -1);
            } else if ("DELETE".equals(method)) {
                objects.remove(key);
                exchange.sendResponseHeaders(204, -1);
            } else if (!objects.containsKey(key)) {
                error(exchange, 404);
            } else {
                ObjectData object = objects.get(key);
                exchange.getResponseHeaders().add("Content-Type", object.type());
                exchange.getResponseHeaders().add("Content-Length", Integer.toString(object.bytes().length));
                String metadata = metadataOverride == null ? object.filename() : metadataOverride;
                if (metadata != null) {
                    exchange.getResponseHeaders().add("x-amz-meta-filename-base64", metadata);
                }
                boolean noBody = "HEAD".equals(method) || object.bytes().length == 0;
                exchange.sendResponseHeaders(200, noBody ? -1 : object.bytes().length);
                if (!noBody) {
                    exchange.getResponseBody().write(object.bytes());
                }
            }
        }
    }

    private static void error(HttpExchange exchange, int status) throws IOException {
        byte[] body = ("<Error><Code>" + (status == 404 ? "NoSuchKey" : "AccessDenied")
                + "</Code><Message>sensitive-upstream-detail</Message></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            exchange.getResponseBody().write(body);
        }
    }

    private record ObjectData(byte[] bytes, String type, String filename) {
    }
}
