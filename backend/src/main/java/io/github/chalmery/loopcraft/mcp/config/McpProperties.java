package io.github.chalmery.loopcraft.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP 能力开关。stdio 允许后端 spawn 租户配置的任意命令，默认关闭，
 * 仅在明确信任本机执行边界的开发环境显式打开。
 */
@ConfigurationProperties("loopcraft.mcp")
public record McpProperties(@DefaultValue("false") boolean stdioEnabled) {}
