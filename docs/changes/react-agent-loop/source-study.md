# 自己实现 Agent：主流程与五个源码项目的设计取舍

调研日期：2026-10-21。范围：本地 oh-my-pi、OpenCode、DeepSeek Harness、Codex、CCB 五份源码。

本文回答两个问题：**一个 Agent 到底怎样持续执行任务；不同项目把同一套循环做出了哪些优势。** 最后给出适合手写 Java Agent 的主流程，不修改业务实现。

证据边界：本文依据固定提交的源码静态阅读，没有启动参考项目、执行其测试或比较真实任务效果。“优势”指从实现机制推导的适用场景，不代表模型能力、成功率或性能排名。CCB 是第三方 `claude-code-best/claude-code`，不能作为 Anthropic 官方 Claude Code 内部实现的证据。

## 1. 先给出核心结论

五个项目在本次研究的执行路径上，都包含下面这个闭环：

```text
准备本轮上下文和工具定义
    ↓
请求模型
    ↓
保存模型的完整输出
    ↓
有工具调用？ ── 有 → 校验并执行 → 写回工具结果 → 下一轮
    │
    无
    ↓
判断是否满足停止条件 → 返回回答 / 等待输入 / 异常终止
```

**模型负责提出下一步，程序负责真正执行，以及决定是否允许继续。** 模型不能因为输出了一个工具名就直接获得执行权限，也不会在工具运行之后自动知道结果；下一次请求必须把结果交给它。

成熟 Agent 比这个循环复杂，是因为它们还要解决运行中插话、并发、上下文增长、取消、错误恢复、权限和会话记录。主循环的控制原则仍然相同。

对手写实现，建议先借鉴三件事：

1. 从 oh-my-pi 学习显式的模型—工具循环和消息回填。
2. 从 DeepSeek Harness 学习一次任务、一次模型步骤、一次请求尝试之间的边界。
3. 从 Codex 学习把工具执行权限与模型决策分开。

OpenCode 更适合下一阶段研究持久会话与流式恢复；CCB 更适合研究生成器事件输出和停止后的继续策略。对应源码与具体代价见第 4 节。

## 2. 用一个完整任务看主流程

假设用户要求：“查订单 A 的当前状态，解释为什么还没发货。”注册了两个只读工具：`getOrder` 和 `getShipment`。

### 2.1 初始化：给模型可用信息和可用动作

程序准备两部分输入：

- 消息：系统要求、用户问题、已有对话或本轮需要的上下文。
- 工具定义：名称、用途、参数 schema。真正执行工具的代码留在服务端。

工具定义必须随需要工具能力的模型请求一起提供，不能只在第一轮提供。历史中的一次工具调用，不等于向下一次请求注册了这个工具。

### 2.2 第一轮：模型决定查订单

```text
发送：
  system: 根据工具返回的信息回答订单问题
  user: 查订单 A 的当前状态，解释为什么还没发货
  tools: getOrder(orderId), getShipment(shipmentId)

收到：
  assistant:
    text: 我先查询订单信息。
    tool_calls:
      id=c1, name=getOrder, arguments={"orderId":"A"}
```

“我先查询订单信息”只是过程说明。只要还有工具调用，就不能把这段文本当最终结果返回并结束。

程序先保留完整 assistant 消息，再查找工具、校验参数和访问权限，实际执行 `getOrder`，随后写入：

```text
tool:
  tool_call_id=c1
  result={"status":"待发货","shipmentId":"S1"}
```

### 2.3 第二轮：模型根据上一步结果决定继续查物流

这次请求携带此前的消息，并追加订单查询结果。模型读到 `shipmentId=S1`，才能提出：

```text
assistant: tool_calls=[{id=c2, name=getShipment, arguments={"shipmentId":"S1"}}]
tool: tool_call_id=c2, result={"status":"备货中","reason":"缺少一个配件"}
```

程序执行、回填，再请求模型。

### 2.4 第三轮：模型返回正常回答

