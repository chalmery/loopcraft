# Skill 管理 SQL

依赖 identity 的三张表，按顺序手动执行：

```sh
./scripts/dev.sh db sql skill/create_skill.sql
./scripts/dev.sh db sql skill/create_skill_version.sql
./scripts/dev.sh db sql skill/create_skill_tag.sql
```

`lc_skill` 保存独立的平台基本信息，`lc_skill_version` 保存整包内容版本和内部对象引用，`lc_skill_tag` 保存一对多标签。不解析文件元数据写入基本信息，不记录版本说明，不提供回退。

同租户自建标识唯一且创建后不可修改；SYSTEM 内置来源只能由可信初始化数据写入，HTTP 新增一律 CUSTOM。未提供内置包初始化内容，勿自行将已有自建资源提升为内置。平台管理员沿用现有租户资源管理权限，普通成员只能管理自己所属租户的自建 Skill，内置仅负责人/平台管理员能写。

删除先标记 `deleted` 并隐藏，保留版本和对象引用；后台每分钟最多处理 20 个，清理成功后删除数据库记录。存储故障时保留待清理数据并重试，因此待清理记录仍保护租户外键。测试可用 `loopcraft.skill.cleanup-enabled=false` 关闭周期任务。

版本写入时使用新对象 Key，事务回滚尽力删除新对象；如果回滚和对象删除同时失败，需要按日志与 RustFS 对象清单核对孤立对象。应用不在数据库事务外自动创建 Bucket，也不把对象存储视为数据库事务的一部分。

集成测试在一次性 PostgreSQL 中执行这些 SQL，并使用内存 FileStorage；不会连接开发库或真实 RustFS。
