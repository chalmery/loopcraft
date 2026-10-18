CREATE TABLE lc_tenant_member (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES lc_tenant(id),
    user_id bigint NOT NULL REFERENCES lc_user(id),
    role varchar(16) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, user_id)
);
CREATE INDEX tenant_member_user_idx ON lc_tenant_member(user_id, tenant_id);
CREATE UNIQUE INDEX tenant_single_owner_idx ON lc_tenant_member(tenant_id) WHERE role = 'OWNER';

COMMENT ON TABLE lc_tenant_member IS '租户与用户的成员关系';
COMMENT ON COLUMN lc_tenant_member.id IS '成员关系 ID';
COMMENT ON COLUMN lc_tenant_member.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN lc_tenant_member.user_id IS '成员用户 ID';
COMMENT ON COLUMN lc_tenant_member.role IS '租户角色：OWNER 负责人，MEMBER 普通成员';
COMMENT ON COLUMN lc_tenant_member.created_at IS '加入时间';