```text
assistant: 订单 A 仍在备货，因为缺少一个配件，暂未发货。
无 tool_calls，响应正常结束。
```

单次、无插话的最小 Agent 此时结束。这次运行包含 **3 次模型调用、2 次工具调用**。

这里有三个容易写错的点：

- **保留调用和结果的配对。** `c1` 必须对应 `c1` 的结果，不能只把结果拼成一句普通用户消息。
- **保留完整模型消息。** 不能只抽取文本后重建 assistant；适配器还可能需要回放协议元数据或内容块。
- **后续动作依赖运行结果。** 在不知道 `S1` 前，不能让两个依赖调用盲目并行。一个批次里有多个工具，也不代表存在变量替换或依赖调度能力。

这种回填分别体现在 [OMP 主循环][OMP-1]、[DeepSeek step][DS-1]、[CCB 下一轮状态构造][CCB-3]；不同模型协议的外层消息角色可能不同，逻辑关系一致。

## 3. 主循环实际上要管哪些事情

### 3.1 先统一三个计数层次

本文采用以下名称，避免照搬不同仓库中含义不同的 turn/step：

| 层次 | 含义 | 订单例子 |
| --- | --- | --- |
| Run，一次运行 | 从接受任务到本次终止 | 整个订单解释任务 |
| Iteration，一次迭代 | 一次模型响应及其工具批次 | 查订单、查物流、最终回答，各一次 |
| Attempt，一次请求尝试 | 发起一次底层模型请求 | 若同一请求失败后重试，可能有多个 attempt |

一次 iteration 可以有多个工具调用，也可以没有工具。允许重试时，iteration 数量不能代替真实请求次数和成本计数。

### 3.2 一轮的执行顺序

| 阶段 | 程序负责什么 | 为什么需要 |
| --- | --- | --- |
| 进入本轮 | 检查预算、取消、剩余运行时间 | 防止无界继续 |
| 准备输入 | 组装消息、工具定义、模型配置 | 决定模型本轮能看到和使用什么 |
| 请求模型 | 接收完整响应，或正确聚合流式内容 | 得到可执行的完整调用 |
| 判定响应 | 区分正常输出、截断、错误、中断 | “没有工具”不一定是正常完成 |
| 保存输出 | 保留完整 assistant 和调用 ID | 为下一轮构造有效历史 |
| 执行工具 | 名称解析、参数校验、授权、实际运行 | 将建议转成受控动作 |
| 记录观察 | 每个调用关联明确结果 | 让模型根据真实执行结果继续 |
| 决定继续 | 再请求模型，或记录明确终止原因 | 将模型回答与运行状态分开 |

同步实现可以按这个顺序写直线代码。流式实现会让“收到调用—执行工具”与后续模型输出重叠，但必须处理调用完整性、取消以及结果落定，不能看到参数字符串的一部分就执行。

### 3.3 什么时候结束

最小实现的正常结束条件可以是：**模型响应完整、正常结束、没有工具调用。** 其他情况要区分：

| 情况 | 建议结果 |
| --- | --- |
| 正常回答且无工具调用 | `COMPLETED`，表示本次对话执行正常结束 |
| 模型输出被截断 | `INCOMPLETE`，不能冒充完成 |
| 轮次或工具预算耗尽 | `LIMIT_REACHED` |
| 用户取消 | `CANCELLED` |
| 超时 | `TIMED_OUT` |
| 模型协议异常、不可恢复的工具异常 | `FAILED` |

这些是本文建议的状态，不是五个项目统一使用的枚举。`COMPLETED` 也不自动证明业务成功：若任务是修改订单状态，应以实际工具结果或业务校验为依据，而不是模型说“操作完成”就算成功。

有交互队列或 Stop Hook 后，还需要额外检查：“是否有新输入需要处理”“是否有明确的检查结果要求补做”。这是成熟项目在无工具调用后仍可能继续的原因。

