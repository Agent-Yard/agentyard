# Session Workflow 重构方案审阅工作记录

## 范围

- 目标文档：`docs/develop_record/session_workflow_redesign.md`
- 目标：审阅设计文档本身，而不是审阅当前项目实现是否与文档一致
- 方法：
  - 按章节逐段阅读，并保留行号引用
  - 从句子级语义一直检查到完整系统模型的一致性
  - 记录关键歧义、相互矛盾之处、缺失契约以及结构性风险

## 审阅计划

1. 术语与约束闭环
2. 状态机与时序语义
3. 数据模型与 API 契约完整性
4. 整体架构形态、可扩展性与落地适配度

## 初步观察

- 文档的核心收敛方向是强的：`session workflow + owner + playbook`，并且明确要移除旧的编排层。
- 主要风险不在顶层方向，而在若干关键边界之间的缝隙：
  - “事件流是事实来源” 与 “playbook run 表是事实来源” 之间的关系
  - “同一时刻只有一个 owner turn” 与异步 reevaluation / signal 之间的关系
  - “session handoff” 与 playbook 完成、等待、恢复行为之间的关系
  - “sharedState 全量替换” 与多参与方更新、回放、调试预期之间的关系
- 文档中不少章节对局部规则描述得很清楚，但跨章节的不变量仍是隐含的。这意味着即便每一节都按字面实现，不同团队仍可能做出彼此不兼容的状态机。

## 工作分桶

### A 类：高严重度问题候选

- workflow 结束 / 重启过程中的生命周期与持久化不变量
- reevaluation 的调度与去重语义
- session handoff 退出语义
- 单活 playbook 约束及其对后续产品形态的影响
- playbook `STEP` 的确定性边界

### B 类：中严重度问题候选

- event schema 对某些决策 / 审计 / 调试场景支撑不足
- owner 转交在上下文 / 消息粒度上的语义不够完整
- 拒绝路径可能保留无效状态修改
- idle timer 语义依赖未写明的实现细节

### C 类：宏观问题

- `sharedState` 是否应该继续保持为单一扁平全局 map
- owner/playbook 二分模型是否足以覆盖所有目标中的“强流程”场景
- 这份文档是否为了第一阶段简化，过度透支了后续不变量

## 讨论记录

### Finding 1: “拒绝路径”术语说明

- 这里的“拒绝路径”特指文档 `§5.2 决策校验与降级` 中“校验失败统一降级路径”这一段。
- 触发条件是：session workflow 收到 `AgentDecision` 后，发现该决策未通过运行时校验。
- 典型场景包括：
  - action 不在当前 owner 的 `allowedActions` 中
  - `SWITCH_OWNER` 目标不合法、不可切换、超过切换次数上限
  - `RUN_PLAYBOOK` 的 playbook 不存在、无权限、已有活跃 playbook、输入不符合 schema
- 该路径的处理动作按文档原文包括：
  - 保留已替换的 `sharedState`
  - 丢弃本次决策及其 `accompanyingReply`
  - 写入 `AGENT_DECISION_REJECTED`
  - 由 session workflow 生成一条降级 `OWNER_REPLY`
  - 设置 `agentTurnActive = false`
  - 再进入 `§5.1` 的 turn 结束通用逻辑
- Finding 1 里说它有风险，就是因为 reevaluation 一旦也走到这条路径，文档没有明说 `pendingOwnerReevaluation` 在何时被消费，因而可能在 turn 结束后再次触发 reevaluation。

### Finding 1: 关于 reevaluation 是否存在 `AgentDecision`

- 文档里的触发源确实是 playbook workflow 完成，见 `§3.3` 与 `§9.3`：
  - playbook 进入终态后，如果当前不在 handoff，则“触发一次 owner reevaluation”
