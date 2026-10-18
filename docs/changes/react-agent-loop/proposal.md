# ReAct Agent 主流程：Harness 源码调研与 LoopCraft 能力取舍

状态：**讨论中，等待用户拍板；未批准开发**

调研日期：2026-10-18

调研方式：阅读本地五个参考仓库的固定提交、LoopCraft 当前文件，以及 Spring AI 2.0.1 源码。未运行参考项目、参考测试或真实模型。

## 1. 建议先确定什么

建议 LoopCraft 第一阶段做一个**项目自己驱动、单次请求内运行、工具串行执行、有明确预算和终止结果的 ReAct 循环**。

保留 Spring AI 的模型、消息和工具描述类型；模型每次只调用一次，执行哪些工具、何时再调用模型、何时停止，由 LoopCraft 显式决定。第一阶段先用少量无副作用的本地工具把链路跑通，再考虑业务工具和交互体验。

本次建议的核心取舍：

| 问题 | 推荐选择 | 原因 |
| --- | --- | --- |
| 谁拥有循环 | LoopCraft 手写循环 | 主流程可见，符合项目的学习目标 |
| 如何理解 ReAct | 使用原生 Function Calling 组织模型 → 工具 → 观察结果 | 不解析自由文本里的 `Thought/Action`，不要求输出或展示隐藏思维链 |
| 模型调用方式 | 先完整响应，再判断和执行工具 | 避免流式碎片、截断参数和执行竞态一起进入首版 |
| 一次返回多个工具 | 支持，按返回顺序串行执行 | 能覆盖真实协议，又暂不承担并发调度复杂度 |
| 状态存在哪里 | 本次请求的局部内存 | 当前需求是主循环，已有数据库不构成保存会话的理由 |
| 常见工具输入错误 | 转成明确的失败观察结果，让模型在预算内修正 | 保留 Agent 的纠错能力，不把工具错误冒充成功 |
| 模型请求失败、超时 | 明确终止，首版不自动重试 | 避免重试嵌套、隐性成本和重复执行 |
| 达到轮次上限 | 硬停止，不额外请求“总结一次” | 预算容易解释和验证 |
| 高级能力 | 暂缓并发、SSE、运行中插话、Hooks、压缩、持久化恢复和子 Agent | 先验证核心闭环；以后按具体场景增加 |

以上均为**建议，不是已确认决策**。拍板清单见第 12 节。

## 2. LoopCraft 当前真实起点

本节以调研时的工作区文件为准，包含尚未提交的文件；调研期间可能有其他工作同步进行。它不是某个历史提交的完整状态，也不把别的 Agent 项目视作本项目现状。

| 当前文件 | 已有能力 | 尚未具备的能力 |
| --- | --- | --- |
| [Agent.java](../../../backend/src/main/java/io/github/chalmery/loopcraft/agent/Agent.java) | 组装 system/user 消息，调用一次 `ChatModel.call`，提取文本；遇到工具调用直接报错 | 无循环、工具执行、消息回填或会话历史 |
| [AgentController.java](../../../backend/src/main/java/io/github/chalmery/loopcraft/web/AgentController.java) | `POST /api/agent/chat`，输入 `message`，成功返回 `answer`；未启用模型时返回 503，模型错误映射为 502 | 无 SSE、run 查询或取消接口 |
| [AgentProperties.java](../../../backend/src/main/java/io/github/chalmery/loopcraft/config/AgentProperties.java) | 校验 `maxIterations`、`runTimeout`、`toolTimeout` | 绑定与校验配置不代表执行时已落实预算 |
| [application.yml](../../../backend/src/main/resources/application.yml) | 当前预算为 12 次迭代、120 秒运行时间、15 秒工具时间；关闭 ChatClient 自动工具调用及内容观测配置 | 无 Agent 运行状态机、运行轨迹或预算执行机制 |
| [application-llm.yml](../../../backend/src/main/resources/application-llm.yml) | 显式启用真实模型；当前请求超时默认 45 秒，SDK 重试配置为 0 | 无整个运行剩余时间对单次模型请求的动态约束 |
| [pom.xml](../../../backend/pom.xml) | Java 25、Spring Boot 4.1.1、Spring AI 2.0.1，MVC 和 PostgreSQL/MyBatis-Plus 基础设施 | 无 Agent 业务表或恢复机制 |

因此，本次讨论的是把“单次模型调用”扩展成“受控的模型与工具循环”。数据库选型已经存在，业务会话持久化仍是另一项需求。

## 3. 调研范围和证据边界

固定以下本地源码快照，不把主分支后续更新混入结论：

