package io.github.chalmery.loopcraft.template.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 租户共享的 Agent 模板，只保存配置，不执行任务。 */
@TableName("lc_agent_template")
public class AgentTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String name;

    private String description;

    private Boolean enabled;

    private Long modelId;

    private String systemPrompt;

    private BigDecimal temperature;

    private Long contextBudgetTokens;

    private Long summaryModelId;

    private Integer keepRecentMessages;

    private Long maxIterations;

    private BigDecimal timeoutMinutes;

    private Long revision;

    private Long createdBy;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public Long getContextBudgetTokens() {
        return contextBudgetTokens;
    }

    public void setContextBudgetTokens(Long contextBudgetTokens) {
        this.contextBudgetTokens = contextBudgetTokens;
    }

    public Long getSummaryModelId() {
        return summaryModelId;
    }

    public void setSummaryModelId(Long summaryModelId) {
        this.summaryModelId = summaryModelId;
    }

    public Integer getKeepRecentMessages() {
        return keepRecentMessages;
    }

    public void setKeepRecentMessages(Integer keepRecentMessages) {
        this.keepRecentMessages = keepRecentMessages;
    }

    public Long getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(Long maxIterations) {
        this.maxIterations = maxIterations;
    }

    public BigDecimal getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(BigDecimal timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