- 但被触发的不是“直接结束整个链路”，而是再开启一次 owner 决策回合：
  - `§2.2` 明确写了：`Owner reevaluation` 与用户消息触发的 agent turn 复用同一条推理接口
  - `§5.1` step 3 写的是：设置 `agentTurnActive = true`，调用 agent-runtime 进行 owner reevaluation
  - `§9.3` step 11 写的是：当前 owner 基于“当前会话状态 + playbook 终态”决定后续动作
- 只要调用了 agent-runtime，这一轮就仍然会返回 `AgentTurnResult`，里面仍然包含 `decision`，也就是 `AgentDecision`
- 所以这里要区分两层：
  - reevaluation 的触发原因：是 playbook workflow 完成
  - reevaluation 的执行形式：仍然是一轮 owner agent turn，因此仍然存在 `AgentDecision`

### Finding 1: 当前讨论结论

- 讨论共识：
  - 同一个 `pendingOwnerReevaluation` 从设计意图上说，应该只被消费一次。
  - 即使 reevaluation 结束后重新进入 turn-end，也不应该再次触发“同一个” reevaluation。
- 因而 Finding 1 更精确的含义不是：
  - “文档一定会导致无限循环”
- 而是：
  - “文档没有把‘pending reevaluation 的消费时点与一次性语义’写成明确不变量，因此实现者只能靠推断补齐”
- 当前问题本质上是规格不闭合，而不是必然逻辑错误。
- 若后续修文，至少需要明确以下一点中的一种：
  - 在开始执行 reevaluation 前就消费并清空 `pendingOwnerReevaluation`
  - 或者引入明确的 reevaluation token / generation，保证同一触发源只会被处理一次

### Finding 1: 设计文档已修复

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复采取的方案是：
  - 保持 `pendingOwnerReevaluation` 为布尔值
  - 明确其为“一次性触发语义”
  - 在 `§5.1` 中规定：一旦决定启动该次 reevaluation，必须先清空该标记，再调用 agent-runtime
  - 明确：即便该轮 reevaluation 走到校验失败统一降级路径，也视为这次 pending 已被处理，不得恢复旧标记或再次触发同一来源的 reevaluation
- 这样处理后：
  - 设计意图与规格文字对齐
  - 同一个 pending reevaluation 不会因重新进入 turn-end 而被重复消费

### Finding 2: 当前讨论澄清

- 当前共识补充：
  - `sharedState` 不包含 `action`
  - `sharedState` 的定位是 LLM / agent 自主维护的会话变量，用于记录后续推理需要的上下文
- 因而 Finding 2 需要收紧，不应表述成：
  - “被拒绝的 action 仍然通过 `sharedState` 间接落库了 action 本身”
- 更准确的剩余问题是：
  - 同一个 turn 中，控制动作虽然被拒绝，但该 turn 产出的上下文变量仍然会提交
  - 这是否合理，取决于文档是否把 `sharedState` 定义为：
    - 纯认知性上下文缓存
    - 还是可影响后续系统行为的持久会话状态
- 如果 `sharedState` 只是 agent 的长期上下文笔记，那么“动作被拒绝但上下文仍保留”未必是问题
- 如果 `sharedState` 中允许出现会影响后续路由、权限判断、业务推进的字段，那么当前语义仍然会让一次被拒绝的 turn 改写未来上下文
- 后续需要继续讨论的核心不是 “`sharedState` 里有没有 action”，而是：
  - `sharedState` 的信任边界是什么
  - 它是否允许承载 operational state

### Finding 2: 当前讨论结论

- 当前结论：
  - `sharedState` 应被视为纯认知性上下文
  - 不应承载 operational state
  - 不应作为权限、路由、业务推进等系统行为的权威依据
- 因而原 Finding 2 的表述偏重，不能再直接表述为“权限模型被削弱”
- 更准确的问题应收敛为：
  - 文档需要把 `sharedState` 的边界写清楚
  - 否则读者会自然把这个全局持久 KV 理解成可驱动系统行为的状态容器
- 在这一前提下，“动作被拒绝但 `sharedState` 保留”属于可接受设计，但前提是文档要明确它只是一种认知性会话变量，而不是操作性状态

