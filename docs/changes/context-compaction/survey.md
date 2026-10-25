# 上下文压缩调研：LoopCraft 模板配置与主流实现方式

调研日期：2026-10-25。范围：本仓库当前代码，以及本地 `harness-references/` 下 Codex、CCB（第三方 `claude-code-best/claude-code`，非 Anthropic 官方实现证据）、OpenCode、DeepSeek Harness 四个项目中与上下文压缩相关的文件；oh-my-pi 未发现对话级压缩实现，本文不涉及。

本文回答两个问题：**主流 Agent Harness 用哪些方式控制上下文增长；LoopCraft 的模板配置应该往哪个方向收敛。** 结论与建议均为待讨论内容，不是已批准实现。

证据边界：本文依据本地快照源码的静态阅读与关键常量摘录，未运行参考项目或验证压缩效果；各项目参数（阈值、比例、上限）以调研当日文件为准，可能随版本变化。

## 1. 核心结论

1. LoopCraft 的 `lc_agent_template` 表已预留 `context_budget_tokens`、`summary_model_id`、`keep_recent_messages` 三个字段，但 Agent 侧代码零使用，循环内消息无限增长，唯一间接约束是 `maxIterations` 与 `maxToolCalls`。
2. 主流方式呈阶梯：单项工具结果截断 → 滑动窗口 → 无模型的消息级修剪 → **LLM 摘要压缩（事实标准）** → 溢出后抢救 → 结构化世界状态 / 检索式记忆（主流核心均未采用）。摘要压缩的通用结构是 `[system] + [摘要] + [最近尾部]`。
3. 各家共识的三条工程约束：工具调用与结果必须成对保留；压缩会打断 prompt cache，阈值需留余量；token 计量是接线前的地基能力。
4. 对 LoopCraft 的建议分两步（见第 6 节）：先在 run 内做无模型的阈值检查与旧工具结果清理，会话落地后再启用完整摘要压缩。

## 2. LoopCraft 当前实现盘点

以下均为当前代码事实，不含计划：

- 模板字段已预留：`backend/.../template/model/AgentTemplate.java` 含 `contextBudgetTokens`、`summaryModelId`、`keepRecentMessages`；全仓库检索确认 Agent 包内没有任何代码读取这三个字段。`AgentChatService.budget()` 只把 `maxIterations` 与 `timeoutMinutes` 转入 `RunBudget`，后者不含 token 预算。
- 循环消息结构：`backend/.../agent/core/Agent.java` 以 `system + 单条 user` 起步，逐轮追加 assistant（含 tool calls）与工具结果，消息列表无上限。
- 工具层已有单项截断：`backend/.../tool/ToolExecutor.java` 的结果 JSON 携带 `truncated` 标志，即"层级 0"已存在。
- 路线图定位：`docs/changes/react-agent-loop/roadmap.html` 将"上下文自动压缩"列为后续阶段，触发场景是"会话逼近上限时"，排在 SSE 通道与会话之后。

当前每次 run 是单轮独立运行（单条 user 消息起步，无跨 run 历史），因此上下文膨胀的真实来源是：一次 run 内多轮工具循环的累积输出，以及将来多轮会话的历史回放。后者是压缩的主战场，与路线图顺序一致。

## 3. 主流方式阶梯

| 层级 | 方式 | 是否调用 LLM | 代表实现 |
|---|---|---|---|
| 0 | 单项工具结果截断 | 否 | LoopCraft 已有；各家普遍在工具层做 |
| 1 | 滑动窗口（保尾部 N 条） | 否 | 无家单独使用，均作为摘要压缩的"尾部保留"组件 |
| 2 | 消息级修剪（替换旧工具结果） | 否 | CCB microCompact |
| 3 | 摘要压缩（compact/summarize） | 是 | Codex、CCB autoCompact、OpenCode、DeepSeek Harness |
| 4 | 溢出后抢救（reactive recovery） | 可选 | CCB reactiveCompact、DeepSeek Harness 溢出路径 |
| 5 | 结构化世界状态 / 检索式记忆 | — | Codex WorldState 片段；向量检索式主流核心未采用 |

### 3.1 层级 2：无模型的消息级修剪

CCB `src/services/compact/microCompact.ts`：对白名单工具（FileRead、Shell、Grep、Glob、WebSearch、WebFetch、FileEdit、FileWrite）的**旧结果**整体替换为 `[Old tool result content cleared]`，消息结构与工具配对保持不动。配套有按时间衰减的变体（`timeBasedMCConfig.ts`）和尽量不破坏 prompt cache 的变体（`cachedMicrocompact.ts`）。

特点：零额外 token 成本、确定性、可用假模型直接测试；只能回收工具输出体积，回收不了对话文本。CCB 的 `docs/features/context-collapse.md` 还规划了消息级折叠（CONTEXT_COLLAPSE）与对话级 autoCompact 并存的双层结构，折叠核心在该快照中仍是 stub。

### 3.2 层级 3：摘要压缩（事实标准）

压缩后结构统一为 `[system] + [摘要消息] + [最近尾部]`。保留尾部是共识：摘要模型会丢失即时工作状态，最近几轮必须原文保留。

各家关键参数：

