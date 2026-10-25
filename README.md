# LoopCraft

![Java](https://img.shields.io/badge/Java-25-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen) ![Next.js](https://img.shields.io/badge/Next.js-16-black) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue) ![Redis](https://img.shields.io/badge/Redis-7.4-red) ![RustFS](https://img.shields.io/badge/RustFS-S3-brown)

LoopCraft 是一个用于**学习和构建云端 ReAct Agent** 的全栈项目：手写「模型 → 工具 → 观察结果」执行循环，配套多租户的身份、模型接入、Agent 模板与 Skill 管理。循环本体保持显式、可读、可测试，不被框架接管。

## 功能总览

| 能力域 | 说明 | 状态 |
| --- | --- | --- |
| ReAct 执行循环 | `agent.core` 手写循环：预算与超时、响应判定顺序、call id 配对、终止状态机与错误回填；24 项假模型确定性测试 | ✅ |
| Agent 运行通道 | chat 受理（202 + runId）、SSE 事件订阅（断线按序号续传）、Redis Stream 热通道 + PostgreSQL 持久化兜底 | ✅ |
| 内置工具 | `list_skills` / `read_skill` 只读工具，按模板绑定技能与版本解析 | ✅ |
| 身份与租户 | 账号、租户、成员角色、Session + CSRF、登录防爆破 | ✅ |
| 模型接入 | 租户供应商连接（OpenAI / Anthropic 兼容）、加密 API Key、目录发现与导入 | ✅ |
| Agent 模板 | 租户共享模板：模型、提示词、温度、迭代与超时预算、Skill 绑定 | ✅ |
| Skill 管理 | 版本化技能包（ZIP + SKILL.md）、RustFS 对象存储、软删除与清理补偿 | ✅ |
| 多轮会话 / 消息历史 / OpenAPI | 见后续路线 | 📋 |

📋 完整路线见 [核心流程路线图](docs/changes/react-agent-loop/roadmap.html)（可交互，悬停条目在循环图上定位作用位置）；设计取舍与决策记录见 [ReAct 提案](docs/changes/react-agent-loop/proposal.md)。

## 下一步计划

按当前优先级排列，每项的触发条件与提案依据见路线图：

- [ ] **补测试欠账（最优先）**：HTTP / SSE 接口级测试、Skill 工具行为测试、模型链路与适配器离线桩、真实模型端到端联调
- [ ] **会话与状态**：session 多轮会话容器、全量消息历史落库、run 取消接口、运行恢复（checkpoint）
- [ ] **循环增强**：Anthropic 协议运行时、token 级流式输出、并发工具执行、上下文自动压缩、自动重试与续写、运行中插话
- [ ] **工具面**：MCP 动态工具、业务写操作工具（需授权与幂等语义）、结构感知的结果截断
- [ ] **通道与开放**：前端聊天界面（SSE 消费）、run 列表查询、OpenAPI（API Token 认证）、多实例水平扩展
- [ ] **可观测**：脱敏诊断日志、运行审计查询、token 用量与成本计量

## 架构速览

```text
frontend/  Next.js App Router · React · TypeScript · Bun · Tailwind 4 · shadcn/ui
backend/   Java 25 · Spring Boot MVC（虚拟线程）· MyBatis-Plus
             agent.core      ReAct 循环本体（仅依赖 Spring AI 类型，无 DB/HTTP/Redis）
             agent.*         受理编排、SSE 通道、运行持久化
             tool / llm / template / skill / identity / storage / redis
           PostgreSQL 17（业务与运行记录）· Redis 7.4（事件热通道）· RustFS（S3 对象存储）
```

协作约束见 [根目录 AGENTS](AGENTS.md)、[后端 AGENTS](backend/AGENTS.md)、[前端 AGENTS](frontend/AGENTS.md)；表结构总览见 [database-schema.md](docs/database-schema.md)。实现以当前代码、配置和测试为准，变更历史通过 Git 查询。

## 快速开始

需要 Java 25、Python 3.10+、Node.js 20.9+、Bun，以及可运行的 Docker 或 Podman。首次安装前端依赖，再回到仓库根目录启动：

```bash
cd frontend
bun install --frozen-lockfile
cd ..
./scripts/dev.sh up
```

默认前端 `http://127.0.0.1:5173`，后端 `http://127.0.0.1:8080`，PostgreSQL 位于 `127.0.0.1:15432`，Redis 位于 `127.0.0.1:16379`，RustFS API 位于 `127.0.0.1:9000`（控制台 `http://127.0.0.1:9001/rustfs/console/`）。启动器依次准备数据库、RustFS、Skill 私有桶和 Redis，再启动应用并检查 HTTP 就绪；各服务仅监听本机回环地址，默认无需模型凭证。

### 服务管理

```bash
./scripts/dev.sh up backend    # 数据库 + Redis + 文件存储 + 后端
./scripts/dev.sh up frontend   # 仅前端
./scripts/dev.sh db up|status|down          # PostgreSQL（down 删容器留数据卷）
./scripts/dev.sh storage up|status|down     # RustFS
./scripts/dev.sh redis up|status|down       # Redis
```

Ctrl+C / SIGTERM 停止本次启动的前后端，数据库、RustFS 与 Redis 继续运行；任一应用退出也会停止另一个。数据保存在持久命名卷中，脚本不提供删除持久化数据的命令。

## 环境与配置

| 用途 | Spring Profile | 可选配置文件 | 数据库 |
| --- | --- | --- | --- |
| 开发 | `dev`（默认） | `env/dev/.env` | 启动器管理的本地 PostgreSQL |
| 常规测试 | `test` | 不读取 `.env` | 禁用数据源自动装配 |
| 数据库测试 | `integration` | 不读取 `.env` | 一次性 PostgreSQL |

当前仅提供 `dev` 运行环境。公共配置在 [application.yml](backend/src/main/resources/application.yml)，开发配置在 [application-dev.yml](backend/src/main/resources/application-dev.yml)。首次使用创建 `env/dev/.env`（整个 `env/` 不入 Git），填写需要覆盖的配置；进程环境变量优先，文件值按字面读取不展开 `$VAR`。

本地默认数据库 `loopcraft_local` / `loopcraft` / `loopcraft_local_only`，仅供本机开发。改端口用 `DB_PORT`、`SERVER_PORT`、`FRONTEND_PORT`；修改数据库端口后先 `db down` 再 `db up`。容器与命名卷名称含仓库路径哈希，多 checkout 并行需设不同端口。默认优先 Docker，可用 `CONTAINER_RUNTIME=podman` 选择 Podman。

## 建表与首次管理员

应用不会自动建表或升级结构。先启动数据库，按各模块 README 顺序执行建表文件（identity → llm → skill → template → agent）：

```bash
./scripts/dev.sh db up
./scripts/dev.sh db sql identity/create_user.sql
```

SQL 路径相对 `backend/sql/`，单个文件在同一事务中执行，失败即回滚；依赖顺序见 [SQL README](backend/sql/README.md)，表关系总览见 [database-schema.md](docs/database-schema.md)。

建表后在 `env/dev/.env` 配置首次管理员并启动后端：

```dotenv
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=admin
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-private-password
```

密码至少 12 位，须替换示例值。初始化只在用户表为空时执行；成功后关闭开关并移除密码，没有预置密码或匿名注册。

「平台管理」提供租户、账号管理（仅平台管理员）；租户负责人通过「租户设置」管理成员。API 使用 Session，写操作需 CSRF 令牌：自行调用时先 `GET /api/auth/csrf` 取 `headerName` 和 `token`，携带 Cookie 登录后再取新令牌用于写操作。Session 单实例内存、空闲 30 分钟过期；登录防爆破按账号与来源 IP 双重限制（默认 15 分钟窗口内账号 5 次、IP 30 次，超限返回 429 与 `Retry-After`），参数见 `loopcraft.identity.login-protection`，可经 Spring 环境变量覆盖；计数不跨实例共享，多实例部署需共享限流存储或网关限流。

## 模型接入

进入租户后选择「模型接入」。同租户可为同一供应商建多个连接，每个连接维护多个模型；普通成员只读，负责人和平台管理员可管理并查询供应商候选导入。来源名租户内唯一，模型 ID 来源内唯一。

- **协议**：OpenAI 兼容与 Anthropic 兼容目录；API 根地址完整填写（如 `https://api.example.com/v1`），服务端只追加 `/models`。保存连接不调供应商，查询候选须先配 Key。
- **安全**：API Key 以 AES-256-GCM（随机 nonce + 租户绑定）加密存储，接口只返回 `hasApiKey`。配置 `LLM_ENCRYPTION_KEY`（`openssl rand -base64 32` 生成，丢失后旧密文不可解密）。供应商查询只连公网地址，连接时校验全部 DNS 结果拒绝内网/回环，不跟随重定向；可信内网服务由部署者配置 `LLM_DISCOVERY_ALLOWED_INTERNAL_HOSTS` 放行。
- **运行时**：Agent 运行按模板引用解密构建 OpenAI 兼容模型实例（Anthropic 协议运行时暂未接入，运行返回 503）；被模板引用的模型/连接受外键保护不可删除。

## Agent 模板配置

侧栏「Agent → 模板配置」提供租户共享模板的列表与整页编辑，按「基本信息 / 工具 / 上下文 / 运行设置」四个页签组织，统一保存、跨页签校验和未保存离开保护。

运行设置即循环预算：最大循环次数仅统计主模型调用，超时以分钟表示支持小数；工具页签绑定 Skill 并锁定内容版本；上下文页签的摘要模型、预算与保留条数已存储、待会话压缩能力启用后消费（见路线图）。模板被运行链路引用校验：停用、模型不可用或运行参数为 0 时受理明确失败，不静默回退。

## Agent 运行接口

chat 受理与 SSE 订阅（所有租户成员可发起；需启用 Redis）：

```bash
# 受理运行（需先登录并携带 CSRF 头，见「建表与首次管理员」）
curl -X POST http://127.0.0.1:8080/api/tenants/1/agent/chat \
  -H 'Content-Type: application/json' -b cookies.txt \
  -d '{"templateId": 1, "message": "按笔记技能的规范总结这段内容"}'
# → 202 {"code":202,"data":{"runId":"<uuid>"}}

# 订阅事件流（EventSource 断线自动重连并携带 Last-Event-ID）
curl -N http://127.0.0.1:8080/api/tenants/1/agent/runs/<runId>/events -b cookies.txt
```

事件集（`id` 为单调序号，内容不含工具参数与结果）：

| 事件 | 数据 |
| --- | --- |
| `run_started` | templateId |
| `iteration_started` | iteration、maxIterations |
| `tool_called` / `tool_finished` | callId、name；结束附 ok、truncated、durationMs |
| `run_finished` | status、answer（成功时）、reason（失败时）、iterations、toolCalls |

事件先落 `lc_agent_run_event`（真相源）再写 Redis Stream（实时热通道，终态后短时保留）；断线重连按序号重放，热通道缺失时轮询数据库兜底；连接断开不取消任务，运行记录与终态落 `lc_agent_run`。预算耗尽、模型错误、超时等终态语义见提案第 9 节。

## Skill 管理

侧栏「插件」分组提供 Skill 管理：卡片/列表切换、来源筛选、搜索分页；普通成员可管理自建 Skill，内置 Skill 仅负责人或平台管理员可管理。

- **包格式**：ZIP ≤ 20 MiB、≤ 200 文件、单文件 ≤ 2 MiB、解压后 ≤ 10 MiB；根目录必须有非空 UTF-8 `SKILL.md`。不解压到系统目录、不执行包内文件。
- **版本**：每次保存追加不可变整包版本（对象存储），旧版本只读；基本信息与包内文件互不自动覆盖，保存带修订号与幂等请求保护。
- **Agent 接入**：`list_skills` / `read_skill` 两个内置只读工具按模板绑定解析技能与版本；`read_skill` 返回绑定版本的 `SKILL.md` 内容，未绑定 slug、存储不可用等可预期失败回填错误观察，不中断运行。
- **API**：`/api/tenants/{tenantId}/skills`（列表/创建/编辑/删除/版本历史/内容/包预检），细节见 [Skill SQL README](backend/sql/skill/README.md) 与前端页面。

## 文件存储

后端 `storage` 包通过 AWS SDK v2 以 S3 协议连接 RustFS（path-style），`FileStorage` 提供上传、元数据、流式读取、幂等删除与短时下载链接；模块不开放 HTTP 接口、不建元数据表，桶与 Key 由调用业务决定。

本地启动器固定 RustFS 镜像摘要并管理容器；未配置凭证时自动生成并保存在忽略提交的 `.run/storage.env`。默认关闭，启用时在 `env/dev/.env` 配置：

```dotenv
STORAGE_ENABLED=true
STORAGE_ENDPOINT=http://127.0.0.1:9000
STORAGE_REGION=us-east-1
STORAGE_ACCESS_KEY=<your-access-key>
STORAGE_SECRET_KEY=<your-secret-key>
```

使用 S3 API 地址（不要填控制台端口）；启用而缺凭证会启动失败，没有默认账号密码，不从 AWS 默认凭证链发现凭证。超时与临时链接 TTL 可经 `STORAGE_*` 覆盖；下载链接持有者可绕过应用 Session，不能作为永久引用写入日志。外部 S3 服务设 `RUSTFS_MANAGED=false`，启动器只验证 Skill 桶存在。

## Redis

本地启动器默认创建 Redis 7.4 容器（`127.0.0.1:16379`，`REDIS_PORT` 可改，改前先 `redis down`）；`REDIS_ENABLED=false` 同时跳过容器与装配；配置非回环 `REDIS_HOST` 表示使用外部实例。

后端经 `spring-data-redis` + Lettuce 直连，不引入 Boot starter：仅 `REDIS_ENABLED=true` 时装配连接工厂与 `StringRedisTemplate`，并在 `/actuator/health` 加真实 PING 探测。Agent 事件通道使用 Stream 作为实时热通道（见「Agent 运行接口」）；会话与登录防爆破计数仍在单实例内存，迁移共享存储需另行设计。

## 日志与排查

本地启动输出在 `.run/backend.log`、`.run/frontend.log`（每次覆盖），应用日志追加至 `.run/logs/loopcraft.log`（10MB/跨天滚动压缩，保留 7 天，上限 200MB）。请求经 `X-Trace-Id` 响应头返回追踪 ID 并在日志中关联；默认不记录模型内容。

## 检查与 Git hooks

```bash
./scripts/check.sh repo         # 内容检查和仓库脚本测试
./scripts/check.sh backend      # 后端测试与 JAR 打包，不请求真实模型
./scripts/check.sh frontend     # 格式、lint、类型检查与 Next.js 构建
./scripts/check.sh all          # 上述全部
./scripts/check.sh integration  # 一次性 PostgreSQL，验证数据库相关行为
```

`./scripts/dev.sh test <范围>` 同一实现；测试不加载 `.env` 并清除继承的业务凭证。克隆后启用原生 hooks：

```bash
git config core.hooksPath .githooks
```

`pre-commit` 检查暂存内容与前端暂存快照；`pre-push` 执行当前工作区前端完整检查。Hooks 不自动修复或提交，CI 独立检查检出版本。