### Finding 2: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§2.4 Session 上下文恢复` 中明确：恢复 `sharedState` 仅用于认知性会话上下文，不承担操作性状态恢复
  - 在 `§3.2 sharedState 读写语义` 中明确：`sharedState` 只承载认知性上下文，不得作为 owner 路由、权限判断、playbook 生命周期、handoff 状态、外部回调处理、业务推进条件的权威依据
  - 在 `§5 Session Workflow 内部状态` 中明确：`sharedState` 不是操作性状态容器
- 这样处理后：
  - Finding 2 的风险从“可能被误解为权限/状态漏洞”收敛为“文档边界已写清”
  - “动作被拒绝但 `sharedState` 保留”在当前设计下成为可解释且一致的行为

### Finding 3: 当前讨论结论

- 当前共识：
  - handoff 期间，session 的决策中心是人工
  - playbook 结果、运行状态、等待点等信息，都是为了人工决策服务
  - handoff 期间的人工作业与 playbook 结果，在 handoff 结束后都只作为历史上下文保留
  - handoff 结束后，决策中心切回 LLM / owner，但不需要对 handoff 期间发生的结果执行补偿性触发
- 因而原 Finding 3 不能再表述为：
  - “handoff 退出后遗漏了必须补偿的 catch-up path”
- 更准确的结论是：
  - 设计意图本身没有问题
  - 文档需要更明确地写出：handoff 构成一个决策中心切换边界；handoff 期间发生的 playbook 终态、人工回复和人工决策过程，在 handoff 结束后仅作为可见历史上下文，不会自动触发追补 reevaluation

### Finding 3: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§3.3 Playbook 终态处理` 中明确：若处于 handoff，playbook 终态只暴露给人工侧处理，并作为历史上下文保留；handoff 结束后也不会补偿触发自动 reevaluation
  - 在 `§9.4 Session Human Handoff` 中明确：
    - handoff 期间完成的 playbook 结果只作为人工决策输入与后续可见历史上下文保留
    - handoff 结束时不会自动补偿触发 owner reevaluation
    - 后续只在新的用户消息或新的系统事件到达时进入新的 owner 决策回合
- 这样处理后：
  - handoff 被明确写成“决策中心切换窗口”
  - 文档不会再被误读为需要 handoff 结束后的 catch-up path

### Finding 4: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§5.2 SWITCH_OWNER 专属校验` 中补充 `activePlaybookRunId == null`
  - 明确这条校验就是 playbook 活跃期间禁止 `switch_owner` 的权威落点
- 这样处理后：
  - `§2.3`、`§9.3`、`§10` 里的约束与 `§5.2` 的正式校验清单一致
  - 不再需要依赖权威校验点之外的隐式实现来兜底

### Finding 5: 当前讨论澄清

- 这条 finding 的核心首先不是 LLM 的“上下文压缩策略”，而是 Temporal `session workflow` history 的增长控制策略。
- 需要区分两类“上下文”：
  - LLM / agent 的对话上下文压缩：解决模型输入窗口、认知负载和提示成本问题
  - Temporal workflow history 控制：解决 workflow replay 成本、history size 上限和可运维性问题
- `Continue-As-New` 缺失所暴露的，是第二类问题。
- 不过两者在当前设计里又有耦合：
  - 文档要求 workflow 结束后可从 DB 恢复对话历史与 `sharedState`
  - 因此如果后续采用 workflow 滚动 / 历史压缩策略，通常也需要同时定义会话上下文如何压缩、投影或恢复
- 所以更准确的说法是：
  - Finding 5 直指“缺少 workflow history 控制 / 滚动策略”
  - 而不只是“缺少 LLM 上下文压缩策略”

### Finding 5: 关于“单条 workflow 是否会无限增长”的讨论

- 当前讨论结论：
  - 按文档设计，单条 workflow 不是理论上的“永不结束”
  - 但它也没有被文档约束到一个可证明安全的 history 上界
