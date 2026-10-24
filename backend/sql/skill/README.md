# Skill 管理 SQL

依赖 identity 的三张表，按顺序手动执行：

```sh
./scripts/dev.sh db sql skill/create_skill.sql
./scripts/dev.sh db sql skill/create_skill_version.sql
./scripts/dev.sh db sql skill/create_skill_tag.sql
./scripts/dev.sh db sql skill/add_skill_creation_digest.sql
./scripts/dev.sh db sql skill/create_skill_object_upload.sql
```

`lc_skill` 保存独立的平台基本信息，`lc_skill_version` 保存整包内容版本和内部对象引用，`lc_skill_tag` 保存一对多标签。不解析文件元数据写入基本信息，不记录版本说明，不提供回退。

同租户自建标识唯一且创建后不可修改；SYSTEM 内置来源只能由可信初始化数据写入，HTTP 新增一律 CUSTOM。未提供内置包初始化内容，勿自行将已有自建资源提升为内置。平台管理员沿用现有租户资源管理权限，普通成员只能管理自己所属租户的自建 Skill，内置仅负责人/平台管理员能写。

删除先标记 `deleted` 并隐藏，保留版本和对象引用；后台每分钟最多处理 20 个 Skill，每个 Skill 每次最多清理 20 个版本对象，清理成功后删除数据库记录。存储故障时保留待清理数据并重试，因此待清理记录仍保护租户外键。测试可用 `loopcraft.skill.cleanup-enabled=false` 关闭周期任务。

版本写入先在独立事务中登记 `lc_skill_object_upload` 引用，再持引用行锁上传不可变对象；上传时不持租户或 Skill 业务锁。发布事务锁住引用和业务记录，检查权限、基线与幂等载荷后插入版本并删除上传引用。回滚后清理失败会保留引用；后台扫描超过 15 分钟的引用，跳过仍被上传/提交持锁的对象，并再次确认无已提交版本引用后才删除。进程重启后沿用同一恢复路径。当前 SDK 的超时和包数量/大小限制仍生效。应用不自动创建 Bucket，也不把对象存储视为数据库事务的一部分。

已有环境升级仅执行新增的 `add_skill_creation_digest.sql` 和 `create_skill_object_upload.sql`，不要重跑建表脚本。创建摘要绑定首次基本信息、标签和文件内容，后续元数据编辑不覆盖摘要；同请求标识但不同操作者、有效载荷或保存基线返回冲突。升级前历史记录的摘要为空，不能重用历史创建请求标识，请生成新标识。

集成测试在一次性 PostgreSQL 中执行这些 SQL，并使用内存 FileStorage；不会连接开发库或真实 RustFS。
