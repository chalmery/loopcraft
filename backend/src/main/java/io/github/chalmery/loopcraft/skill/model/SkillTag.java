package io.github.chalmery.loopcraft.skill.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 仅属于一个 Skill 的标签。 */
@TableName("lc_skill_tag")
public class SkillTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long skillId;
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