- 关键原因：
  - idle timer 只在真正进入“空闲且无活跃 playbook / 无 handoff / 无 agent turn”时才开始计时
  - 只要 session 持续活跃，或长期处于 playbook 等待、人工作业窗口，workflow 就可以长时间不结束
  - 因而 history 风险不是“无限”这个数学概念，而是“缺少显式上界与控制机制”
- 后续需要判断的重点不是：
  - “它会不会真的无限增长”
- 而是：
  - “文档是否给出了足够清晰的 history 上界假设”
  - “若没有，是否需要补充滚动 / 截断 / 运维阈值策略”

### Finding 5: 当前讨论结论

- 当前共识：
  - 主路径仍然是“idle 结束 + 新 workflow 恢复”，不引入 `Continue-As-New`
  - 但系统层面仍应设置防御性限制，避免异常情况下单条 workflow 持续增长
- 采用的方向是：
  - 给单条 workflow 增加 guardrail，而不是把主路径改成强制滚动
  - guardrail 命中后进入 `draining`，阻止新消息继续堆积到同一条 workflow
  - 在首个安全结束点正常结束当前 workflow；若暂时无法安全结束，则至少保留执行链路并产出运维告警

### Finding 5: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§2.4 Session 生命周期` 中新增 workflow guardrail 语义
  - 在 `§4.1 Assistant 配置` 中新增 `sessionPolicy.maxWorkflowAge` 与 `sessionPolicy.maxWorkflowHistoryEvents`
  - 在 `§10 第一阶段约束` 中明确：虽然不使用 `Continue-As-New`，但仍为单条 workflow 设置防御性上界
- 这样处理后：
  - 文档不再是“完全依赖 idle end 自然收束”
  - 对异常长活跃 session、异常长 handoff、异常长 playbook 等场景有了明确的系统级防御边界

### Finding 6: 当前讨论结论

- 当前共识：
  - `STEP` 的目标形态不是 workflow 内部直接执行的一小段 Java 确定性逻辑
  - 而是“配置即代码”的脚本化节点
  - 执行时将版本化节点配置/脚本与运行时数据送入受限沙箱环境执行，返回结构化结果
- 这意味着 Temporal 边界必须明确：
  - workflow 只负责图遍历、状态机、等待和路由
  - `STEP` 的沙箱执行必须发生在 activity 边界之外，不能在 workflow 线程内直接执行动态脚本

### Finding 6: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§3.3 Playbook Workflow` 中将 `STEP` 明确为“配置即代码”的脚本化节点，并规定其通过 activity 调用受限沙箱执行
  - 明确 Temporal workflow 的确定性边界：workflow 只做图遍历、状态转移、等待与路由，不直接执行动态脚本
  - 在 `§4.4 Playbook 节点类型` 中补充 `STEP` 的执行模型、版本化要求和结构化输出语义
  - 在 `§8.2 Worker 内部结构` 中补充 `PlaybookStepActivity.java`
- 这样处理后：
  - Finding 6 从“关键执行机制未定义”收敛为“执行模型已明确”
  - `STEP` 与 Temporal 的确定性约束不再冲突

### Finding 7: 当前讨论结论

- 当前共识：
  - 用户消息投递需要在 API 侧以 `sessionId` 为粒度加锁并串行化
  - 理论上同一用户 / 同一 session 不存在并发投递
  - 因而“恢复后重建 workflow 的竞态”不是依赖 Temporal start 语义兜底的问题，而应当是 API 入口显式保证的不变量

### Finding 7: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§2.4 Session 生命周期` 中明确：用户消息投递链路必须按 `sessionId` 串行化，并在检查活跃 workflow / 创建新 workflow / 投递消息期间持有 session 级锁
  - 在 `§2.5 Temporal 消息入口` 中明确：理论上同一用户 / 同一 session 不存在并发用户消息投递到创建链路的情况
  - 在 `§9.1 普通消息处理` 与 `§9.6 Session 空闲超时与恢复` 中将 session 级锁补入典型执行流程
