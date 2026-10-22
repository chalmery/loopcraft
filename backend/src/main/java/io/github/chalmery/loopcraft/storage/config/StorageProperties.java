package io.github.chalmery.loopcraft.storage.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/** RustFS 连接配置，仅在显式启用存储时绑定和校验。 */
@ConfigurationProperties("loopcraft.storage")
public record StorageProperties(
        @DefaultValue("http://127.0.0.1:9000") URI endpoint,
        @DefaultValue("us-east-1") String region,
        String accessKey,
        String secretKey,
        @DefaultValue("20MB") DataSize maxFileSize,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue("5m") Duration downloadLinkTtl) {

    public StorageProperties {
        if (endpoint == null || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("存储 endpoint 必须是无凭证、路径和查询参数的 HTTP(S) 地址");
        }
        if (!StringUtils.hasText(region) || !StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
            throw new IllegalArgumentException("启用存储必须配置 region、access-key 和 secret-key");
        }
        if (maxFileSize == null || maxFileSize.toBytes() <= 0 || maxFileSize.toBytes() > 5L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("单文件大小限制必须大于 0 且不超过 5 GiB");
        }
        requireDuration(connectTimeout, Duration.ofMinutes(1), "connect-timeout");
        requireDuration(readTimeout, Duration.ofMinutes(10), "read-timeout");
        requireDuration(requestTimeout, Duration.ofMinutes(15), "request-timeout");
        requireDuration(downloadLinkTtl, Duration.ofHours(1), "download-link-ttl");
        if (requestTimeout.compareTo(connectTimeout) < 0 || requestTimeout.compareTo(readTimeout) < 0) {
            throw new IllegalArgumentException("request-timeout 不能小于连接或读取超时");
        }
    }

    private static void requireDuration(Duration duration, Duration maximum, String name) {
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("存储 " + name + " 超出允许范围");
        }
    }

    /** 配置对象被诊断输出时也不暴露凭证。 */
    @Override
    public String toString() {
        return "StorageProperties[credentials=REDACTED]";
    }
}