| 参数 | Codex | OpenCode | DeepSeek Harness | CCB |
|---|---|---|---|---|
| 触发阈值 | 窗口 65%（`core/src/config/mod.rs` `DEFAULT_ASYNC_COMPACTION_THRESHOLD_PERCENT`） | 模型上限减 buffer，buffer 默认留 10% 余量 | 阈值比例 0.8 | 百分比阈值，另有 2 万 token 的警告缓冲 |
| 尾部保留 | — | 默认保留最近 15,000 token | 保留历史比例 0.16 | — |
| 摘要上限 | 压缩用 user 消息上限 20,000 token | — | 摘要 `maxTokens: 8192` | — |
| 摘要模型 | 可配置，且有摘要模型降级链（`compact_model_fallback.rs`），另有服务端远程压缩（`compact_remote_v2.rs`） | 可换模型执行 | 默认空则回退最近路由模型 | — |
| 失败收缩 | — | 范围按 `SHRINK_STEPS = [0.7, 0.5, 0.35]` 逐次缩小重试 | `compactionRetries: 1` | — |

摘要提示词都很短且面向"交接"：Codex `prompts/templates/compact/prompt.md` 全文约 10 行——为接手的另一个 LLM 写交接摘要，包含当前进展与关键决策、约束与用户偏好、剩余步骤、必须延续的数据与引用。OpenCode 的提示词更结构化，要求合并新需求、调和 Work State 与 Next Move，且若模型原生支持压缩（`model.compaction.type === "native"`）则优先走原生能力。

DeepSeek Harness 的工程化程度最深：成功调用的压力检查放在下一个 pre-step 边界（那时上一轮输出、工具结果都已持久化，且不会拆开工具配对）；按提供方/模型组合的策略覆盖（`modelPolicies`）；摘要不缩小可拒绝。详见 `.agents/notes/archived/architecture/2026-07-10-after-call-compaction-pressure-and-overflow-recovery.zh.md`。

### 3.3 层级 4：溢出后抢救

提供方直接以上下文超限拒绝请求（如 `CONTEXT_WINDOW_EXCEEDED`、HTTP 413）时的恢复路径。DeepSeek Harness 的顺序是：先无模型剪枝大工具结果，压力仍不达标再在同一信号下做一次缩小范围的摘要；用"表层 generation 变化"证明压缩产生了真实进展，无进展则保留原始错误、不无限重试（`maxOverflowRetries` 默认 1）。CCB 对应有 `reactiveCompact.ts` 与 query 层的 413 恢复挂点。

### 3.4 层级 5：结构化状态与检索式记忆（不建议采用）

Codex 把摘要升级为跨窗口合并的 WorldState 结构化片段（`core/src/compact.rs` 引用的 context/world_state 体系），维护成本显著高于纯文本摘要。检索式记忆（把旧上下文向量化、按需取回）在四个主流项目中均未进入核心循环。两者均超出 LoopCraft 第一阶段目标。

## 4. 跨层级工程要点

1. **工具配对不变量**：assistant 消息的 tool_calls 与后续工具结果必须成对出现在上下文中，否则多数提供方直接拒绝请求。裁剪只能整对裁掉，或按层级 2 只替换结果内容、保留占位。
2. **prompt cache 代价**：压缩改写历史前缀会打断缓存，频繁压缩反而增加费用与延迟，这是各家阈值留 10%–35% 余量的原因。
3. **token 计量是地基**：LoopCraft 目前没有任何 token 计数器。Codex 用 `approx_token_count` 字符估算起步即可；有了计量才谈得上阈值触发与"压缩是否有效"的判定。
4. **事件可见性**：压缩发生时应作为运行事件外显（LoopCraft 现有 SSE 双层通道可直接复用），否则前端看到的上下文行为无法解释。

## 5. 模板字段语义的待定项

现有三字段方向正确，但语义尚未定义，接入前需要拍板：

- `context_budget_tokens`：绝对 token 预算（估算值达到即触发），还是模型窗口的比例阈值？各家用比例为主，绝对值在多模型租户下难以配置。
- `summary_model_id`：为空时回退主模型还是拒绝启用压缩？
- `keep_recent_messages`：按消息条数还是按 token 保留尾部？条数实现简单，但单条工具结果可能极大。
- 是否需要新增"压缩策略"枚举列（如 `none / truncate / summary`）？若采纳则属于改列，实施时须同步更新 `docs/database-schema.md` 的 ER 图与表清单。

## 6. 建议路径（待讨论）

1. **run 内（可先行，不依赖会话）**：补 token 估算器；在循环每轮模型调用前按 `context_budget_tokens` 检查；超限时对超出保留范围的历史工具结果做层级 2 式清理（替换为占位文本，配对不动）。全程无额外 LLM 调用，假模型即可测试，也不改变循环的决策结构。
2. **会话落地后（跨 run）**：完整层级 3——触发后用 `summary_model_id`（建议允许配置为更便宜模型）生成交接式摘要，按 `keep_recent_messages` 保留尾部，SSE 增加 `context_compacted` 事件。策略枚举与字段语义在第 5 节定案后一并实施。

按仓库约定，核心循环的实现由用户亲自完成；本文仅收敛设计与配置方向。若第一步的估算器与阈值检查需要先行，也建议以独立小组件形式提供，再由用户接入 `Agent.loop`。