- 这样处理后：
  - 文档把“消息投递串行化”提升为明确入口不变量
  - Finding 7 从“未定义竞态”收敛为“已通过 API 侧串行化明确规避”

### Finding 8: 当前讨论澄清

- 这里讨论的 `ConversationContext`，核心指向的是 LLM / agent-runtime 的输入上下文组织方式
- 除了语义完整性，还需要考虑缓存友好设计
- 因而后续方案评估标准不只是“表达清楚”，还包括：
  - 稳定前缀比例是否足够高
  - 高频变化内容是否被压缩到上下文尾部
  - 是否便于复用已有摘要 / 投影，避免每轮重组大量文本
  - 是否便于未来接入 prompt cache / prefix cache 一类机制

### Finding 8: 当前实现观察

- 已查看 `apps/agent-runtime/agentyard_agent_runtime/main.py` 与相关测试
- 当前实现的关键事实：
  - prompt 已拆成 `system_prompt` + `instruction_block` + `capability_block` + `runtime_context_block`
  - `instruction_block` 基本稳定，描述输出 schema、决策规则、sessionStatePatch 语义
  - `capability_block` 承载：
    - 可用路由
    - 可用工具目录
    - 可用技能目录
    - 已加载技能详情
  - `runtime_context_block` 承载：
    - 当前用户消息
    - 会话记忆
    - shared facts / artifacts / agentScope
    - 工具结果
    - resume_input
    - 当前执行轮次
  - 技能不是一次性全部展开，而是先给 `available_skill_catalog`，由模型通过 `skillReads` 请求，随后把 `loaded_skill_details` 放入 capability block
  - 工具也是先给目录，再由模型通过 `toolRequests` 请求
- 结论：
  - 设计文档里的 `ConversationContext` 方案不能脱离这种 block 化和延迟加载现实，否则会与当前 runtime 方向相冲突

### Finding 8: 基于现实现状的候选方案

- 方案 A：保留三段式 prompt，仅补文档定义
  - 继续沿用 `instruction_block + capability_block + runtime_context_block`
  - 将 `ConversationContext` 定义为 runtime block 所需的输入载荷
  - 将技能/工具加载视为 capability block 的组成部分
  - 优点：改动最小，直接贴合当前实现
  - 缺点：`ConversationContext` 一词容易继续混淆“运行时上下文”和“全 prompt 结构”

- 方案 B：显式定义四层 prompt envelope
  - `systemContext`
  - `instructionContext`
  - `capabilityContext`
  - `runtimeContext`
  - 其中 `ConversationContext` 只对应 `runtimeContext`
  - 优点：术语最清晰，也最利于缓存分层
  - 缺点：需要文档承认“ConversationContext 不是完整 prompt，而只是其中一层”

- 方案 C：把能力加载从上下文概念中剥离
  - 文档中不再用 `ConversationContext` 泛指所有 LLM 输入
  - 改成：
    - `PromptInstruction`
    - `PromptCapabilities`
    - `PromptRuntimeContext`
  - 技能目录、已加载技能详情、工具目录全部归入 `PromptCapabilities`
  - `PromptRuntimeContext` 只放消息、记忆、facts/artifacts/agentScope、最近工具结果、触发事件
  - 优点：与当前实现最一致，也最缓存友好
  - 缺点：会改动术语，需要文档统一替换

- 当前倾向：
  - 若追求最小改动，选方案 A
  - 若追求术语清晰与缓存友好，选方案 C
  - 若想兼顾现实现状与文档表达，方案 B/C 都优于 A

### Finding 8: 当前讨论结论

- 当前选择：按方案 C 细化
- 收敛方向：
  - 不再用 `ConversationContext` 泛指完整 LLM 输入
  - 完整 prompt 拆为：
    - `PromptInstruction`
    - `PromptCapabilities`
    - `PromptRuntimeContext`
  - 其中：
    - 技能目录、已加载技能详情、工具目录、可用路由归入 `PromptCapabilities`
    - 当前消息 / 系统触发、会话记忆窗口、facts / artifacts / agentScope、最近工具结果归入 `PromptRuntimeContext`
  - 设计目标明确包含缓存友好：稳定前缀尽量固定，高频变化内容集中在 runtime 层

