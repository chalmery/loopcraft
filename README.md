# LoopCraft

LoopCraft 是用于学习和构建云端 ReAct Agent 的全栈项目。当前提供用户、租户管理；前端支持登录、个人设置、工作区成员和平台账号管理。Agent 对话入口、ReAct 循环和会话持久化尚未接入。

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

当前仅提供 `dev` 运行环境；`test` 和 `integration` 仅用于自动化测试。公共配置在 [application.yml](backend/src/main/resources/application.yml)，开发配置在 [application-dev.yml](backend/src/main/resources/application-dev.yml)。启动器只读 `env/dev/.env`，不加载 `backend/.env`；可从 [开发示例](env/dev/.env.example) 复制。进程环境变量优先，文件值按字面读取，不执行 shell 或展开 `$VAR`，含空格或 `#` 的值需加引号；真实 `.env` 已忽略提交。

本地默认数据库名 `loopcraft_local`、用户 `loopcraft`、密码 `loopcraft_local_only`，仅供本机开发。启动器按本地端口和数据库字段生成 `DB_URL`，按 `SERVER_PORT` 生成 `BACKEND_URL`。需要改端口时在本地 `.env` 设置 `DB_PORT`、`SERVER_PORT`、`FRONTEND_PORT`。

容器和命名卷名称包含仓库路径的哈希；多个 checkout 同时运行仍需设置不同端口。修改数据库端口后先 `db down` 再 `db up`；修改 `.env` 不会更新已有卷中的账号和密码。默认优先 Docker，也可通过 `CONTAINER_RUNTIME=podman` 选择 Podman。

## 建表与首次管理员

应用不会自动建表或升级结构。先启动数据库，按 [用户与租户 SQL](backend/sql/identity/README.md) 依次执行三个建表文件。例如：

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