| 仓库 | 本地目录 | 固定提交 |
| --- | --- | --- |
| can1357/oh-my-pi | `harness-references/oh-my-pi/` | [dd657ab3a31c](https://github.com/can1357/oh-my-pi/tree/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf) |
| anomalyco/opencode | `harness-references/opencode/` | [8c173a8f3805](https://github.com/anomalyco/opencode/tree/8c173a8f3805d8e749d44311054b07e37a1b2ce1) |
| deepseek-ai/deepseek-harness | `harness-references/deepseek-harness/` | [687f282e8625](https://github.com/deepseek-ai/deepseek-harness/tree/687f282e8625c6b8d11ff58211a90524be2e4317) |
| openai/codex | `harness-references/codex/` | [ce0323f2f2d3](https://github.com/openai/codex/tree/ce0323f2f2d3b25e82806cd555237da3db4e1897) |
| claude-code-best/claude-code | `harness-references/claude-code/` | [77a7934e15d6](https://github.com/claude-code-best/claude-code/tree/77a7934e15d69da13879112ed7db695c9ee7a52a) |

这里的 `claude-code` 是 **claude-code-best/claude-code（CCB）第三方项目**。其 README 自述为复原和扩展项目；本次只分析该仓库代码，不据此推断 Anthropic 官方 Claude Code 的内部实现。 [源码：项目来源](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/README.md#L13)

调研聚焦主循环、工具分发、消息回填、终止和错误边界，没有全面审计各项目全部权限、存储、模型适配与插件实现。源码里存在某项机制，不等于已验证它在所有环境下可靠。参考测试仅用于理解预期，不作为本次测试通过的证据。

## 4. 先用一个例子统一“主循环”的含义

假设注册两个无副作用的演示工具 `add(a,b)` 和 `multiply(a,b)`，请求是“使用工具计算 `(18+24)*3`”：

```text
初始消息：system + user
第 1 次模型调用：assistant 发出 add(18,24)，call_id=c1
运行时：校验并执行 add，记录 tool(c1, 42)
第 2 次模型调用：携带完整历史，assistant 发出 multiply(42,3)，call_id=c2
运行时：校验并执行 multiply，记录 tool(c2, 126)
第 3 次模型调用：携带完整历史，assistant 返回正常最终文本 126
运行结束：COMPLETED，模型调用 3 次，工具调用 2 次
```

不能只把 `42` 拼进用户文本，也不能丢掉带 `c1` 的 assistant 消息。模型需要看到自己提出的调用及与它一一对应的工具结果，才能继续。演示验收用假模型固定返回上述序列；真实模型是否选择使用算术工具，不作为确定性测试条件。

本文约定：

- **run**：一次 HTTP 请求触发的一次 Agent 运行。
- **iteration**：一次模型请求以及该响应产生的工具批次；即使没有工具也计一次。
- **tool call**：模型提出的一次工具调用；同一 iteration 可以有多个。

不同参考项目的 turn、step 含义不同，不能直接把它们的计数配置搬过来。

## 5. 五个项目分别怎么做

### 5.1 oh-my-pi：显式内循环，加输入队列和扩展点

主入口在 `packages/agent/src/agent-loop.ts`。`runLoopBody` 使用两层循环：内层处理模型输出、工具调用和 steering 输入；内层准备结束后，外层检查是否还有迟到的 steering、aside 或 follow-up，存在时继续驱动。它不是只跑一个 `while(toolCalls)`。 [源码：主循环](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1196) [源码：输入队列](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1815)

主调用链可以概括为：准备上下文 → 准备并发送模型请求 → 保存 assistant 消息 → 提取工具调用 → `executeToolCalls` → 回填工具结果 → 检查是否继续。

值得借鉴的具体机制：

- 主循环同时看实际工具块和结束原因。正常 `stop` 中带工具调用也可能继续；`length` 截断、错误或中断响应不会按正常完整调用执行。对未执行调用生成带明确原因的结果，以保持调用与结果配对。 [源码：响应分流](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1536)
- `prepareToolCallDispatch` 先解析工具、校验参数，执行前 Hook 改过参数后再次校验，并让最终历史与真实执行参数保持一致。 [源码：执行准备](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L3071)
- 工具分为 `shared` 和 `exclusive`，独占调用等待此前共享调用完成；这里未声明时默认 shared，不能把它概括为“所有项目都默认保守串行”。 [源码：工具调度](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L3830)
- `deadline` 可以合并成取消信号；模型前置 gate、上下文变换和工具前后 Hook 是独立扩展点。本文没有在该主循环里找到与 LoopCraft `maxIterations` 完全等价的统一总轮次硬上限，不把局部纠错次数限制误写成总预算。 [源码：deadline](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1208) [源码：循环配置](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/types.ts#L172)

**LoopCraft 取舍：**借鉴显式循环、完整消息和终止分流；首版不引入输入队列、工具别名补救、参数改写 Hook、推测执行或共享/独占调度。省掉的是交互和扩展机制，工具配对与截断判断仍要保留。

### 5.2 OpenCode：持久会话驱动逻辑 step，attempt 负责一次执行

本次版本的核心已在 `packages/core/src/session/runner/`，不能按旧教程寻找 `packages/opencode/src/session/processor.ts`。

`SessionRunner.drain` 从已记录的会话历史和 inbox 推进；`runStep` 拥有逻辑 step 的压缩、重试和步数策略；`SessionStep.attempt` 负责单次 provider stream、工具执行及最终事件落定。换句话说，**逻辑上的一轮与底层一次请求尝试不是同一个计数维度**。 [源码：drain](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L57) [源码：runStep](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L209)

值得借鉴的具体机制：

- 收到完整 `tool-call` 事件并发布调用事件后启动本地工具 fiber；标记 `providerExecuted` 的调用不在本地重复执行。流结束后等待工具结束，再落定 step。 [源码：attempt](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/step.ts#L91)
- 达到配置的 steps 阈值时，保留工具定义以维持缓存前缀，但将 tool choice 设为 none；工具执行入口也拒绝调用，随后允许文本收尾。这是“限制工具、留一轮文本回答”的产品策略，不等同于禁止一切后续模型调用。 [源码：步数限制](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L226)
- 没有持久输出时可以重试原请求；已有输出时走带已完成历史的继续路径。输出截断还有单独的有限续写逻辑。 [源码：重试分流](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/step.ts#L200) [源码：输出续写](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L290)
- 重新 drain 前会处理遗留 running/streaming 工具和未落定压缩记录，体现持久化后对异常恢复的额外责任。 [源码：遗留调用](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L362)

**LoopCraft 取舍：**借鉴“一次逻辑迭代”和“一次传输尝试”的区分，即使首版不重试也把计数语义讲清楚；首版不搬数据库事件、inbox、位置迁移和流中工具执行。建议采用硬停止，不照搬最后一次模型总结。

### 5.3 DeepSeek Harness：Session 日志驱动，turn/step 边界清楚

`ReactLoopAgent` 在 `packages/core/agent-loop/src/agent.ts`。`kick` 驱动 turn，turn 推进 step；每个 step 从 Session 日志派生模型请求，流完成后保存 assistant 消息，再调度工具。next-step 和 next-turn inbox 分别承载当前运行中的引导与后续输入。 [源码：turn](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L298) [源码：step](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L400)

值得借鉴的具体机制：

- 明确记录 `turn/start`、`step/start`、assistant、tool 和结束事件；中断流记录为 interrupted message 或 attempt，不能直接冒充正常完整响应。 [源码：step](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L400)
- 模型 finish 为 max-tokens 时先结束该 step，不执行其中的工具；正常响应没有工具则完成，有工具则执行并决定是否继续。工具可显式 `concludeTurn`，这属于工具完成语义，不是所有工具天然如此。 [源码：结束判定](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L547)
- 工具调度使用有上限的滚动池，exclusive 工具形成屏障；结果即使先后完成顺序不同，也按模型调用顺序落定。未显式声明并发安全时视为 exclusive。 [源码：工具批次](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/tool-calls.ts#L60) [源码：并发判定](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/tools/src/index.ts#L1311)
- 取消后停止补充待执行调用，收集已启动调用的结果，对未开始的调用记录明确的跳过结果；这比只对一个 Promise 超时更完整。 [源码：工具批次](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/tool-calls.ts#L60)
- 请求错误交给 `agent/request-error` 策略决定是否 retry；不能因为 step 内有 `while(true)`，就说它无条件重试。本文未从该 driver 证明一个统一总 step 次数上限，LoopCraft 仍应自己落实硬预算。 [源码：请求错误](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L512)

**LoopCraft 取舍：**借鉴清楚的运行边界、按调用顺序提交结果以及取消后的停止派发；首版不引入 Session 日志投影、插件事件体系和恢复驱动器。保留一次请求的局部状态就足够。

### 5.4 Codex：turn 驱动采样，工具路由和执行策略分层

当前 Rust 主流程在 `codex-rs/core/src/session/turn.rs`。`run_turn` 反复调用 `run_sampling_request`；后者处理流式输出项，通过工具运行时执行调用并收集结果。`model_needs_follow_up` 或待处理输入都会促使外层继续；准备结束时还会运行 Stop hooks。 [源码：run_turn](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L166) [源码：停止 Hook](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L720)

值得借鉴的具体机制：

- 模型输出、工具执行结果和待处理输入都能影响是否继续，不能把“出现一段 assistant 文本”当作整个任务已经结束。 [源码：继续判定](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L604)
- 工具经过 router/registry 分发；未知工具会变成可返回模型的错误，部分 Fatal 错误才向上终止。 [源码：工具查找](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/registry.rs#L650) [源码：工具结果](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/parallel.rs#L78)
- 并发安全工具取得共享读锁，其他工具取得独占写锁；取消同时考虑等待准入与已经开始执行的处理器。 [源码：并发准入](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/parallel.rs#L213)
- 工具执行编排器管理审批与沙箱尝试。失败后的重试受权限策略控制，不能理解成工具失败就直接关闭沙箱再执行。 [源码：执行策略](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/orchestrator.rs#L125)
- stream retry、上下文压缩和工具执行记录各有单独机制。这里的长期编码会话与单次云端 HTTP 请求不同，本次不声称它具有一个与 12 次模型调用等价的统一默认上限。 [源码：采样请求](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L1707)

**LoopCraft 取舍：**借鉴模型/工具/策略的责任边界，区分可回馈错误与运行级失败；首版仅注册可信、无副作用的工具，不接 Shell、文件写入和任意 URL 请求，因此不提前引入沙箱、审批平台和多 Agent。

### 5.5 CCB：生成器输出事件，以显式 state 推进下一轮

`src/query.ts` 的 `query` 调用 `queryLoop`。`queryLoop` 用 `while(true)` 和 state 推进，yield 模型消息、工具结果和运行状态；下一轮把 `messagesForQuery + assistantMessages + toolResults` 作为历史。这里看到的是第三方 CCB 的实现。 [源码：queryLoop](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L393) [源码：下一轮](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L2053)

值得借鉴的具体机制：

- 使用实际收到的 tool-use 块设置 `needsFollowUp`，不只依赖 `stop_reason` 字符串。没有工具时仍可能被 Stop hooks 阻止结束。 [源码：工具继续](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L756) [源码：Stop hooks](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1557)
- 同时有流式工具执行分支和完整响应后的 `runTools` 分支。后者按 `isConcurrencySafe` 分组，并发安全调用并行，其他调用串行；先解析输入再判断并发安全，判断异常按不安全处理。 [源码：执行分支](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1669) [源码：并发分组](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/services/tools/toolOrchestration.ts#L106)
- 工具执行路径有 schema 解析、业务输入校验、Hook 和权限决策。中断路径补齐缺失的 tool result，避免孤立调用进入下次模型请求。 [源码：参数与权限](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/services/tools/toolExecution.ts#L641) [源码：中断](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1302)
- `maxTurns` 是可选参数，在继续边界判断；输出上限恢复、压缩恢复、Stop hook 继续各有状态。它的 turnCount 不能直接当成 LoopCraft 的“所有模型调用次数”。 [源码：轮次上限](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L2033) [源码：Stop hooks](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1557)

**LoopCraft 取舍：**借鉴完整消息拼接、非正常结束单独处理和可读的状态推进；暂不引入生成器事件协议、权限对话、Stop hooks 和自动压缩，也不照搬其模型特定恢复逻辑。

## 6. 横向比较：哪些是共同底座，哪些依赖产品形态

| 维度 | oh-my-pi | OpenCode | DeepSeek Harness | Codex | CCB |
| --- | --- | --- | --- | --- | --- |
| 主驱动入口 | `runLoopBody` | `drain → runStep → attempt` | `kick → turn → step` | `run_turn → run_sampling_request` | `query → queryLoop` |
| 本次阅读的状态载体 | context/messages + 输入队列 | 持久 Session 历史、inbox、事件 | Session 日志和投影 | Session/history + turn context | 显式 state/messages + tool context |
| 工具继续依据 | 可运行响应中的工具块 + 待处理输入 | attempt 的 needsContinuation + inbox | 工具块、concludesTurn、next-step 输入 | 输出项的 follow-up + 待处理输入 | needsFollowUp + Stop hooks |
| 批量工具调度 | shared/exclusive，未声明默认 shared | 事件触发 fiber；下层另有工具策略 | 有界并发池 + exclusive 屏障 | 共享读锁 / 独占写锁 | 并发安全分组 + 串行组 |
| 模型截断处理 | 不正常执行截断工具，可补结果后继续 | 有限的输出续写路径 | step 返回 max-tokens | 通过 provider/stream 错误与恢复路径处理，不在此概括成统一策略 | 有输出上限恢复状态 |
| 结束扩展 | turn hooks / 输入队列 | inbox / step outcome | turn-stopping / concludeTurn | Stop hooks / 输入 | Stop hooks / 状态推进 |

表中机制的源码依据分别见第 5 节。本次没有验证各项目所有部署模式；尤其 OpenCode 的并发上限不能仅从 forkScoped 推断，Codex 的截断语义也不能用 OpenAI Chat Completions 的 finish_reason 规则直接替代。

对 LoopCraft 的推论是：**完整消息协议、明确终止、执行边界和预算必须在第一阶段形成；队列、并发、持久化、压缩和 Hooks 可以按场景补。** 这项取舍来自我们的目标，不是五个项目都采用的统一最简配置。

## 7. 建议的 LoopCraft 主流程

### 7.1 对外行为和责任边界

用户仍向 `POST /api/agent/chat` 提交文本。服务端创建一次独立 run；模型可以直接回答，也可以提出一个或多个工具调用。运行时执行后再请求模型，直到正常文本结束或触发终止条件。

建议初期只形成以下职责，不为了层次创建转发类：

```text
web/AgentController
    请求校验、HTTP 错误映射
        ↓
agent/Agent
    显式循环、局部 messages、计数、deadline、终止结果
        ├── Spring AI ChatModel：一次模型调用
        └── tool/ToolExecutor：工具查找、输入校验、受限执行、结果整理
                  └── 本次运行固定的 Map<String, ToolCallback>
```

`Agent` 可以继续沿用现有名字，不必同时增加 AgentRunner、AgentService 和 AgentManager。`ToolExecutor` 的存在是因为它拥有真实的执行约束，不是为了转发一行调用。第一阶段也不增加模型协议包装层；具体配置留在现有模型接入边界。

`Agent` 虽然是单例 Bean，messages、deadline、计数和取消状态必须是每次请求的局部数据。工具表可以共享不可变快照，工具实现必须无会话可变状态或具有明确线程安全约束。两个请求之间不得串消息，也不得串预算。

### 7.2 状态与停止条件

```mermaid
flowchart TD
    A[创建局部历史和预算] --> B{剩余时间和迭代预算允许?}
    B -- 否 --> X[记录明确终止原因]
    B -- 是 --> C[调用一次模型]
    C --> D{响应完整且结束类型可处理?}
    D -- 否 --> X
    D -- 是 --> E{含工具调用?}
    E -- 否 --> F{正常结束且文本非空?}
    F -- 是 --> G[COMPLETED]
    F -- 否 --> X
    E -- 是 --> H{call id 有效且剩余模型/工具预算允许?}
    H -- 否 --> X
    H -- 是 --> I[保存完整 assistant 消息]
    I --> J[串行校验和执行各工具]
    J --> K{取消、超时或执行状态不明?}
    K -- 是 --> X
    K -- 否 --> L[按 call id 回填全部工具结果]
    L --> B
```

图中的“COMPLETED”只表示模型协议正常结束，并且本次执行链已收束；它不自动证明答案正确或未来业务操作验收完成。

建议内部区分：`COMPLETED`、`ITERATION_LIMIT`、`TOOL_CALL_LIMIT`、`TIMED_OUT`、`CANCELLED`、`FAILED`。失败还应有稳定原因码，如 MODEL_ERROR、INVALID_MODEL_RESPONSE、OUTPUT_LIMIT、CONTEXT_LIMIT、TOOL_EXECUTION_UNCERTAIN。工具输入错误若已回馈并得到模型修正，不必把整次运行标成 FAILED。

### 7.3 响应判定顺序

1. 模型异常、空 response/generation、取消或超时：终止，不执行响应中残留的工具。
2. `length`、过滤/拒绝等非正常结束：明确失败或截断，不执行可能不完整的工具，也不把文本当最终成功。
3. 检查工具调用块。如果存在完整调用，允许适配器正常映射的 `tool_calls` 或 `stop` 进入工具分支，不能先看到 stop 就返回文本；未知结束类型保守失败。
4. 没有工具且 finish 是正常 stop、文本非空：完成。
5. finish 表示工具调用但实际没有调用、空文本无调用、多候选响应与约定不符：视为协议异常，首版不猜测或修复。

这针对当前 OpenAI-compatible 模型边界提出，不承诺兼容五个项目的所有模型协议。实现前需用当前适配器离线桩确认 finish reason 的实际映射；不要把工具调用中夹带的说明文字当作最终答案。

## 8. 消息与 Spring AI 2.0.1 的具体边界

每次模型请求都携带同一组允许使用的工具定义。收到工具调用后，保留模型返回的**完整 `AssistantMessage`**，包括 tool calls 和 provider metadata；再添加带相同 id/name 的 `ToolResponseMessage.ToolResponse`。全部工具结果配齐后才进行下一次模型调用。

Spring AI 2.0.1 已在本机 sources.jar 核实：

- `AssistantMessage.ToolCall(id,type,name,arguments)` 与 `ToolResponseMessage.ToolResponse(id,name,responseData)` 可表达配对；`ToolCallback` 可复用为工具定义与执行入口。 [源码：调用消息](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-model/src/main/java/org/springframework/ai/chat/messages/AssistantMessage.java#L111) [源码：工具结果](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-model/src/main/java/org/springframework/ai/chat/messages/ToolResponseMessage.java#L75) [源码：工具接口](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-model/src/main/java/org/springframework/ai/tool/ToolCallback.java#L33)
- `OpenAiChatOptions` 通过 `toolCallbacks` 声明工具；沿用当前模型默认配置并按请求补充工具，避免无意覆盖模型、timeout 等选项。 [源码：工具选项](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java#L529)
- 此版本 `OpenAiChatModel.internalCall` 返回单次 ChatResponse；自动多轮工具执行走 ChatClient 的 ToolCallingAdvisor 机制。继续直接使用 `ChatModel.call`，保持 `spring.ai.chat.client.tool-calling.enabled=false`，不手动装配 Advisor，也不引用 1.x 的 `internalToolExecutionEnabled(false)` 写法。 [源码：单次调用](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java#L196) [源码：自动工具配置](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/models/spring-ai-autoconfigure-model-chat-client/src/main/java/org/springframework/ai/model/chat/client/autoconfigure/ChatClientAutoConfiguration.java#L125)
- OpenAI 适配器会从 assistant metadata 回放 reasoning content。不要重建成只有文本和工具名的消息，从而丢掉必要信息；也不要把 provider 字段解析散落到循环中。 [源码：元数据回放](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java#L676)

`ToolCallback` 并不自动等于任意 JSON Schema 已经完成运行时验证。首版推荐两个固定、强类型演示工具：执行入口严格解析 JSON，拒绝缺失字段、错误类型及越界值，随后做业务参数校验；不先引入完整的通用 Schema 执行平台。未来接入动态 MCP 工具时，再单独讨论通用 JSON Schema 校验和能力发现。

## 9. 能力取舍与异常策略

### 9.1 工具注册、调用和结果

- 使用服务端明确注册的工具白名单；提供给模型的定义与实际可执行快照来自同一份表。启动或装配时拒绝重名。模型不能通过名字访问任意 Bean、类、文件、URL 或命令。
- 同一批次先检查 call id 非空、批内及本 run 内不重复、累计调用数未超限，再开始执行。遇到 id 冲突首版直接失败，不仿照 OMP 自动修复 id。
- 支持单响应多个调用，按模型返回顺序执行；工具之间的参数依赖由下一次模型调用传递，不支持同批次的占位引用。
- 未知工具、参数 JSON 错误、参数校验失败：该调用不执行，回填带原 call id 的安全错误结果。独立的后续调用可以继续；模型在剩余预算内重新生成正确调用，算新调用，不能由运行时悄悄修复后执行。
- 工具可预期业务错误：回填错误码与精简说明。不可预期运行时错误、超时或取消：终止 run，不能 catch 所有异常后继续；HTTP 只暴露安全说明。
- 成功与失败结果显式区分。例如结果 envelope 使用 `ok`、`data` 或 `error`；过大结果改为合法 JSON envelope，标明 `truncated` 并放受限的文本摘要，不能直接截断 JSON 字符串造成无效内容。
- 首版不支持 returnDirect：工具结果必须进入下一轮模型，由模型形成用户答案；否则容易把“工具执行完”误当“用户任务完成”。

### 9.2 预算：既限制模型次数，也限制工具总量

| 预算 | 建议 | 语义 |
| --- | --- | --- |
| maxIterations | 沿用 12 | 所有已发起模型调用都计数；最后一次正常文本可成功；若第 12 次仍要求工具，硬停止且不再执行该批工具，因为已无下一次观察预算 |
| runTimeout | 沿用 120 秒 | 整个 run 的总等待预算，包含模型、工具和所有排队时间 |
| toolTimeout | 沿用 15 秒 | 单工具上限，还要取 run 剩余时间的较小值 |
| 模型请求超时 | 当前 45 秒与 run 剩余时间取较小值 | run 只剩 8 秒时，不能继续完整等待 45 秒 |
| maxToolCalls | 建议新增，初值 32，待确认 | 限制一个 run 中模型提出的工具调用总数，包含非法调用；整批会超限时不执行该批，不挑部分执行 |
| maxToolResultChars | 建议新增，初值 8000，待确认 | 每个发给模型的工具结果内容上限，不等于精确 token 上限 |

新数值是便于首版验收的建议值，没有来自参考项目的通用最优依据。12 次模型调用通常最多对应 11 个可执行工具批次，因为最终答案也占一次模型调用。不同轮次预算方案的代价应明确，而不是上线后再解释 off-by-one。

工具结果字符上限不能保证所有模型的上下文一定够用。首版不自动压缩；若模型返回上下文超限，报告 CONTEXT_LIMIT 并结束。后续出现长会话需求时再设计 token 预估、输入预算和成组裁剪，不能删除孤立的 assistant 或 tool 消息。

### 9.3 超时与取消的实际含义

时间计算使用单调时钟维护剩余预算。循环顶部、模型调用前后、每个工具前后均检查；外部 I/O 必须有自身超时，不能只在 `while` 开头看时间。

阻塞 `ChatModel.call` 或工具调用需要可等待、可发出中断的执行句柄。超时后停止派发新工作，调用取消并释放等待资源；不能把共享 ChatModel 的配置改成当前请求的剩余超时，从而影响其他请求。

**Future.get 超时或 cancel(true) 只代表本地不再等待/请求中断，不证明下游停止或副作用已回滚。** 具体 HTTP 客户端和工具的取消行为必须通过离线延迟桩验证。首版限制为无副作用演示工具；若执行状态不能确定，返回明确的执行不确定错误，不自动重试。

首版内部取消处理应有测试，但不承诺同步 MVC 能可靠识别浏览器断连，也不因此新增取消 API。显式 `runId + cancel`、连接关闭传播和后台任务生命周期放到后续交互方案讨论。

### 9.4 错误分类和重试

| 情况 | 当前运行怎样处理 | 后续动作 |
| --- | --- | --- |
| 未知工具 / 非法参数 | 不执行，回填失败观察结果 | 模型可以在预算内改正 |
| 可预期只读查询失败 | 回填工具错误 | 模型解释失败或选择其他路径 |
| SDK 网络错误、限流、认证失败 | FAILED / MODEL_ERROR | 首版不自动重试；保留当前 SDK max-retries=0 |
| 输出截断、过滤、空或未知协议响应 | 明确失败，不执行工具 | 首版不自动续写或改模型 |
| 模型上下文超限 | FAILED / CONTEXT_LIMIT | 首版不自动压缩 |
| run/tool 超时 | TIMED_OUT 或执行不确定失败 | 停止后续派发，不自动重放 |
| 轮次/工具数耗尽 | 对应 LIMIT | 返回未完成原因，不伪造最终回答 |
| 内部取消 | CANCELLED | 尽力中断，不能继续下一轮 |

以后若确需重试，分别定义“模型传输重试”和“工具重试”的次数、延迟与预算。写操作必须先解决幂等、查询执行状态和重复请求问题；会话日志或 checkpoint 本身不能保证外部操作恰好一次。

### 9.5 HTTP 与可观察性

建议先保持同步接口和成功结构 `ChatReply(answer)`，运行结果内部保留 status、reason、模型次数和工具次数；不向现有成功 JSON 强塞完整轨迹。失败使用现有 ProblemDetail 机制，建议约定：模型协议/模型服务错误 502，预算耗尽 422，超时 504，模型未启用仍 503。实际映射是待确认的产品/API 决策。

诊断至少区分“模型提出”“通过校验”“已开始”“成功/失败/未执行”，保留调用 id、工具名、耗时和计数即可。默认不记录用户全文、模型思维内容、工具参数或完整结果。模型结束、工具结束和整个 run 结束分别统计，不把它们混成一个成功标记。

## 10. 首版范围与后续触发条件

| 能力 | 第一阶段建议 | 以后什么时候值得加 |
| --- | --- | --- |
| 单次调用和多次工具循环 | 纳入 | 核心目标 |
| 多工具返回、串行执行 | 纳入 | 后续确认工具互相独立且有延迟收益，再做有界并发 |
| 完整消息与 call id 配对 | 纳入 | 后续持久化仍必须保持 |
| 预算、终止、基本错误反馈 | 纳入 | 基础执行约束 |
| 局部内存状态、最小诊断 | 纳入 | 需要跨请求查询轨迹时再加运行记录 |
| 两个无副作用本地演示工具 | 建议纳入 | 业务工具由实际场景确定 |
| SSE、流中工具执行 | 暂缓 | 先有“用户需要逐步看到内容”的明确交互目标；SSE 和流中执行也可分开建设 |
| 用户插话、follow-up 队列 | 暂缓 | 需要长任务中途调整指令时 |
| 业务写工具、审批与沙箱 | 暂缓 | 确定写操作场景后，一并设计授权、幂等和执行不确定状态 |
| 自动重试、续写、模型降级 | 暂缓 | 有真实失败样本，能定义可安全恢复的边界时 |
| 会话持久化、checkpoint、恢复 | 暂缓 | 确定多轮对话、后台任务或进程重启恢复需求时 |
| 自动上下文压缩 | 暂缓 | 长历史实际逼近上下文限制时 |
| Hooks、中间件、事件总线 | 暂缓 | 出现多个真实扩展方或稳定的横切需求时 |
| MCP、Skills、RAG、Graph、子 Agent | 本提案不引入 | 各自有明确需求后单独设计，通过工具/上下文边界接入 |

## 11. 验收条件与未来验证方式

以下均为**拍板后开发的验收计划，当前没有实现或执行这些用例**：

- [ ] 无工具正常文本结束；单工具一次；工具连续多轮；同轮多个独立工具按顺序执行。
- [ ] 每次模型调用都带工具定义；完整 assistant 消息、metadata、call id 和工具结果配对保留。
- [ ] 正常 stop 带完整工具调用可进入工具分支；length 带工具、未知 finish、空响应、空答案均不错误执行或成功返回。
- [ ] 工具调用 id 为空/重复时，整个批次不开始；未知工具、非法 JSON、字段类型错误、业务参数越界能得到对应失败结果。
- [ ] 前面一个工具成功、后面一个校验失败时，历史准确反映各自结果；不可预期异常不会伪装为成功或自动重跑前面的工具。
- [ ] maxIterations=1 时直接文本可以成功、工具请求不会执行；最后一轮工具请求硬停止；没有隐藏的第 N+1 次总结请求。
- [ ] 超过整批工具预算时一个也不执行；失败调用也消耗工具调用额度；过大结果整理后仍是合法 JSON 且包含截断标识。
- [ ] 延迟模型桩、延迟工具桩覆盖单工具超时、剩余运行预算、取消前/执行中/工具批次边界；终止后不再派发新工作。
- [ ] 两个并发请求的历史、预算和取消状态互不影响；默认诊断和 HTTP 错误不泄露提示词、参数、凭证或异常原文。
- [ ] HTTP 成功结构及各失败类型的状态码与最终拍板一致。

测试分为假 ChatModel/假工具的确定性单元测试，以及本地 HTTP provider 桩的适配器测试。后者专门覆盖工具 schema 出站、tool_calls/finish reason 入站、消息回放和取消/超时行为。不请求真实模型，不用真实凭证。

按后续实际改动运行仓库检查和后端检查；数据库未在本提案范围内，不为此增加表或数据库集成测试。

## 12. 待拍板清单

可以按编号确认或修改，不必一次讨论所有未来能力。

| 编号 | 需要决定的事项 | 推荐答案 | 不选择推荐方案的主要代价 |
| --- | --- | --- | --- |
| D1 | 循环由谁实现 | 手写 `Agent` 循环，Spring AI 只承接模型与工具类型 | 框架接管会降低主流程的可见性 |
| D2 | 首版交互形态 | 同步完整响应，先保持当前接口成功结构 | SSE 需要事件语义、客户端消费与取消传播设计 |
| D3 | 批量工具调度 | 支持多调用，全部串行 | 并发需额外定义工具安全性、数量上限、顺序和取消行为 |
| D4 | 工具错误策略 | 名称/参数/可预期业务错误回填；异常、超时终止 | 全部 fail-fast 更简单但无法修正参数；全部继续会掩盖执行状态不明 |
| D5 | 限次后的行为 | 12 次包含最终回答，最后一次仍调工具则硬停止且不执行 | 文本收尾模式需要单独保留或增加模型预算，并改变计数语义 |
| D6 | 首批工具 | 两个无副作用本地工具 add/multiply，先验证流程 | 换业务工具需同时确定数据、授权和错误语义 |
| D7 | 状态范围 | 一请求一 run，局部内存，不保存跨请求对话 | 多轮会话与恢复需要 session 身份、持久化和并发控制 |
| D8 | 重试与恢复 | SDK 与运行时均不自动重试，不续写、不自动压缩 | 自动恢复需补充错误分类、状态记录和额外预算 |
| D9 | 新增两个限制 | 工具总数 32、单结果 8000 字符作为起始值 | 也可调整数值，但不能只有模型轮次上限 |
| D10 | 取消和高级扩展 | 内部取消测试；暂不做取消接口、Hooks、MCP、子 Agent | 若本阶段就需要这些能力，应先扩大需求和验收范围 |
| D11 | HTTP 失败映射 | 502 模型/协议错误、422 预算耗尽、504 超时 | 可以统一其他映射，但前端必须能区分未完成与成功 |

## 13. 实施步骤与完成记录

**实施步骤：尚未安排。** 用户确认目标、范围与关键决策后，再补充具体文件设计、分步开发顺序及验证命令，不以本文建议自动授权开发。

本轮交付仅为源码调研和提案。未修改 Agent、工具、Controller、配置或测试实现；未运行五个参考项目或模型服务。已执行 `./scripts/check.sh repo`：仓库内容检查通过，16 项仓库脚本测试通过。该检查不等于 ReAct 流程已实现或通过测试，未来开发验收保持未勾选。

## 附录：源码定位

以下索引同时给出本地路径、关键符号和固定提交链接。以符号和提交定位为主；更新参考仓库后行号可能变化。源码只读参考，不复制其实现；各仓库顶层许可中 OMP/OpenCode/DeepSeek Harness 为 MIT，Codex 为 Apache-2.0；CCB 根目录未见独立 LICENSE，本次不对其再利用许可作结论。若以后借用具体代码，仍需检查该文件及第三方内容的许可。

| 来源 / 本地文件 | 已核对入口与固定版本链接 |
| --- | --- |
| oh-my-pi / [packages/agent/src/agent-loop.ts](../../../harness-references/oh-my-pi/packages/agent/src/agent-loop.ts) | [主循环 L1196](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1196) · [输入队列 L1815](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1815) · [响应分流 L1536](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1536) · [执行准备 L3071](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L3071) · [工具调度 L3830](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L3830) · [deadline L1208](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/agent-loop.ts#L1208) |
| oh-my-pi / [packages/agent/src/types.ts](../../../harness-references/oh-my-pi/packages/agent/src/types.ts) | [循环配置 L172](https://github.com/can1357/oh-my-pi/blob/dd657ab3a31c865efdb0c9c02ff7cd7a47c66bdf/packages/agent/src/types.ts#L172) |
| opencode / [packages/core/src/session/runner/llm.ts](../../../harness-references/opencode/packages/core/src/session/runner/llm.ts) | [drain L57](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L57) · [runStep L209](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L209) · [步数限制 L226](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L226) · [输出续写 L290](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L290) · [遗留调用 L362](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L362) |
| opencode / [packages/core/src/session/runner/step.ts](../../../harness-references/opencode/packages/core/src/session/runner/step.ts) | [attempt L91](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/step.ts#L91) · [重试分流 L200](https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/step.ts#L200) |
| deepseek-harness / [packages/core/agent-loop/src/agent.ts](../../../harness-references/deepseek-harness/packages/core/agent-loop/src/agent.ts) | [turn L298](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L298) · [step L400](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L400) · [结束判定 L547](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L547) · [请求错误 L512](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L512) |
| deepseek-harness / [packages/core/agent-loop/src/tool-calls.ts](../../../harness-references/deepseek-harness/packages/core/agent-loop/src/tool-calls.ts) | [工具批次 L60](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/tool-calls.ts#L60) |
| deepseek-harness / [packages/core/tools/src/index.ts](../../../harness-references/deepseek-harness/packages/core/tools/src/index.ts) | [并发判定 L1311](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/tools/src/index.ts#L1311) |
| codex / [codex-rs/core/src/session/turn.rs](../../../harness-references/codex/codex-rs/core/src/session/turn.rs) | [run_turn L166](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L166) · [停止 Hook L720](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L720) · [继续判定 L604](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L604) · [采样请求 L1707](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L1707) |
| codex / [codex-rs/core/src/tools/registry.rs](../../../harness-references/codex/codex-rs/core/src/tools/registry.rs) | [工具查找 L650](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/registry.rs#L650) |
| codex / [codex-rs/core/src/tools/parallel.rs](../../../harness-references/codex/codex-rs/core/src/tools/parallel.rs) | [工具结果 L78](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/parallel.rs#L78) · [并发准入 L213](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/parallel.rs#L213) |
| codex / [codex-rs/core/src/tools/orchestrator.rs](../../../harness-references/codex/codex-rs/core/src/tools/orchestrator.rs) | [执行策略 L125](https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/orchestrator.rs#L125) |
| claude-code / [README.md](../../../harness-references/claude-code/README.md) | [项目来源 L13](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/README.md#L13) |
| claude-code / [src/query.ts](../../../harness-references/claude-code/src/query.ts) | [queryLoop L393](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L393) · [下一轮 L2053](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L2053) · [工具继续 L756](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L756) · [Stop hooks L1557](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1557) · [执行分支 L1669](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1669) · [中断 L1302](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1302) · [轮次上限 L2033](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L2033) |
| claude-code / [src/services/tools/toolOrchestration.ts](../../../harness-references/claude-code/src/services/tools/toolOrchestration.ts) | [并发分组 L106](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/services/tools/toolOrchestration.ts#L106) |
| claude-code / [src/services/tools/toolExecution.ts](../../../harness-references/claude-code/src/services/tools/toolExecution.ts) | [参数与权限 L641](https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/services/tools/toolExecution.ts#L641) |
| Spring AI 2.0.1 sources.jar / `spring-ai-model/src/main/java/org/springframework/ai/chat/messages/AssistantMessage.java` | [调用消息 L111](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-model/src/main/java/org/springframework/ai/chat/messages/AssistantMessage.java#L111) |
| Spring AI 2.0.1 sources.jar / `spring-ai-model/src/main/java/org/springframework/ai/chat/messages/ToolResponseMessage.java` | [工具结果 L75](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-model/src/main/java/org/springframework/ai/chat/messages/ToolResponseMessage.java#L75) |
| Spring AI 2.0.1 sources.jar / `spring-ai-model/src/main/java/org/springframework/ai/tool/ToolCallback.java` | [工具接口 L33](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-model/src/main/java/org/springframework/ai/tool/ToolCallback.java#L33) |
| Spring AI 2.0.1 sources.jar / `models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java` | [工具选项 L529](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java#L529) |
| Spring AI 2.0.1 sources.jar / `models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java` | [单次调用 L196](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java#L196) · [元数据回放 L676](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java#L676) |
| Spring AI 2.0.1 sources.jar / `auto-configurations/models/spring-ai-autoconfigure-model-chat-client/src/main/java/org/springframework/ai/model/chat/client/autoconfigure/ChatClientAutoConfiguration.java` | [自动工具配置 L125](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/models/spring-ai-autoconfigure-model-chat-client/src/main/java/org/springframework/ai/model/chat/client/autoconfigure/ChatClientAutoConfiguration.java#L125) |
