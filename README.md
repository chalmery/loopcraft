# LoopCraft

LoopCraft 是用于学习和构建云端 ReAct Agent 的全栈项目。当前提供用户、租户、供应商连接/模型管理及 RustFS 通用文件存储能力；前端支持登录、个人设置、工作区成员、模型接入和平台账号管理。Agent 对话入口、ReAct 循环和会话持久化尚未接入。

- `backend/`：Java 25、Spring Boot 4.1.1、PostgreSQL、MyBatis-Plus、Maven。基础包为 `io.github.chalmery.loopcraft`。
- `frontend/`：Next.js App Router、React、TypeScript、Bun、Tailwind CSS 4、shadcn/ui、Lucide；运行与组件说明见 [前端 README](frontend/README.md)。
- 协作约束见 [根目录 AGENTS](AGENTS.md)、[后端 AGENTS](backend/AGENTS.md)、[前端 AGENTS](frontend/AGENTS.md)。实现以当前代码、配置和测试为准，变更历史通过 Git 查询。
- [ReAct 主流程调研](docs/changes/react-agent-loop/proposal.md) 保留为待讨论方案，调研中的实现快照可能已经变化，不代表当前实现或开发授权。

## 本地启动

需要 Java 25、Python 3.10+、Node.js 20.9+、Bun，以及可运行的 Docker 或 Podman。首次安装前端依赖，再回到仓库根目录启动：

```bash
cd frontend
bun install --frozen-lockfile
cd ..
./scripts/dev.sh up
```

默认前端 `http://127.0.0.1:5173`，后端 `http://127.0.0.1:8080`，PostgreSQL 17 位于 `127.0.0.1:15432`。启动器检查端口、数据库连接及应用 HTTP 就绪后才报告成功；应用与数据库仅监听本机回环地址。默认无需模型凭证。

```bash
./scripts/dev.sh up backend    # 数据库 + 后端
./scripts/dev.sh up frontend   # 仅前端
./scripts/dev.sh db up         # 仅数据库，重复执行可复用
./scripts/dev.sh db status
./scripts/dev.sh db down       # 删除本地数据库容器，保留数据卷
```

Ctrl+C / SIGTERM 停止本次启动的前后端，数据库继续运行；任一应用退出也会停止本次启动的另一个应用。数据库数据保存在本仓库专属命名卷中，脚本不提供删除持久化数据的命令。

## 环境与配置

| 用途 | Spring Profile | 可选配置文件 | 数据库 |
| --- | --- | --- | --- |
| 开发 | `dev`（默认） | `env/dev/.env` | 启动器管理的本地 PostgreSQL |
| 常规测试 | `test` | 不读取 `.env` | 禁用数据源自动装配 |
| 数据库测试 | `integration` | 不读取 `.env` | 一次性 PostgreSQL |

当前仅提供 `dev` 运行环境；`test` 和 `integration` 仅用于自动化测试。公共配置在 [application.yml](backend/src/main/resources/application.yml)，开发配置在 [application-dev.yml](backend/src/main/resources/application-dev.yml)。启动器只读 `env/dev/.env`，不加载 `backend/.env`；`env/` 整个目录仅保留在本地，不纳入 Git。首次使用时创建 `env/dev/.env`，填写需要覆盖的配置。进程环境变量优先，文件值按字面读取，不执行 shell 或展开 `$VAR`，含空格或 `#` 的值需加引号。

本地默认数据库名 `loopcraft_local`、用户 `loopcraft`、密码 `loopcraft_local_only`，仅供本机开发。启动器按本地端口和数据库字段生成 `DB_URL`，按 `SERVER_PORT` 生成 `BACKEND_URL`。需要改端口时在本地 `.env` 设置 `DB_PORT`、`SERVER_PORT`、`FRONTEND_PORT`。

容器和命名卷名称包含仓库路径的哈希；多个 checkout 同时运行仍需设置不同端口。修改数据库端口后先 `db down` 再 `db up`；修改 `.env` 不会更新已有卷中的账号和密码。默认优先 Docker，也可通过 `CONTAINER_RUNTIME=podman` 选择 Podman。

## 建表与首次管理员