### Finding 8: 设计文档已初步对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 将 `agent-runtime` 对外签名从 `ConversationContext` 收敛为 `PromptEnvelope`
  - 在 `§3.2 Agent Runtime` 中新增：
    - `PromptInstruction`
    - `PromptCapabilities`
    - `PromptRuntimeContext`
    的职责边界
  - 明确技能和工具加载属于 capability 层，不属于 runtime context
  - 明确 `ConversationContext` 若指 LLM 输入，后续统一收敛为 `PromptRuntimeContext`
- 后续还需继续细化：
  - system trigger 在 runtime 层的结构化表示
  - capability 层的技能详情加载策略与缓存失效语义
  - runtime 层的预算与裁剪规则

### Finding 8: 关于“结构化块”vs“原生多条 messages”的讨论

- 当前新增分歧：
  - 不是简单选择 `PromptRuntimeContext` 的字段结构
  - 而是选择 LLM 输入的组织范式：
    - 方案一：将大部分内容先拼成少数几个大 block，再作为少数 user messages 输入
    - 方案二：更充分利用模型原生会话能力，用多条 messages 组织整个上下文
- 初步判断：
  - 对当前系统而言，这不是二选一的纯哲学问题，而是缓存友好、技能/工具加载、可观测性、跨模型兼容性之间的工程取舍
  - 当前 runtime 已实现的是“block 化 prompt”，其优点是跨 provider 一致、缓存边界清晰、技能/工具目录与 runtime 上下文分层明确
  - 原生多 messages 的优点是：
    - 更贴近聊天模型训练分布
    - 消息级语义更自然
    - 对 trigger、历史对话、工具结果等信息可以天然分条组织
  - 原生多 messages 的代价是：
    - skills/tools/capabilities 这类半结构化大块信息仍然很难优雅地拆成大量自然消息
    - provider 间对多消息、多 system 指令、缓存命中边界的支持差异更大
    - 如果没有严格分层，容易把能力说明、历史消息、系统事件混成一串高频变化消息，反而损失缓存收益
- 当前倾向：
  - 对这个系统，最优解大概率不是“全 block”或“全 message”
  - 而是混合式：
    - 稳定规则和能力目录继续保留为稳定 block
    - 高频变化的 runtime 层改成原生多条 messages 组织

### Finding 8: 新增约束 - 需要兼容 LLM function calling

- 新增要求：
  - 设计不仅要考虑多条 messages
  - 还要考虑使用 LLM 原生 function calling / tool calling 能力
- 这会进一步影响方案判断：
  - 若未来采用 function calling，工具目录、参数 schema、调用结果这些信息更适合作为模型原生 tool/function 定义，而不是继续塞进自由文本块
  - 同时，skills 更像“可延迟加载的文本能力”，未必适合直接建模为 function
- 初步收敛方向更偏向混合式：
  - 规则与少量稳定说明保留稳定前缀
  - runtime 上下文使用原生多条 messages
  - tools 尽量对齐模型原生 function/tool calling
  - skills 继续作为 capability 层的可按需加载文本能力

### Finding 8: 当前讨论结论（更新）

- 当前共识已从方案 C 继续收敛为“混合式输入模型”：
  - `PromptInstruction` 保留稳定前缀
  - runtime 上下文改为原生多条 messages，而不是单个 runtime block
  - tools 优先对齐模型原生 function/tool calling
  - skills 保持为可按需加载的文本能力
- 因而关键术语应重写为：
  - 不再把完整 LLM 输入理解成单纯的文本 `PromptCapabilities + PromptRuntimeContext`
  - 而是 `PromptInstruction + PromptRuntimeMessages + PromptCapabilities`
  - 其中 `PromptCapabilities` 内部再区分：
    - `Tools / Functions`
    - `Skills`
    - `Routes`

