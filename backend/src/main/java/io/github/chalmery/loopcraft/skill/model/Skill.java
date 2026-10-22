package io.github.chalmery.loopcraft.skill.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 租户 Skill 基本信息，与包内文件独立。 */
@TableName("lc_skill")
public class Skill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String slug;
    private String name;
    private String description;
    /** SYSTEM 内置，CUSTOM 租户自建；不由包内元数据决定。 */
    private String origin;
    private Boolean enabled;
    /** 删除后先隐藏，保留对象引用供重试清理。 */
    private Boolean deleted;
    private Integer latestVersion;
    /** 基本信息并发修订号，与内容版本号独立。 */
    private Long revision;
    private String creationRequest;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }

    public Integer getLatestVersion() { return latestVersion; }
    public void setLatestVersion(Integer latestVersion) { this.latestVersion = latestVersion; }

    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }

    public String getCreationRequest() { return creationRequest; }
    public void setCreationRequest(String creationRequest) { this.creationRequest = creationRequest; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