## 4. 五个项目分别如何组织循环

### 4.1 oh-my-pi：显式循环，适合研究交互和扩展点

主入口是 `packages/agent/src/agent-loop.ts` 的 `runLoopBody`。

```text
runLoopBody
  → 接纳本轮输入，准备上下文
  → 请求模型，保存 assistant
  → 提取可执行调用，executeToolCalls
  → 写入工具结果
  → 有工具或 steering 输入：继续内循环
  → 准备结束时再检查 aside / follow-up / 迟到的 steering
  → 无待处理输入：退出外循环
```

这两层循环分别解决“当前任务还有动作”和“当前动作结束时又来了输入”。源码在停止边界再次读取队列，避免用户恰好在结束时输入的消息被搁置。[OMP-1][OMP-1] [OMP-2][OMP-2]

工具调用前有 `beforeToolCall`，上下文和模型请求也有独立扩展点。执行前 Hook 修改参数后会再次校验，并同步更新历史里的参数，使记录与实际执行一致。[OMP-3][OMP-3] [OMP-5][OMP-5]

工具调度支持 `shared` / `exclusive`：共享调用可以重叠，独占调用等待前面的共享调用；该快照中未声明的模式默认是 `shared`。这是一项具体设计选择，不能概括为所有 Agent 都默认串行。[OMP-4][OMP-4]

**优势：**交互输入、工具循环和扩展点直接可见，适合学习一个可嵌入 Agent 内核怎样接收运行中指令、给工具增加统一策略。

**代价：**队列、协议兼容、工具重放和其他恢复分支已经很多。学习时先抓主干，逐个看分支解决了什么问题，不宜把整个文件作为首版模板。

**手写时借鉴：**显式循环、完整消息回填、工具前后的少数边界。等出现“运行中插话”的明确需求，再添加输入队列。

### 4.2 OpenCode：会话驱动，擅长组织流式执行和恢复

本地版本的核心目录是 `packages/core/src/session/runner/`，入口关系为：

```text
SessionRunner.drain
  → 读取会话历史与 inbox，接纳输入
  → runStep：管理一次逻辑 step 的策略
      → 上下文压缩、步数限制、请求准备
      → SessionStep.attempt：一次实际执行
          → 消费 provider stream
          → 发布完整 tool-call 后启动本地工具
          → 等待工具、保存结果、落定 step
      → 按 outcome 决定重试、继续、压缩或返回
```

`runStep` 管“这一步如何推进”，`attempt` 管“一次尝试产生了什么”。这种区分让重试不必伪装成一个全新的用户任务。[OC-1][OC-1] [OC-2][OC-2]

最值得关注的是失败时的分流：没有输出时可以重试；已经产生输出时，可以基于已记录历史继续。对于 `providerExecuted` 的工具调用不会再次本地执行，避免把服务端已执行的动作再执行一遍。[OC-2][OC-2]

达到 steps 阈值时，它保留工具定义，但将 tool choice 设为 none，工具执行入口也拒绝执行，留给模型文本收尾的机会。因此这里的“步数限制”不是禁止所有后续模型请求的简单硬上限。[OC-1][OC-1] [OC-2][OC-2]

**优势：**执行围绕可记录的会话状态组织，适合长期对话、过程展示和异常后继续；逻辑步骤与传输尝试分开，恢复策略更容易表达。

**代价：**持久化、事件投影、输入队列和流式工具执行相互关联。恢复运行状态也不等于保证外部动作只执行一次，写操作仍需幂等或对账机制。

**手写时借鉴：**先明确 iteration 与 attempt 的差别；等需要会话恢复时再引入持久状态，不必为一个同步调用先实现完整事件系统。

### 4.3 DeepSeek Harness：生命周期边界清楚，工具批次处理细致

核心是 `packages/core/agent-loop/src/agent.ts` 的 `ReactLoopAgent`：

