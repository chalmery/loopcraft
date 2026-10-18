# 用户与租户 SQL

新库按顺序手动执行，每个文件只建一张表：

1. `create_user.sql`：用户及内置密码凭证。
2. `create_tenant.sql`：租户工作空间。
3. `create_tenant_member.sql`：成员关系，依赖前两张表。

```bash
./scripts/dev.sh db sql identity/create_user.sql
./scripts/dev.sh db sql identity/create_tenant.sql
./scripts/dev.sh db sql identity/create_tenant_member.sql
```

脚本在仓库根目录执行。表和字段均有中文注释；首次管理员通过应用配置初始化，不在 SQL 中保存明文密码或默认账号。已执行过的脚本不重复执行，后续结构升级另建描述用途的 SQL 文件。