### Finding 8: 设计文档已更新到新方向

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 将 `agent-runtime` 输入模型从上一版的纯文本 `PromptEnvelope` 改为混合式 `LlmInputEnvelope`
  - 明确：
    - `PromptInstruction` 作为稳定前缀
    - `PromptRuntimeMessages` 使用原生多条 messages
    - `Tools / Functions` 优先对齐模型原生 tool/function calling
    - `Skills` 继续按需文本加载
  - 将术语约束从 `PromptRuntimeContext` 收敛为 `PromptRuntimeMessages`
- 这样处理后：
  - 设计文档与当前希望演进到的 runtime 方向一致
  - Finding 8 不再只是“上下文字段未定义”，而是已明确输入组织范式

### Finding 8: provider role 与第一阶段实现范围

- 当前共识：
  - 不同 LLM provider 对原生 role / system prompt / tool calling 协议并不统一
  - 因此设计文档应显式区分：
    - 内部语义层抽象
    - provider 适配层映射
- 第一阶段实现范围：
  - 先只实现 OpenAI-compatible 映射
  - 其他 provider 只在抽象层预留，不在第一阶段落地

### Finding 8: 设计文档已补充 provider 适配原则

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§3.2 Agent Runtime` 中新增 provider role / tool calling 适配原则
  - 明确内部以语义层抽象输入元素，而不是直接绑定某一家 provider 的 role 集合
  - 明确第一阶段仅实现 OpenAI-compatible provider 映射
- 这样处理后：
  - 文档既说明了长期方向，也避免第一阶段目标发散

### Finding 9: 当前讨论结论

- 当前共识：
  - `allowOwnerSwitch` 不需要保留
  - owner 是否可切换，已经由 agent 级 `allowedActions` 与 `switchableOwnerAgentIds` 表达
  - 因而 assistant 级的 `allowOwnerSwitch` 只会形成无效配置面

### Finding 9: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 从 `§4.1 Assistant 配置` 中删除 `ownerPolicy.allowOwnerSwitch`
- 这样处理后：
  - Finding 9 通过删掉无效配置项解决
  - owner switch 的控制面只保留真正生效的配置与校验点

### Finding 10: 当前讨论结论

- 当前共识：
  - `playbook.callableByAgentIds` 不需要保留
  - playbook 调用白名单由 agent 侧 `playbookIds` 单独表达即可
  - 因而 playbook 侧的 `callableByAgentIds` 只会形成冗余授权面

### Finding 10: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 从 `§4.3 Playbook 配置` 中删除 `callableByAgentIds`
  - 从 `§5.2 RUN_PLAYBOOK 专属校验` 中删除 `currentOwnerAgentId ∈ playbook.callableByAgentIds`
- 这样处理后：
  - playbook 调用授权只保留 agent 侧 `playbookIds`
  - Finding 10 通过删除冗余授权面解决

### Finding 11: 建议方向

- 当前问题不是 playbook run 表不能用，而是：
  - `playbook_run` 是可变状态视图
  - session event 是 append-only 事实流
  - 目前 waiting / resumed 轨迹只在前者，不在后者
- 我的建议不是把所有 playbook 内部细节都写入 session event，而是补最小必要事件：
  - `PLAYBOOK_WAITING`
  - `PLAYBOOK_RESUMED`
- 建议 payload：
  - `runId`
  - `nodeKey`
  - `waitingType`（`HUMAN_TASK` / `EXTERNAL_INTERACTION`）
  - `waitingReason`
  - `resumeSource`（恢复时）
- 这样做的收益：
  - session event 足以还原用户/人工/系统视角下的重要时间线
  - 不需要把 playbook 每个节点执行细节全部灌入事件流
  - 保留 `playbook_run` 作为当前状态主视图，保留 event 流作为审计/观测主时间线
- 当前倾向：
  - 建议补这两个事件
  - 不建议把 `RUNNING -> RUNNING` 一类细粒度内部节点流转全部事件化

### Finding 11: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§3.3 Playbook Workflow` 中补充：进入等待点时写 `PLAYBOOK_WAITING`，恢复执行时写 `PLAYBOOK_RESUMED`
  - 在 `§6.1 Session Event` 中新增这两个事件类型与 payload 约束
  - 在 `§9.3 Playbook 执行` 和 `§9.5 人工恢复与外部回调` 中补充等待/恢复时的事件写入流程