应用不会自动建表或升级结构。先启动数据库，按 [用户与租户 SQL](backend/sql/identity/README.md) 依次执行三个建表文件，再按 [模型接入 SQL](backend/sql/llm/README.md) 执行来源、模型两个文件。例如：

```bash
./scripts/dev.sh db up
./scripts/dev.sh db sql identity/create_user.sql
```

SQL 路径相对 `backend/sql/`，单个文件在同一事务中执行，失败即回滚。已有数据库只执行尚未应用的脚本；依赖顺序与手动执行方式见 [SQL README](backend/sql/README.md)。

建表后在 `env/dev/.env` 中配置首次管理员，再启动后端：

```dotenv
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=admin
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-private-password
```

密码至少 12 个字符，须替换示例值。初始化只在用户表为空时创建管理员；成功后关闭 `BOOTSTRAP_ADMIN_ENABLED` 并移除初始化密码。没有预置密码或匿名注册入口。

后台配置分为「租户管理」「账号管理」两个分页 CRUD 页面，仅平台管理员可访问；支持右侧栏新增/编辑和二次确认删除，暂不提供搜索条件。平台管理员创建账号、管理租户及交接负责人；当前租户负责人通过「工作区与成员」管理成员。平台管理员账号禁止停用或删除，负责人账号需先交接，租户存在关联业务数据时禁止直接删除。API 使用 Session，写操作需要 CSRF 令牌。自行调用 API 时，先 `GET /api/auth/csrf`，从响应 `data` 读取 `headerName` 和 `token`，携带 Cookie 和该请求头登录 `POST /api/auth/login`；登录后重新获取令牌，用于后续写操作。

Session 保存在单实例内存中，空闲 30 分钟过期，服务重启需重新登录；修改密码或启停账号会使旧会话失效。前端收到 401 后返回登录页；CSRF 失效返回 HTTP 403、业务码 `40301`，前端刷新令牌后仅重试一次，业务权限不足不会重试。本地 HTTP 默认关闭 Secure Cookie，可用 `SESSION_COOKIE_SECURE` 覆盖。

登录防爆破默认按账号和来源 IP 双重限制：同一账号在 15 分钟窗口内连续失败 5 次后冷却 15 分钟，用户名大小写共用计数；成功认证清空该账号的失败次数。同一 IP 每 15 分钟最多放行 30 次登录尝试（含成功），更换账号或 Session 不会重置来源计数。不存在或已停用的账号同样计数；并发认证会预占账号名额，超限请求在查库和密码计算前返回 HTTP/业务码 `429`、统一提示及 `Retry-After` 秒数，拒绝请求不会延长冷却。CSRF 和请求格式校验仍先执行。

限制参数位于 `application.yml` 的 `loopcraft.identity.login-protection`，可通过 Spring 环境变量覆盖，例如 `LOOPCRAFT_IDENTITY_LOGINPROTECTION_ACCOUNTFAILURES`、`LOOPCRAFT_IDENTITY_LOGINPROTECTION_IPATTEMPTS`、`LOOPCRAFT_IDENTITY_LOGINPROTECTION_WINDOW`、`LOOPCRAFT_IDENTITY_LOGINPROTECTION_MAXENTRIES`。计数保存在单实例内存，重启清空，不跨实例共享；最多保留 10000 条账号/IP 记录，过期记录在后续登录时回收，容量耗尽时拒绝新记录而不淘汰有效限制。IP 取服务端 `getRemoteAddr()`，不直接读取 `X-Forwarded-For` / `Forwarded`；当前 Next.js 转发下用户会共享代理 IP 的额度，应按访问规模调整阈值。部署时如需真实客户端 IP，须在可信代理边界配置容器的地址解析并阻止绕过代理直连；多实例部署需改用共享限流存储或网关统一限流。

## 模型接入

进入租户后选择「模型接入」。同租户可为同一供应商建立多个连接，每个连接维护多个模型。普通成员只读，当前负责人和平台管理员可新增、编辑、启停、删除，以及查询供应商候选并勾选导入。来源名在租户内唯一，实际模型 ID 在来源内唯一且区分大小写。重复导入跳过已有记录，不覆盖名称或重新启用。