```text
驱动器推进 turn
  → preStep 接纳 next-turn / next-step 输入
  → step
      → 准备请求，从 Session 可见历史构建输入
      → 消费模型流，保存 assistant 消息
      → 根据 finish 状态判断截断或异常
      → executeToolCalls
  → 无后续动作时触发 turn-stopping 边界
  → 有 next-step 输入则继续，否则结束 turn
```

这里 turn 表示更外层的一次交互，step 表示模型—工具推进。Session 记录 `turn/start`、`step/start`、`assistant/message`、`tool/result`、`step/end`、`turn/end` 等事实，便于区分当前到底停在哪个阶段。[DS-1][DS-1]

模型请求错误交给 `agent/request-error` 扩展点决定是否重试，而不是主循环默认吞掉所有异常。输出到达 max-tokens 时保留截断状态；工具也可以通过 `concludesTurn` 表达结束当前 turn 的意图。[DS-1][DS-1]

工具执行器支持有界并发池及独占屏障，结果按模型调用顺序落定；取消时停止补充新任务，收敛已启动调用，并为未启动调用记录明确的跳过结果。并发安全判定必须明确返回 true，否则按独占处理。[DS-2][DS-2] [DS-3][DS-3]

**优势：**生命周期、请求尝试和工具执行的职责分开，适合研究可观察、可扩展的运行时；并发执行与结果顺序也有明确约定。

**代价：**插件事件、Session 派生状态、取消收敛都会增加学习成本。不能把它简化为几个 Hook 加一个无限循环。

**手写时借鉴：**把一次任务、一次模型调用、一个工具批次的边界写清楚；先串行，后续再借鉴“明确声明安全才允许并发”的策略。

### 4.4 Codex：主循环与工具执行治理分层

研究入口是 `codex-rs/core/src/session/turn.rs` 的 `run_turn`：

```text
run_turn
  → 接纳输入，准备本步骤上下文和历史
  → run_sampling_request
      → 消费模型输出，分发工具，收集结果
  → 判断 model_needs_follow_up 与待处理输入
  → 必要时管理上下文压缩
  → 无继续需求时执行 Stop Hooks
  → Hook 要求补做则继续，否则结束
```

继续条件不只看工具：`needs_follow_up = model_needs_follow_up || has_pending_input`。停止 Hook 也可以返回阻止结束及继续提示，使本轮再次推进。[CX-1][CX-1]

工具并发通过共享/独占执行锁协调；是否支持并发影响准入方式。涉及执行策略的工具还有独立 `ToolOrchestrator`，处理批准、禁止以及所选沙箱下的执行等步骤。[CX-2][CX-2] [CX-3][CX-3]

**优势：**在执行命令、访问文件等能力上，能把“模型想做什么”与“运行时允许怎样做”分开，适合研究有真实环境副作用的 Agent。取消、并发、权限不需要全都塞进模型调用代码。

**代价：**会话、协议、工具运行时和环境策略的层次较多。直接照搬会让一个只读业务工具 Agent 承担大量不必要的系统复杂度。

**手写时借鉴：**主循环决定执行哪个工具；工具入口统一校验允许的操作范围。增加 Bash、文件写入等工具之前，再研究隔离执行与权限策略。

这里讨论的是本地固定提交的开源实现，不据此推断当前桌面产品或服务端的全部行为。

### 4.5 CCB：生成器输出事件，用显式状态推进下一轮

入口是 `src/query.ts` 的 `query` → `queryLoop`。虽然局部注释仍有 recursive call 的表述，当前读到的 `queryLoop` 主体实际通过 `state = next` 和循环推进。

```text
queryLoop(state)
  → 准备消息，处理上下文
  → 请求模型，yield 过程消息
  → 收集工具调用
  → 使用流式工具执行器，或 runTools 执行批次
  → 收集 assistantMessages + toolResults
  → 更新 state，进入下一轮
  → 无工具时处理错误、恢复分支、Stop Hooks 和终止
```