- 这样处理后：
  - session event 足以还原 playbook 的关键等待/恢复时间线
  - 同时避免把 playbook 内部每个节点流转都灌入事件流

### Finding 12: 当前讨论结论

- 当前共识：
  - idle 结束后，当前 session 就结束了
  - 后续用户再次进入时，创建的是新的 session，而不是恢复旧 session
  - 第一阶段不考虑跨 session 历史记忆自动回灌
  - 若未来支持同用户长期记忆，也应建模为“向新 session 注入记忆”，而不是“重建旧 session”

### Finding 12: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§2.1` / `§2.4` / `§9.1` / `§9.6` / `§10` 中去掉“idle 后恢复同一 session”的表述
  - 明确 idle 超时意味着当前 session 结束，后续进入创建新 session / 新 workflow
  - 明确第一阶段不做跨 session 历史或 `sharedState` 自动回灌
- 这样处理后：
  - `assistantReleaseVersion` 的绑定边界重新清晰
  - 生命周期语义不再被误读为“重建同一个 session”

### Finding 13: 建议方向

- 这条的核心不是把所有 Signal 异常分支都穷举成实现细节，而是把最关键的状态机校验写成文档不变量。
- 我的建议是分三类处理：
  - `HUMAN_RESUME`
    - 必须校验目标 `runId` 属于当前 session
    - 必须校验目标 playbook 当前处于 `WAITING`
    - 必须校验 `waitingReason` 对应 `HUMAN_TASK`
    - 若目标 run 已不在等待态，则按幂等/过期请求处理，不重复恢复
  - `EXTERNAL_CALLBACK`
    - 必须校验目标 `runId` 属于当前 session
    - 必须校验目标 playbook 当前处于 `WAITING`
    - 必须校验 `waitingReason` 对应 `EXTERNAL_INTERACTION`
    - 若目标 run 已不在等待态，则按幂等/过期请求处理，不重复恢复
  - `SESSION_HANDOFF_END`
    - 应定义为幂等 Signal
    - 若当前已不在 handoff，则忽略或记录幂等命中，不重复写事件
- 当前倾向：
  - 文档应明确“校验失败 / 过期 / 重复”的处理语义至少到以下粒度：
    - 拒绝恢复
    - 不重复推进状态机
    - 可选记录诊断事件或日志
  - 但不必在第一阶段把每一种错误都扩展成独立事件类型

### Finding 13: 设计文档已对齐

- 已直接修改 `docs/develop_record/session_workflow_redesign.md`
- 本次修复内容：
  - 在 `§2.5 Temporal 消息入口` 中新增 Signal 校验与幂等原则
  - 在 `§9.4 Session Human Handoff` 中将 `SESSION_HANDOFF_END` 明确为幂等 Signal
  - 在 `§9.5 人工恢复与外部回调` 中补充恢复类 Signal 的运行时校验条件与“无效 / 过期 / 重复请求不推进状态机”的语义
- 这样处理后：
  - Signal 路径的关键状态机不变量已经明确
  - 第一阶段仍避免把每一种失败扩展成复杂事件体系

### Finding 14: 结合现状后的收敛结论

- 这条在前面关于 session 生命周期的修正后，已经不再是当前设计文档的活跃缺陷。
- 当前应保留的结论是：
  - 第一阶段不做跨 session 恢复
  - 未来若支持同用户长期记忆，应定义“向新 session 注入记忆”的模型
  - 不应回到“从 DB 恢复旧 session / 重建旧 session”的语义
