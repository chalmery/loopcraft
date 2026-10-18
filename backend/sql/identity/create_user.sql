CREATE TABLE lc_user (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username varchar(64) NOT NULL UNIQUE,
    password_hash varchar(512) NOT NULL,
    display_name varchar(100) NOT NULL,
    platform_admin boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    credential_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_username_format CHECK (username ~ '^[a-z0-9][a-z0-9_.-]{2,63}$'),
    CONSTRAINT user_display_name_not_blank CHECK (length(trim(display_name)) > 0)
);

COMMENT ON TABLE lc_user IS '用户：全局账号及内置登录凭证';
COMMENT ON COLUMN lc_user.id IS '用户 ID';
COMMENT ON COLUMN lc_user.username IS '登录名，全局唯一，统一小写';
COMMENT ON COLUMN lc_user.password_hash IS '单向密码哈希，包含算法标识';
COMMENT ON COLUMN lc_user.display_name IS '显示名称';
COMMENT ON COLUMN lc_user.platform_admin IS '是否为平台管理员，与租户角色独立';
COMMENT ON COLUMN lc_user.enabled IS '账号是否启用';
COMMENT ON COLUMN lc_user.credential_version IS '凭证版本，变更后旧会话失效';
COMMENT ON COLUMN lc_user.created_at IS '创建时间';
COMMENT ON COLUMN lc_user.updated_at IS '更新时间';