生成器使“产生过程事件”和“最终结束”自然分开。非流式工具路径按 `isConcurrencySafe` 划分连续批次；不安全或无法确认安全的调用按串行方式处理。[CCB-1][CCB-1] [CCB-4][CCB-4]

Stop Hook 可以把未满足的条件作为新消息加入下一轮。源码还专门跳过 API 错误后的正常 Stop Hook 检查，避免形成“API 错误—Hook 拦截—再请求—再错误”的循环。[CCB-2][CCB-2]

`maxTurns` 的这条检查路径在工具结果处理后、进入下一轮前进行；部分恢复分支保持同一个 `turnCount`。所以不能把它直接解释成“所有模型请求总次数上限”。[CCB-2][CCB-2] [CCB-3][CCB-3]

**优势：**适合研究如何持续输出运行事件，以及怎样把补充检查结果重新喂给模型；状态转换在同一执行路径中可追踪。

**代价：**功能分支密集，多个继续机制容易相互影响；需要单独约束恢复、Hook 和普通工具迭代的预算。第三方复原的来源边界也必须保持清楚。[CCB-0][CCB-0]

**手写时借鉴：**需要流式进度时再设计事件输出；需要完成检查时，优先使用明确的业务条件，不把 Stop Hook 本身当作完成证明。

## 5. 横向看：各自最值得学什么

下表比较本次阅读路径的设计侧重，不表示其他项目没有同类能力。

| 项目 | 主要优势 | 最适合借鉴的场景 | 直接照搬的主要代价 |
| --- | --- | --- | --- |
| oh-my-pi | 显式循环、交互队列、可插入的执行边界 | 可嵌入 Agent、运行中插话、工具扩展 | 队列和协议兼容分支多 |
| OpenCode | 会话驱动、step/attempt 分层、流式落定 | 长期对话、运行过程展示、失败后继续 | 持久状态和恢复协议复杂 |
| DeepSeek Harness | 生命周期分明、事件扩展、工具批次有序收敛 | 可观察的运行时、插件式能力、受控并发 | 事件和状态派生需要统一约定 |
| Codex | 工具执行与权限、沙箱、并发治理分层 | 操作文件、执行命令、真实环境任务 | 系统边界多，移植成本高 |
| CCB | 生成器事件流、显式下一轮状态、停止反馈 | 连续输出进度、结果检查后补做 | 恢复与继续条件容易交叉 |

对自己实现 Agent，选型不必变成“哪个项目整体最好”。更实用的做法是：**先实现共同闭环，再按实际问题引入对应机制。**

## 6. 自己实现：先写清楚一个循环

### 6.1 最小实现只需要三块

```text
Agent.run
  持有本次消息、轮次、截止时间，显式控制循环

ModelAdapter.call
  将消息和工具定义转换成模型请求，返回完整响应和结束状态

已注册工具
  根据工具名找到实现，校验参数和权限，返回结构化结果
```

开始时这三块可以只是已有模型接口、一个 Agent 类和一组工具实现，不必先创建通用工作流引擎或大量接口层。

### 6.2 核心伪代码

下面是 **Java 风格的流程伪代码**。`ModelReply`、`RunResult` 等仅用于说明，不是现有类，也不是可直接复制编译的 Spring AI 示例。

