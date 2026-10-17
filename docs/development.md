# Agent 开发与模型配置

本文说明当前已提交的配置与运行方式。模型仍未接入 Agent 循环；先做一次模型请求、再接入手写 ReAct 循环，是两个不同阶段。

## 环境和检查

- Java 25 与 Maven Wrapper：`backend/` 目录下运行 `./mvnw -B -ntp verify`。
- Bun 1.3.14：`frontend/` 首次运行 `bun install`，然后运行 `bun run lint`、`bun run build`；锁文件已存在时使用 `bun install --frozen-lockfile`。
- Python 3.10+、Git：仓库根目录运行 `./scripts/check.sh repo` 检查凭证、忽略路径和空白，并运行仓库检查脚本自身的测试。
- 根目录运行 `./scripts/check.sh backend` 或 `./scripts/check.sh frontend` 执行单模块检查；`./scripts/check.sh all` 执行全部检查。

第一次克隆后启用 `.githooks/pre-commit`：

```bash
git config core.hooksPath .githooks
```

Hook 检查实际暂存的 Git Blob，所以未暂存的工作区内容不会冒D充提交内容。检查失败只输出路径和原因，不输出可能包含密钥的代码行。完整检查也覆盖未跟踪且未忽略的文件；它是轻量误提交防护，不替代专门的密钥扫描。

## 不带模型凭证运行

默认 profile 将 Spring AI 的模型自动配置全部关闭，应用可在没有模型配置的情况下启动。调试时使用本地模型桩，不在日常测试中请求线上模型、花费额度或依赖外部服务。

## 显式启用 OpenAI 兼容 ChatModel

从 `backend/` 目录启动。`.env.example` 只展示变量名；复制后填入自己的设置：

```bash
cp .env.example .env
```

文件中的 Key 不要提交。Spring Boot 不会自动读取 `.env` 文件，因此仅在当前终端显式导入变量后再启动：

```bash
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

`SPRING_PROFILES_ACTIVE=llm` 打开 `application-llm.yml`。设置 `LLM_API_KEY`、`LLM_BASE_URL` 和 `LLM_MODEL`：Base URL 提供商可用 `/v1` 结尾的 API 根路径，不带 `/chat/completions`；具体模型 ID 和请求参数取决于所选服务。`LLM_TIMEOUT` 与 `LLM_MAX_TOKENS` 是单次模型调用设置。若目标模型要求 `max_completion_tokens` 而不是 `max_tokens`，调整 profile 中的属性，不能同时设置两者。

该 profile 把模型客户端自动重试设为 0。之后若要重试，需要在 Agent 侧明确选择重试层级、最大次数、延迟和总时间；否则模型请求重试可能叠加在整轮重试上。模型最大输出 token 也不代表整次 Agent 运行的 token 总额。

默认禁用 Spring AI 2.0 的自动工具调用顾问与运行时 fallback，只让每次明确提供给调用端的工具进入调用流程；提示词、补全和工具调用内容的观测日志默认关闭。生产接入仍须在执行前校验工具名称和参数、做业务授权、限制轮次、工具并发和耗时，并向调用方返回真实的工具失败状态。

## Agent 运行预算

`loopcraft.agent` 绑定到 `AgentProperties`，默认 `max-iterations=12`、`run-timeout=120s`、`tool-timeout=15s`；环境变量可以覆盖这些值。启动时拒绝超出范围的轮次数、非正超时及大于整次运行预算的单工具预算。

当前只有配置绑定和启动校验。新增循环时，代码必须实际读取这些预算并将剩余运行时间传给每个模型请求和工具调用；不可取消的阻塞调用即使外层设了定时器也不一定会结束。配置校验通过不能当成运行时防护已经生效。

## 本地 Agent 架构参考

仓库中的 `harness-references/oh-my-pi/` 是只读外部参考，已从 Git 跟踪范围排除。阅读目的限于具体设计问题，不把其代码当成 LoopCraft 现状或直接复制实现。本项目的主线仍是自己实现清晰、有限、可观测的 ReAct 执行循环。
