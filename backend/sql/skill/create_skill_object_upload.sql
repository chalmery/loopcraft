CREATE TABLE lc_skill_object_upload (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    bucket varchar(63) NOT NULL,
    object_key varchar(1024) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bucket, object_key)
);
CREATE INDEX lc_skill_object_upload_created_at_idx ON lc_skill_object_upload(created_at, id);
COMMENT ON TABLE lc_skill_object_upload IS '上传前持久化的 Skill 对象引用，供回滚及进程中断后清理';
COMMENT ON COLUMN lc_skill_object_upload.id IS '上传引用 ID';
COMMENT ON COLUMN lc_skill_object_upload.tenant_id IS '目标租户 ID，独立提交不依赖未提交的业务记录';
COMMENT ON COLUMN lc_skill_object_upload.bucket IS '对象存储桶';
COMMENT ON COLUMN lc_skill_object_upload.object_key IS '待发布或待清理的不可变对象 Key';
COMMENT ON COLUMN lc_skill_object_upload.created_at IS '引用创建时间，超过十五分钟后由后台扫描';