```java
RunResult run(String input) {
    List<Message> messages = initialMessages(input);
    Instant deadline = now().plus(runTimeout);

    try {
        for (int round = 1; round <= maxModelCalls; round++) {
            checkCancelledAndDeadline(deadline);

            // 每次带工具定义；模型适配层只完成本次请求。
            ModelReply reply = model.call(messages, toolDefinitions, deadline);
            messages.add(reply.fullAssistantMessage());

            // 先排除截断、异常和取消，不能仅凭无工具就判成功。
            if (!reply.isCompleteAndUsable()) {
                return RunResult.incomplete(reply.reason());
            }
            List<ToolCall> calls = reply.toolCalls();
            if (calls.isEmpty()) {
                return RunResult.completed(reply.text());
            }

            // 最后一轮仍要调用工具：硬停止，避免执行后已无预算观察结果。
            if (round == maxModelCalls) {
                return RunResult.limitReached();
            }

            validateCallIdsAndBatchBudget(calls);
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : calls) {
                checkCancelledAndDeadline(deadline);
                // 查找已注册工具 → 参数/权限校验 → 执行。
                // 可修正的输入错误返回失败结果；状态不明的异常向外抛出。
                results.add(executeRegisteredTool(call, deadline));
            }
            messages.addAll(toToolMessages(results)); // 保留对应 call ID
        }
        return RunResult.limitReached();
    } catch (RunCancelled e) {
        return RunResult.cancelled();
    } catch (RunTimedOut e) {
        return RunResult.timedOut();
    } catch (Exception e) {
        return RunResult.failed(e);
    }
}
```

先让这段主流程可读，再补充工程细节。辅助方法有几个必须明确的契约：

- `model.call` 必须是一次模型请求，不能在内部偷偷完成多轮工具循环；具体框架版本要核对其自动工具执行机制。
- `maxModelCalls` 包含最终回答那一次；若以后增加重试，所有真实模型请求也要计入预算。
- `executeRegisteredTool` 只能执行注册且授权的工具；工具总调用数单独限制，避免一轮生成大量调用绕过轮次限制。
- 模型和工具调用的超时应受 `min(单次上限, 本次运行剩余时间)` 约束。只在请求前后检查时间，无法阻止一次阻塞调用超时。
- 工具超时或运行失败不一定能撤销外部动作。若执行结果不明，停止并标记状态，不自动重跑写操作。
- 伪代码按完整批次回填，未实现持久化恢复。若批次中途失败，已成功的外部动作不会回滚；以后保存会话时必须逐次记录真实结果并处理未落定调用。

### 6.3 哪些错误交给模型修正

| 错误 | 处理建议 | 原因 |
| --- | --- | --- |
| 工具名称错误 | 返回 `UNKNOWN_TOOL`，在预算内继续 | 模型可以换成已注册工具 |
| 参数缺失或格式错误 | 返回结构化校验错误 | 模型可能修正参数 |
| 预期业务失败，如订单不存在 | 回填实际业务结果 | 这是模型回答所需的观察 |
| 权限不允许 | 明确记录未执行；按业务策略结束或给受限回答 | 不能让模型通过重试绕过权限 |
| 工具执行状态不明 | 终止并暴露不确定状态 | 避免重复副作用 |
| 模型通信错误或协议异常 | 首版终止，后续再设计有界恢复 | 错误类型、输出进度会影响可否安全重试 |

“可修正错误回填”和“基础设施失败终止”是两条不同的路径。把所有异常都转成一句 tool error 然后无限继续，会掩盖真正的执行问题。

### 6.4 先做什么，后加什么

第一步：同步模型响应、串行工具、局部内存消息、预算与明确终止原因。用假模型验证以下路径：直接回答；工具后回答；多轮依赖工具；参数修正；截断；预算耗尽；取消和工具超时。

第二步：有过程展示需求，再增加事件输出。先定义“消息开始、文本片段、工具开始、工具结束、运行结束”的含义，再选 SSE 等传输方式。

第三步：出现独立且耗时的工具批次，再加入有界并发。并发安全由工具定义和实际访问资源决定，不能仅靠模型声明。

第四步：出现跨请求对话、恢复或长任务需求，再加入 Session 存储、输入队列、上下文压缩。持久历史、发给模型的上下文、运行事件可以分开，避免压缩模型上下文时把审计事实也删掉。

这些是实现顺序建议，未作为已批准需求，也不表示当前 LoopCraft 已实现其中能力。

## 7. 如果“不同 Agent”指的是不同执行范式

上面比较的是五个项目的运行时实现。ReAct、规划执行、Graph、Multi-Agent 则是另一层设计，可以组合，不能与项目名称一一对应。

