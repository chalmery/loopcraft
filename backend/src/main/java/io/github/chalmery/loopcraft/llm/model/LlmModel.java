package io.github.chalmery.loopcraft.llm.model;

import java.time.OffsetDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 连接下的模型配置，不包含运行参数。 */
@TableName("lc_llm_model")
public class LlmModel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long sourceId;
    private String name;
    private String modelId;
    /** MANUAL 手动添加，DISCOVERY 候选导入。 */
    private String origin;
    private Boolean enabled;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
