CREATE TABLE lc_template_skill (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    template_id bigint NOT NULL,
    skill_id bigint NOT NULL,
    version_number integer NOT NULL CHECK (version_number > 0),
    position integer NOT NULL CHECK (position >= 0),
    UNIQUE (template_id, skill_id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES lc_agent_template(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES lc_skill(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (skill_id, version_number) REFERENCES lc_skill_version(skill_id, number) ON DELETE RESTRICT
);
CREATE INDEX template_skill_reference_idx ON lc_template_skill(skill_id, version_number);
COMMENT ON TABLE lc_template_skill IS '模板绑定的固定 Skill 内容版本';
COMMENT ON COLUMN lc_template_skill.id IS '绑定 ID';
COMMENT ON COLUMN lc_template_skill.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_template_skill.template_id IS '所属模板';
COMMENT ON COLUMN lc_template_skill.skill_id IS '当前租户 Skill ID';
COMMENT ON COLUMN lc_template_skill.version_number IS '固定内容版本号，不自动更新';
COMMENT ON COLUMN lc_template_skill.position IS '添加顺序，从 0 开始';