首版支持 OpenAI 兼容和 Anthropic 兼容模型目录。API 根地址由用户完整填写，例如 `https://api.example.com/v1`，服务端只追加 `/models`。保存连接不调用供应商；可暂不配置 API Key，查询候选前须配置。候选查询不自动写库；请求 15 秒超时，不跟随重定向，响应上限 2 MiB，单次上游目录最多 2000 条，每页候选最多 100 条。OpenAI 列表在后端分页，Anthropic 使用 `after_id`；目录变化后可重新查询。401/403 归一化为 502 认证失败，超时 504，仅 404/405/501 视为列表不支持，可手动添加。协议依据 [OpenAI Models](https://developers.openai.com/api/reference/resources/models/methods/list) 和 [Anthropic Models](https://platform.claude.com/docs/en/api/models/list)。

保存非空密钥前，在 `env/dev/.env` 配置 `LLM_ENCRYPTION_KEY`：使用 `openssl rand -base64 32` 在本地生成，将结果保存到忽略提交的配置文件。主密钥需安全备份并保持稳定，更换后旧密文无法解密；目前没有自动轮换功能。未配置时仍可查看、管理无凭证来源和手动模型，但不能保存非空密钥或解密查询。密钥以带随机 nonce 的 AES-256-GCM 加密，租户 ID 作为附加认证数据；接口只返回 `hasApiKey`，不返回密钥、尾号或密文。编辑留空保留，明确勾选才清除，同时更换和清除会拒绝。

供应商查询默认只连接公网地址：在实际连接时校验全部 DNS 结果，拒绝回环、内网、链路本地和保留地址，并直接使用已校验的地址建立连接，避免二次解析绕过。域名同时解析到公网和内网时整次拒绝；不读取系统代理，不跟随重定向。地址受限时返回 502。HTTP 客户端使用 Spring Boot 管理版本的 Apache HttpClient，以支持连接时的 DNS 校验。

确需访问可信内网服务时，由部署者在本地环境设置 `LLM_DISCOVERY_ALLOWED_INTERNAL_HOSTS`，以逗号分隔精确主机名或 IP，例如 `models.internal.example`；不支持通配符或子域自动放行。此配置授予该主机的内网访问能力，不能由租户接口修改。未配置时默认不放行；保存连接仍只校验字段，不发起 DNS 或供应商请求。

停用连接保留各模型自身状态，仍允许维护。删除来源和模型在事务中完成；新增表会继续保护有关联数据的租户不被删除。当前没有模板表或引用计数，未来模板需通过真实关联与限制删除外键接入删除保护；未实现模板运行、参数、计费或 Agent 推理调用。

## Skill 管理

工作区侧栏的“插件”分组提供带图标的 Skill 管理入口，支持卡片/列表切换、来源筛选、搜索与分页；顶部“本地”显示当前工作区资源，“市场”暂未开放。卡片展示最新内容的文件数与保存时间，来源数量按整个租户统计，不受分页或关键词影响。详情宽屏并排展示基本信息与内容版本，窄屏纵向排列；普通成员可新增、修改、启停和删除同租户自建 Skill，内置 Skill 仅负责人或平台管理员可管理。来源只读，HTTP 新增不接受内置来源。平台管理员沿用已有租户资源访问能力，不改动仅负责人可用的成员接口。

新建支持在线编写，或先导入 ZIP 再进入编辑页；两种新建方式均与详情一致，按“基本信息 / 文件与版本”标签页展示，切换保留草稿。新建页不重复提供 ZIP 上传入口，导入统一从列表发起。导入时用 SKILL.md frontmatter 的 name 预填标识和名称、description 预填描述，保存前可编辑；字段缺失或无法解析时手动补充。首次导入后的基本信息（名称、描述、标签、状态）与包内文件互不自动覆盖，已有 Skill 上传新包也不更新这些字段。标识在同租户内唯一且创建后不改；首次保存同时创建 Skill 与 v1。内容编辑支持文本新增/修改/移除与二进制文件替换，也可上传 ZIP 替换本次草稿的完整文件集合。每次保存追加不可变整包版本，旧版本只读，不做回退和版本说明，不执行脚本或接入 Agent。

先按 [Skill SQL 顺序](backend/sql/skill/README.md) 手动建表，并启用下文的 RustFS 存储。部署侧须预先建立私有 Bucket，默认 `loopcraft-skills`，可通过 `SKILL_BUCKET` 覆盖；应用不自动建桶。对象 Key 由服务端按租户、Skill 和随机标识生成，接口不返回存储地址。存储未启用时列表和基本信息仍可读取，首次创建、读文件或保存版本返回 503。

包限制：ZIP 最多 20 MiB，最多 200 个文件，单文件最多 2 MiB，解压后总内容最多 10 MiB；文件路径最长 240 字符。根目录必须有非空 UTF-8 `SKILL.md`，兼容单层包目录包装。不解压到操作系统目录，不执行压缩包内文件；二进制按原字节保存，文件被当作普通数据，不跟随链接。当前不提供版本下载或从历史重新编辑。

删除需要确认，先隐藏记录，后台每分钟按引用清理全部版本对象，失败保留记录重试。基本信息保存使用修订号，内容保存使用最新版本基线与请求 UUID；并发冲突保留页面草稿、提示刷新，重复提交同一内容不会多建版本。

API 位于 `/api/tenants/{tenantId}/skills`：GET 列表（`page/size/q` 及可选 `origin=CUSTOM|SYSTEM`，响应在分页字段外带租户级 `counts.custom/system`；每条带 `fileCount/contentUpdatedAt`）、POST multipart 首次创建；`/{id}` GET/PATCH/DELETE；`/{id}/versions` GET 分页历史、POST multipart 保存；`/{id}/versions/{number}` GET 文件集合；`/package-preview` POST multipart 校验 ZIP（不落存储）。multipart 的 `input` 是 JSON 字符串，含 `requestId` 和按 files 顺序的 `paths`，保存还需 `baseVersion`；压缩包可直接用 `archive` 替代 files/paths。PATCH 提交完整 name/description/tags/enabled/revision；不接收包内字段改写基本信息。

## 文件存储

后端 `storage` 功能包使用 AWS SDK for Java v2，通过 S3 协议连接 RustFS，固定使用 path-style 寻址。该依赖只用于对象读写，不参与 Agent 循环；接入方式见 [RustFS Java SDK 文档](https://docs.rustfs.com/en/developer/sdk/java)。

`FileStorage` 为内部能力，提供上传、元数据查询、流式读取、幂等删除和短时下载链接。每次调用显式传入 `ObjectLocation(bucket, key)`，模块不生成业务 ID，不固定桶或 Key 前缀，也不依赖租户、Skill 或制品概念。相同桶和 Key 的上传会覆盖当前对象；需要版本管理或防覆盖时由业务设计规则。桶的创建、权限、版本和生命周期由部署者管理，应用启动不访问远端、不自动建桶或设置公开访问。

默认关闭，启用时在忽略提交的 `env/dev/.env` 配置：

```dotenv
STORAGE_ENABLED=true
STORAGE_ENDPOINT=http://127.0.0.1:9000
STORAGE_REGION=us-east-1
STORAGE_ACCESS_KEY=<your-access-key>
STORAGE_SECRET_KEY=<your-secret-key>
```

使用 S3 API 地址，不要填控制台端口。启用而缺少凭证或配置不合法时启动失败；没有默认账号密码。连接仅使用显式配置的凭证，不从 AWS 默认凭证链发现账号。应用账号应仅被授予所需桶的对象权限。

其他模块通过构造器注入 `FileStorage` 后调用，例如（`key` 由调用业务生成）：

```java
var location = new ObjectLocation(bucket, key);
StoredFile uploaded = storage.upload(location, sourcePath, "说明.txt", "text/plain");
StoredFile metadata = storage.stat(location);
try (FileDownload download = storage.open(location)) {
    download.stream().transferTo(outputStream);
}
URI temporaryLink = storage.presignDownload(location);
storage.delete(location);
```

上传使用本地 `Path`，源文件在调用期间须保持不变，SDK 从磁盘读取和重试，不将整个文件加载进内存；HTTP 业务接入时可先将上传流写入临时文件，并在调用后清理。调用方负责关闭下载流。默认单文件上限 20 MiB（`STORAGE_MAX_FILE_SIZE=20MB`），单次 PUT 不超过 5 GiB；暂不提供分片或断点续传。零字节文件允许上传，中文原名保存在对象元数据中。

默认连接超时 3 秒、读取超时 30 秒、SDK 调用总超时 60 秒，分别用 `STORAGE_CONNECT_TIMEOUT`、`STORAGE_READ_TIMEOUT`、`STORAGE_REQUEST_TIMEOUT` 覆盖。读取超时限制单次网络等待；流式消费的整体时间由调用业务控制。临时链接默认 5 分钟，`STORAGE_DOWNLOAD_LINK_TTL` 可设为 1 秒至 1 小时；链接使用配置的 endpoint，必须能被下载方访问，不能在签名后替换域名。持有链接者在有效期内可下载，不再经过应用 Session 校验，不能把链接作为永久文件引用或写入日志。

该模块不开放 HTTP 接口，也不建立文件元数据表。业务调用前须校验权限，从可信业务记录获取桶和 Key，不将客户端地址直接透传；业务归属、文件引用持久化及数据库事务失败后的文件清理由调用模块负责。读取/查询返回 404 时抛出 `FileStorageException(NOT_FOUND)`（HEAD 不能区分缺桶和缺对象），其余远端故障归一化为 `UNAVAILABLE`，不向上层传播 SDK 的原始异常文本；本地文件 I/O 错误保留为 `IOException`。

常规测试使用随机端口的本地 HTTP 桩运行真实 S3 SDK，不连接开发 RustFS，不读取存储凭证。

## 日志与排查

本地启动输出在 `.run/backend.log`、`.run/frontend.log`，每次启动覆盖；应用日志持续追加到 `.run/logs/loopcraft.log`：

```bash
tail -F .run/logs/loopcraft.log
```

直接运行 JAR 或 Maven 时，应用日志默认写入当前目录下 `logs/loopcraft.log`，可用 `LOG_DIR` 修改。单文件达到 10MB 或跨天后压缩滚动，归档保留 7 天，总量上限 200MB。`LOG_LEVEL_ROOT`、`LOG_LEVEL_APP` 默认 INFO。

请求通过 `X-Trace-Id` 响应头返回追踪 ID，日志通过 `traceId` 关联。可传入 1–64 位字母、数字、点、下划线或短横线的同名请求头，否则生成 UUID。默认不记录模型内容；测试 Profile 仅输出控制台。

## 检查与 Git hooks

从仓库根目录运行：

```bash
./scripts/check.sh repo         # 内容检查和仓库脚本测试
./scripts/check.sh backend      # 后端测试与 JAR 打包，不请求真实模型
./scripts/check.sh frontend     # 格式、lint、类型检查与 Next.js 构建
./scripts/check.sh all          # 上述全部，不启动数据库
./scripts/check.sh integration  # 临时 PostgreSQL，验证数据库相关行为
```

`./scripts/dev.sh test <范围>` 使用同一实现。测试不加载 `.env`，并清除继承的业务凭证、JVM 配置注入及日志路径覆盖。集成测试使用独立随机端口和临时存储，成功、失败或中断后清理容器，不复用业务数据库；强制 SIGKILL 或宿主宕机后需检查 `loopcraft-test-*` 残留。

首次下载依赖和拉取镜像需要网络，缓存就绪后无需外部业务服务。受限环境可设置 `MAVEN_REPO_LOCAL=/tmp/loopcraft-m2` 使用可写 Maven 缓存。CI 独立执行常规检查和 PostgreSQL 集成测试。

克隆后启用原生 Git hooks：

```bash
git config core.hooksPath .githooks
```

`pre-commit` 检查暂存内容的空白、常见凭证格式及前端暂存快照的格式和 lint；`pre-push` 检查当前工作区前端并构建。Hooks 不自动修复、暂存或提交；CI 再检查实际推送版本。内容检查覆盖未跟踪且未忽略的文件，但不替代完整密钥扫描。
