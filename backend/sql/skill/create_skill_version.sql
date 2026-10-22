CREATE TABLE lc_skill_version (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    skill_id bigint NOT NULL,
    number integer NOT NULL CHECK (number > 0),
    request_id varchar(36) NOT NULL,
    digest varchar(64) NOT NULL,
    bucket varchar(63) NOT NULL,
    object_key varchar(1024) NOT NULL,
    file_count integer NOT NULL CHECK (file_count > 0),
    total_bytes bigint NOT NULL CHECK (total_bytes >= 0),
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES lc_skill(tenant_id, id) ON DELETE RESTRICT,
    UNIQUE (skill_id, number),
    UNIQUE (skill_id, request_id)
);
COMMENT ON TABLE lc_skill_version IS '不可变 Skill 整包版本';
COMMENT ON COLUMN lc_skill_version.id IS '版本记录 ID';
COMMENT ON COLUMN lc_skill_version.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_skill_version.skill_id IS '所属 Skill';
COMMENT ON COLUMN lc_skill_version.number IS '递增业务版本号';
COMMENT ON COLUMN lc_skill_version.request_id IS '版本保存幂等标识';
COMMENT ON COLUMN lc_skill_version.digest IS '整包内容 SHA256';
COMMENT ON COLUMN lc_skill_version.bucket IS '对象存储桶';
COMMENT ON COLUMN lc_skill_version.object_key IS '不可变 ZIP 对象 Key';
COMMENT ON COLUMN lc_skill_version.file_count IS '包内文件数量';
COMMENT ON COLUMN lc_skill_version.total_bytes IS '解压后总字节数';
COMMENT ON COLUMN lc_skill_version.created_by IS '保存用户 ID';
COMMENT ON COLUMN lc_skill_version.created_at IS '保存时间';
