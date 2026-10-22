CREATE TABLE lc_skill_tag (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    skill_id bigint NOT NULL,
    name varchar(32) NOT NULL CHECK (length(trim(name)) > 0),
    FOREIGN KEY (tenant_id, skill_id) REFERENCES lc_skill(tenant_id, id) ON DELETE RESTRICT,
    UNIQUE (skill_id, name)
);
COMMENT ON TABLE lc_skill_tag IS 'Skill 独立标签';
COMMENT ON COLUMN lc_skill_tag.id IS '标签记录 ID';
COMMENT ON COLUMN lc_skill_tag.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_skill_tag.skill_id IS '所属 Skill';
COMMENT ON COLUMN lc_skill_tag.name IS '标签文本，同 Skill 内去重';