| 形式 | 执行方式 | 优势 | 主要限制 |
| --- | --- | --- | --- |
| 单 Agent ReAct | 模型观察结果后动态决定下一步 | 适合步骤未知、探索性强的任务；核心实现小 | 需要预算和停止策略，路径不完全确定 |
| 规划—执行 | 先生成计划，再逐步执行，必要时重规划 | 适合目标较大、需要显示进度和检查遗漏的任务 | 计划可能过时，规划本身有成本 |
| 固定 Workflow / Graph + Agent | 程序规定阶段及流转，部分节点运行 Agent | 适合已有业务阶段、审批或确定性校验的任务 | 灵活性受图结构限制，循环层次要区分 |
| 多 Agent 协作 | 划分子任务，分别执行后汇总 | 适合可独立并行、需要上下文隔离的工作 | 协调和汇总成本高，共享状态更难管理 |

这是架构层面的推导，不是本次对五个项目进行的多 Agent 功能审计。

例如“采集信息 → 分析 → 执行 → 验证”可以是外层固定 Graph；“分析”节点内部仍然是模型—工具循环。给单 Agent 换一个系统提示词，或把任务写成计划文本，也不会自动生成可靠的调度、恢复与完成检查机制。

对当前“自己写主流程”的目标，先实现单 Agent ReAct 最容易验证。将来如果业务阶段明确，再在外面增加 Graph；如果子任务真正独立，再引入多 Agent。

## 8. 源码版本与阅读入口

参考源码位于仓库根目录下的 `harness-references/`（仅作本地参考，不随项目提交）。五个参考仓库在本次检查时工作区均无未提交变更；未拉取远端更新。oh-my-pi 的本地 HEAD 已不同于 10 月 18 日旧提案，本文采用下列当前快照。

