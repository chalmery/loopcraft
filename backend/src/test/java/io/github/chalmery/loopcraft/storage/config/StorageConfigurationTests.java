package io.github.chalmery.loopcraft.storage.config;

import java.time.Duration;

import io.github.chalmery.loopcraft.storage.service.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfiguration.class);

    @Test
    void disabledByDefaultWithoutCredentialsOrClient() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FileStorage.class)
                .doesNotHaveBean(S3Client.class).doesNotHaveBean(StorageProperties.class));
    }

    @Test
    void enabledBindsDefaultsWithoutContactingTheServer() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FileStorage.class);
            var properties = context.getBean(StorageProperties.class);
            assertThat(properties.maxFileSize().toBytes()).isEqualTo(20 * 1024 * 1024);
            assertThat(properties.downloadLinkTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.toString()).doesNotContain("test-access", "test-secret");
        });
    }

    @Test
    void enabledRequiresExplicitCredentials() {
        runner.withPropertyValues("loopcraft.storage.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesInvalidEndpointsSizesAndTimeouts() {
        for (String invalid : new String[]{"endpoint=file:///tmp/storage", "endpoint=http://user:secret@localhost:9000",
                "endpoint=http://localhost:9000/path", "endpoint=http://localhost:70000", "endpoint=http://localhost:0",
                "endpoint=http://localhost:9000?token=secret", "max-file-size=0B", "max-file-size=6GB",
                "connect-timeout=0s", "read-timeout=0s", "request-timeout=1s", "download-link-ttl=61m"}) {
            enabled().withPropertyValues("loopcraft.storage." + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("loopcraft.storage.enabled=true",
                "loopcraft.storage.access-key=test-access", "loopcraft.storage.secret-key=test-secret");
    }
}
