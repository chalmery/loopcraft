package io.github.chalmery.loopcraft.storage.model;

import java.nio.charset.StandardCharsets;

/** 对象地址；桶和 Key 由调用业务决定，不附加租户、模块或目录规则。 */
public record ObjectLocation(String bucket, String key) {
    public ObjectLocation {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || bucket.contains(".-") || bucket.contains("-.")
                || bucket.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException("桶名格式不合法");
        }
        if (key == null || key.isBlank() || key.getBytes(StandardCharsets.UTF_8).length > 1024
                || key.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("对象 Key 不能为空、包含控制字符或超过 1024 字节");
        }
    }
}