| 仓库 | 固定提交 |
| --- | --- |
| can1357/oh-my-pi | [ba85aef0b343](https://github.com/can1357/oh-my-pi/tree/ba85aef0b343a25f3c90b4f86628a9bd92d8414d) |
| anomalyco/opencode | [8c173a8f3805](https://github.com/anomalyco/opencode/tree/8c173a8f3805d8e749d44311054b07e37a1b2ce1) |
| deepseek-ai/deepseek-harness | [687f282e8625](https://github.com/deepseek-ai/deepseek-harness/tree/687f282e8625c6b8d11ff58211a90524be2e4317) |
| openai/codex | [ce0323f2f2d3](https://github.com/openai/codex/tree/ce0323f2f2d3b25e82806cd555237da3db4e1897) |
| claude-code-best/claude-code | [77a7934e15d6](https://github.com/claude-code-best/claude-code/tree/77a7934e15d69da13879112ed7db695c9ee7a52a) |

建议阅读顺序：先看 OMP-1 和 DS-1，再看各自工具执行器；需要持久会话时看 OC-1/OC-2，需要环境权限时看 CX-3，需要停止反馈时看 CCB-2。

| 编号 | 本地源码 | 阅读重点 / 固定版本 |
| --- | --- | --- |
| OMP-1 | `oh-my-pi/packages/agent/src/agent-loop.ts`（L1200） | [runLoopBody：主循环][OMP-1] |
| OMP-2 | `oh-my-pi/packages/agent/src/agent-loop.ts`（L1801） | [停止边界与输入队列][OMP-2] |
| OMP-3 | `oh-my-pi/packages/agent/src/agent-loop.ts`（L3075） | [prepareToolCallDispatch：参数与 Hook][OMP-3] |
| OMP-4 | `oh-my-pi/packages/agent/src/agent-loop.ts`（L3831） | [shared / exclusive 调度][OMP-4] |
| OMP-5 | `oh-my-pi/packages/agent/src/types.ts`（L231） | [上下文与执行扩展点][OMP-5] |
| OC-1 | `opencode/packages/core/src/session/runner/llm.ts`（L209） | [runStep：步数、压缩、恢复策略；drain 在同文件前部][OC-1] |
| OC-2 | `opencode/packages/core/src/session/runner/step.ts`（L91） | [attempt：流式工具执行与结果落定][OC-2] |
| DS-1 | `deepseek-harness/packages/core/agent-loop/src/agent.ts`（L298） | [turn / step：生命周期与请求分流][DS-1] |
| DS-2 | `deepseek-harness/packages/core/agent-loop/src/tool-calls.ts`（L60） | [executeToolCalls：批次调度、取消和结果顺序][DS-2] |
| DS-3 | `deepseek-harness/packages/core/tools/src/index.ts`（L1311） | [executionMode：并发安全判定][DS-3] |
| CX-1 | `codex/codex-rs/core/src/session/turn.rs`（L166） | [run_turn：主流程，继续条件在 L604，Stop Hooks 在 L720][CX-1] |
| CX-2 | `codex/codex-rs/core/src/tools/parallel.rs`（L213） | [工具共享与独占准入][CX-2] |
| CX-3 | `codex/codex-rs/core/src/tools/orchestrator.rs`（L125） | [ToolOrchestrator.run：批准与沙箱执行策略][CX-3] |
| CCB-0 | `claude-code/README.md`（L13） | [第三方项目来源声明][CCB-0] |
| CCB-1 | `claude-code/src/query.ts`（L393） | [queryLoop：生成器与循环状态][CCB-1] |
| CCB-2 | `claude-code/src/query.ts`（L1538） | [API 错误与停止 Hook 分流][CCB-2] |
| CCB-3 | `claude-code/src/query.ts`（L2033） | [maxTurns 与下一轮状态][CCB-3] |
| CCB-4 | `claude-code/src/services/tools/toolOrchestration.ts`（L106） | [partitionToolCalls：并发安全分组][CCB-4] |

[OMP-1]: https://github.com/can1357/oh-my-pi/blob/ba85aef0b343a25f3c90b4f86628a9bd92d8414d/packages/agent/src/agent-loop.ts#L1200
[OMP-2]: https://github.com/can1357/oh-my-pi/blob/ba85aef0b343a25f3c90b4f86628a9bd92d8414d/packages/agent/src/agent-loop.ts#L1801
[OMP-3]: https://github.com/can1357/oh-my-pi/blob/ba85aef0b343a25f3c90b4f86628a9bd92d8414d/packages/agent/src/agent-loop.ts#L3075
[OMP-4]: https://github.com/can1357/oh-my-pi/blob/ba85aef0b343a25f3c90b4f86628a9bd92d8414d/packages/agent/src/agent-loop.ts#L3831
[OMP-5]: https://github.com/can1357/oh-my-pi/blob/ba85aef0b343a25f3c90b4f86628a9bd92d8414d/packages/agent/src/types.ts#L231
[OC-1]: https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/llm.ts#L209
[OC-2]: https://github.com/anomalyco/opencode/blob/8c173a8f3805d8e749d44311054b07e37a1b2ce1/packages/core/src/session/runner/step.ts#L91
[DS-1]: https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/agent.ts#L298
[DS-2]: https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/agent-loop/src/tool-calls.ts#L60
[DS-3]: https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/core/tools/src/index.ts#L1311
[CX-1]: https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/session/turn.rs#L166
[CX-2]: https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/parallel.rs#L213
[CX-3]: https://github.com/openai/codex/blob/ce0323f2f2d3b25e82806cd555237da3db4e1897/codex-rs/core/src/tools/orchestrator.rs#L125
[CCB-0]: https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/README.md#L13
[CCB-1]: https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L393
[CCB-2]: https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L1538
[CCB-3]: https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/query.ts#L2033
[CCB-4]: https://github.com/claude-code-best/claude-code/blob/77a7934e15d69da13879112ed7db695c9ee7a52a/src/services/tools/toolOrchestration.ts#L106
