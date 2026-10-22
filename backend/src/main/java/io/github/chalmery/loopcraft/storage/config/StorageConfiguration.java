package io.github.chalmery.loopcraft.storage.config;

import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.storage.service.RustFsFileStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 装配 RustFS 客户端，启动时不访问远端，也不隐式建桶。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "loopcraft.storage", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client rustFsClient(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .forcePathStyle(true)
                // 普通文件 PUT 不依赖可选的 aws-chunked 校验和扩展。
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.connectTimeout()).socketTimeout(properties.readTimeout()))
                .overrideConfiguration(config -> config
                        .apiCallTimeout(properties.requestTimeout())
                        .apiCallAttemptTimeout(properties.requestTimeout()))
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner rustFsPresigner(StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    FileStorage fileStorage(S3Client rustFsClient, S3Presigner rustFsPresigner, StorageProperties properties) {
        return new RustFsFileStorage(rustFsClient, rustFsPresigner, properties);
    }

    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
