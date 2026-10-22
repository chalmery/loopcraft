# 手动 SQL 变更

业务模块在独立目录维护 SQL。用户与租户模块见 [`identity/README.md`](identity/README.md)。模型接入见 [`llm/README.md`](llm/README.md)，依赖 identity，按 source → model 执行。Skill 管理见 [`skill/README.md`](skill/README.md)，依赖 identity，按 skill → skill_version → skill_tag 执行。一个表一个建表文件，以用途命名，不加数字前缀，例如 `identity/create_user.sql`。

表名统一使用 `lc_` 前缀和英文小写下划线，字段同样使用小写下划线；建表文件使用 `create_<业务表名>.sql`，省略表名的 `lc_` 前缀。每张表及每个字段添加简短中文 `COMMENT`，索引、约束和注释随所属表放在同一文件。完整规范见 [`backend/AGENTS.md`](../AGENTS.md#数据库命名与-sql-文件)。

- 模块 README 说明执行顺序与依赖。已在共享环境执行的脚本保持不变，通过新脚本演进。
- 新数据库按模块依赖顺序执行全部脚本；已有数据库只执行尚未应用的脚本。
- 模块 README 记录执行顺序和跨模块依赖。发布记录中注明每个环境、每个模块已执行的文件名；本项目不自动记录或推断数据库版本。
- SQL 与对应业务代码一起提交。不要在脚本中写入真实账号、密钥或生产数据。
- 应用启动不执行这些脚本；不使用 Flyway，也不扫描目录自动建表。

本地执行指定文件（路径相对本目录）：

```bash
./scripts/dev.sh db sql identity/create_user.sql
```

该命令只操作本仓库管理的 dev PostgreSQL，遇到 SQL 错误立即停止，整个文件在同一事务中执行。`CREATE INDEX CONCURRENTLY` 等不能在事务中执行的语句须使用数据库工具单独执行。
