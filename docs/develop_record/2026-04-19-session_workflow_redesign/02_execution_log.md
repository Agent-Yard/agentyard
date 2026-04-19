# Session Workflow 重构执行记录

## 2026-04-19

### 记录 001：建立执行框架并完成首轮全局盘点

- 动作：
  - 通读 `docs/architecture/session_workflow_redesign.md`
  - 盘点以下模块与文档：
    - `apps/api`
    - `apps/worker`
    - `apps/agent-runtime`
    - `packages/contracts`
    - `packages/contracts-jvm`
    - `apps/web`
    - `docs/architecture/code-framework.md`
    - `docs/architecture/external-interaction-integration.md`
    - `docs/todo/runtime_todo.md`
    - `docs/todo/sse_plan.md`
  - 建立 `docs/doing/00~04` 执行文件
- 关键发现：
  - 现有系统核心仍是 `assistant-run + orchestration graph + LangGraph + workflow projection`
  - 与设计要求的 `session workflow + owner + playbook + session event` 存在根模型冲突
  - 本次不是局部升级，而是运行时架构重建
- 决策依据：
  - 设计文档 §1~§10
  - 用户明确要求：冲突处按设计重做，不做兼容
- 影响范围：
  - 全运行时主链路
  - catalog 配置模型
  - shared contracts
  - web runtime / assistant / agent / orchestration 相关页面
- 当前状态：
  - 阶段 A 已完成
  - 尚未开始代码结构改造
- 下一步：
  - 进入 contracts/catalog 根模型替换设计与落地
  - 先建立新的共享类型与目录配置对象，再推进 worker/API/runtime 实现

### 记录 002：进入阶段 B，开始共享模型与 catalog 重构

- 动作：
  - 将阶段状态切换为“阶段 A 完成，阶段 B 进行中”
  - 准备先改 `packages/contracts*` 与 `apps/api` catalog 模型
- 原因：
  - worker、API runtime、web 都依赖共享模型和 release snapshot；不先换根契约，后续实现会反复返工
- 预期影响范围：
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `apps/api` catalog
  - `apps/web` catalog 类型与配置页
- 下一步：
  - 建立新的 assistant/agent/playbook/session contracts
  - 在 catalog 中落地新的配置字段和 release snapshot

### 记录 003：落地 session/playbook 根契约，并把 catalog 提升到 owner/session 配置语义

- 已改内容：
  - 新增 JVM 共享契约：
    - `packages/contracts-jvm/src/main/java/com/lynxus/contracts/session/SessionContracts.java`
    - `packages/contracts-jvm/src/main/java/com/lynxus/contracts/session/SessionWorkflow.java`
    - `packages/contracts-jvm/src/main/java/com/lynxus/contracts/session/PlaybookWorkflow.java`
  - 扩展 TS 共享契约：
    - `packages/contracts/src/index.ts`
    - 新增 `SessionSnapshot / SessionEvent / PlaybookRun / AgentTurnRequestV2 / AgentTurnResultV2` 等类型
  - 扩展 catalog assistant/agent 模型：
    - assistant 新增 `primaryAgentId / ownerPolicy / sessionPolicy / replyPolicy / playbookPolicy`
    - agent 新增 `canOwnSession / allowedActions / switchableOwnerAgentIds / playbookIds`
    - release snapshot 同步固化上述字段
  - 调整 `CatalogService`：
    - 新增 owner/session/policy 默认化逻辑
    - 发布前新增 `primaryAgentId` 与 `canOwnSession` 校验
    - 删除主 owner agent 时自动清空 assistant 的 `primaryAgentId`
  - 调整 web catalog 类型与表单状态：
    - `apps/web/src/types/catalog.types.ts`
    - `apps/web/src/pages/AssistantPage.vue`
    - `apps/web/src/pages/AgentPage.vue`
- 原因：
  - worker/runtime 新链路要以 owner 与 session policy 为输入；不先把 catalog 和共享契约升级，后续实现无法稳定展开
- 影响范围：
  - contracts
  - catalog service
  - assistant/agent 配置页
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - 阶段 B 已完成第一批基础改造，但尚未完成：
    - playbook catalog 配置对象
    - worker/runtime/API 新 session 主链路切换
    - web 对新 owner/session policy 的完整可编辑 UI
- 下一步：
  - 继续完成阶段 B 收口，或直接进入阶段 C 的 worker/runtime 主链路重构；进入前需先在 `docs/doing` 明确下一批主干切入点

### 记录 004：启动阶段 C，切入 worker/runtime 新主协议

- 决策：
  - 不等待 catalog 所有配套 UI 完整收口，先推进 worker 与 agent-runtime 主协议骨架
- 原因：
  - 当前主路径瓶颈已经从“类型缺失”转为“旧 workflow 协议仍是唯一执行通道”
  - 若继续停留在 catalog/web 层，无法真正推进 session workflow 重构
- 当前切入点：
  - `apps/worker`：新增 `session workflow / playbook workflow / agent turn activity`
  - `apps/agent-runtime`：新增 agent turn 入口契约
- 下一步：
  - 先搭新协议骨架并完成编译
  - 再决定是否继续把旧入口切换到新协议

### 记录 005：完成 worker/runtime 新主协议第一批骨架

- 已改内容：
  - `apps/worker` 新增：
    - `worker/session/AgentTurnActivities.java`
    - `worker/session/AgentTurnActivitiesImpl.java`
    - `worker/session/SessionWorkflowImpl.java`
    - `worker/session/PlaybookWorkflowImpl.java`
    - `worker/runtime/SessionAgentRuntimeGateway.java`
  - `TemporalWorkerConfiguration` 已注册新的：
    - `SessionWorkflow`
    - `PlaybookWorkflow`
    - `AgentTurnActivities`
  - `apps/agent-runtime/lynxus_agent_runtime/main.py`
    - 新增 `/agent-turns/execute`
    - 新增 V2 agent-turn / session / playbook 请求响应模型
- 当前实现状态：
  - 已有可编译的新 Temporal 主协议骨架
  - 还未完成：
    - session event 持久化
    - playbook child workflow 终态回传父 workflow
    - worker 对 `AgentDecision` 的完整运行时校验与降级
    - API 切到新 session workflow 入口
    - Python runtime 用真实 prompt/tool 流程替换当前占位决策
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava` 通过
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py -q` 通过
- 结构判断：
  - 新协议主骨架已存在，后续可以开始从 API/runtime persistence 侧把旧入口切向新链路
- 下一步：
  - 进入 API runtime 新领域模型与持久化改造
  - 用 `session + session_event + playbook_run` 替换旧 `task/workflow/resume` 投影主线

### 记录 006：启动阶段 D，开始 API/runtime 主路径替换

- 决策：
  - 直接新建 session 领域服务与持久化结构，不在旧 `RuntimeService` 上继续叠加新语义
- 原因：
  - 旧 `RuntimeService` 已强耦合 `task/workflow/resume/external_interaction` 语义，继续演化会形成新的结构债务
- 当前切入点：
  - `apps/api` 新建 session 领域 DTO、repository、service、controller
  - DB migration 新增 `session_runtime_session / session_runtime_event / session_runtime_playbook_run`
  - 新 Temporal gateway 对接 `SessionWorkflow`
- 下一步：
  - 先完成 API 领域模型和可用入口
  - 再把前端 runtime 主视图切到新查询对象

### 记录 007：新增硬约束，不允许遗留旧逻辑代码

- 用户新增要求：
  - “不要留下旧逻辑代码”
- 影响：
  - 不能以“新旧平行实现”作为最终状态
  - 旧 `AssistantRunWorkflow`、旧 runtime API、旧 `LangGraph`、旧 orchestration runtime 主链路都必须进入删除范围
- 执行调整：
  - 后续优先级从“补齐新链路”调整为“新链路可接管后立即删除旧链路”
  - 不再接受长期保留旧 controller / service / workflow 作为旁路

### 记录 008：worker 旧执行链已进入删除，主攻点切换到 API/web 旧入口清除

- 已发生变化：
  - `apps/worker` 旧 `AssistantRun` 相关实现与测试已进入删除范围
  - `TemporalWorkerConfiguration` 仅保留新 `SessionWorkflow / PlaybookWorkflow` 与知识库 workflow 注册
- 当前判断：
  - worker 侧旧执行链不再是继续投入时间的主风险点
  - 当前最大遗留点转为：
    - `apps/api` 旧 `RuntimeService + RuntimeController + RuntimeRepository`
    - `apps/web` 旧 `orchestration / workflow / workflow polling / runtime actions`
    - `apps/agent-runtime` 旧 `LangGraph + AssistantRunSnapshot` 主体逻辑
- 当前执行策略：
  - 先切 `apps/web` 到 `session-runtime` 视图，并删除旧路由/页面/组合式逻辑
  - 再删除 `apps/api` 旧 runtime 包与相关测试，统一到 `platform.session`
  - 收口后继续清除 catalog 中的 orchestration 旧模型与 Python runtime 旧 graph 逻辑
- 原因：
  - 如果先保留旧 console/API 入口，即使 worker 已切新，也仍然是新旧并存
  - 用户已明确禁止保留旧逻辑代码，因此必须从对外入口开始断旧链
- 下一步：
  - 更新 web 状态模型、API client、路由与 runtime 页面
  - 删除 `workflow` 与 `orchestration` 页面入口及其依赖

### 记录 009：API/web 已切到 session-runtime，旧 runtime 入口与前端旧页面已删除

- 已改内容：
  - `apps/web`
    - 删除旧 `types/runtime.types.ts`、`types/orchestration.types.ts`
    - 删除旧 `WorkflowPage.vue`、`OrchestrationPage.vue`、`TaskLaunchPage.vue`
    - 删除旧 `useWorkflowPolling`、`runtimePresentation`、`workflowFailure` 与对应测试/graph 工具文件
    - `navigation`、`router`、`ConsolePage`、`useAppState`、`useRuntimeActions` 已统一切到 `session-runtime`
    - `RuntimeConversationPage.vue` 改为 `session event / owner / playbook run / sharedState` 视图
  - `apps/api`
    - 删除整个旧 `platform.runtime` 包及其测试
    - 新增 `platform.session.TemporalSessionConfiguration`
    - `ApiAuthorizationTest`、`ApiLogContextFilterTest`、`ApiLogContextFilter` 已切到 `/api/session-runtime/...`
- 验证：
  - `pnpm --dir apps/web lint` 通过
  - `./gradlew :apps:api:test --tests com.lynxus.platform.auth.ApiAuthorizationTest --tests com.lynxus.platform.shared.logging.ApiLogContextFilterTest :apps:api:compileJava` 通过
- 当前结论：
  - 旧 runtime API/controller/service/repository/web 页面不再留在主工程中
  - 剩余主要旧逻辑集中在：
    - catalog orchestration 模型与相关接口/持久化
    - `apps/agent-runtime` 中 `AssistantRunSnapshot + GraphSnapshot + LangGraph` 主体实现
- 下一步：
  - 删除 catalog orchestration 及相关 DTO/repository/controller/web 合同残留
  - 重写 Python runtime，移除 graph 语义并仅保留单 agent 单轮推理入口

### 记录 010：进入共享契约与 orphan reference 清场阶段

- 原因：
  - 新链路已经接管 API/web/runtime 主入口，但仓内仍残留一批旧 `task/workflow/resume/orchestration` 契约、OpenAPI 定义、测试断言与展示文案
  - 用户新增硬约束要求“不留下旧逻辑代码”，因此这些残留不能作为死代码继续保留
- 当前目标：
  - 删除 `packages/contracts` 中不再被主工程使用的旧 runtime/workflow 类型
  - 删除 OpenAPI 中 `/tasks`、`/workflows` 及其旧 schema
  - 删除 catalog 测试与前端 reference 展示中的 orchestration 残留
- 预期影响范围：
  - `packages/contracts`
  - `packages/contracts/openapi`
  - `apps/api` catalog tests
  - `apps/web` reference presentation
- 完成标准：
  - 搜索主工程时，除执行文档中的差异记录外，不再出现旧 graph/runtime 主模型的有效代码引用

### 记录 011：完成主工程旧 runtime/orchestration 残留清场，并修正 assistant 发布更新语义

- 已改内容：
  - `packages/contracts/src/index.ts`
    - 删除旧 `task/workflow/resume/conversation` 运行时类型，仅保留当前 catalog/knowledge/session 契约
  - `packages/contracts/openapi/control-plane.yaml`
    - 删除旧 `/tasks`、`/runtime/*`、`/workflows/*` 契约定义
    - 新增 `/session-runtime/sessions` 相关 schema
    - 删除旧 conversation/external-interaction/runtime-workflow schema
  - `apps/api`
    - `CatalogServiceTest`、`CatalogReferenceProjectionTest` 已移除 orchestration 旧断言并切到新发布语义
    - `CatalogService.updateAssistant(...)` 修正为：
      - 省略 runtime 配置字段时继承现有 assistant 配置，而不是隐式清空
      - 以 `effectiveStatus` 而非仅 `request.status()` 决定发布校验与 release 生成
      - 在发布校验通过前不提前落库状态
  - `apps/web`
    - `referencePresentation.ts` 删除 orchestration relation label 残留
  - `docs/architecture`
    - `external-interaction-integration.md` 重写为 session/playbook 语义，明确旧 `ExternalInteractionTask` 模型不再作为当前架构基准
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.catalog.CatalogServiceTest --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest` 通过
  - `pnpm --dir apps/web lint` 通过
  - 仓内搜索 `AssistantRunWorkflow / GraphSnapshot / DecisionType / ResumeIntervention / WorkflowInstance / ConversationSession / orchestration runtime` 等旧关键字时，主工程有效代码已清空；剩余提及仅限执行文档与必要的“旧模型已废弃”说明
- 当前结论：
  - 主工程已不存在旧 runtime/workflow/orchestration 的有效实现与调用入口
  - 当前剩余主任务不再是“删旧链”，而是继续把 session workflow 设计文档中尚未落满的主链能力做完整
- 下一步：
  - 继续补齐 session workflow / playbook / agent-runtime 的真实业务主链，而不是骨架实现
  - 回到设计文档逐节核查尚未落地的状态机、playbook、handoff、callback/resume 语义

### 记录 029：按逐句审计结果重新收敛实施边界

- 背景：
  - 逐句核查 `docs/architecture/session_workflow_redesign.md` 后，发现 playbook 执行层、worker 主动持久化、retry/timeout 与 runtime prompt context 仍未达到最终设计要求
  - 用户进一步确认了四个关键实现决策：
    - `STEP` 由 worker 通过受限 sandbox 执行
    - `TOOL_TASK` 统一在 runtime 执行，不在 worker 直接执行业务 tool
    - worker 与 api 共用核心库，核心会话数据由 worker 主动落库
    - `sharedState` 最终按扁平 KV 处理
- 决策依据：
  - 设计文档 §3.2 / §3.3 / §5 / §9
  - 用户最新明确指令
- 对主计划的影响：
  - 本次最终验收保持 reopened
  - 后续阶段顺序调整为：
    1. 先完成 shared core DB 与 worker 主动持久化
    2. 再补 `STEP` sandbox 与 `TOOL_TASK -> runtime` 执行分层
    3. 再收口 input schema、retry/timeout、runtime prompt context
- 下一步：
  - 先回写 `docs/doing`
  - 再直接进入 shared core DB + worker persistence 实施

### 记录 030：完成 worker 主动持久化与 shared core DB 替换第一阶段

- 已改内容：
  - `packages/contracts-jvm`
    - `SessionStartRequest` 新增 `scenarioId / sessionTitle`
    - 删除 `SessionWorkflow.currentProjection()` 查询口
  - `apps/worker`
    - 新增 `SessionPersistenceActivities`、`JdbcSessionProjectionRepository`、`SessionPersistenceActivitiesImpl`
    - `SessionWorkflowImpl` 改为在 workflow 内直接 upsert session、append event、save playbook run
    - `TemporalWorkerConfiguration` 已注册新的持久化 activity
  - `apps/api`
    - `SessionRuntimeService` 不再依赖 `currentProjection()` 同步 worker 内存投影
    - create/send/resume/callback/handoff reply 后统一改为“发命令 -> 读库”
    - 关闭 workflow 时仅保留 `ENDED` 收口补偿
  - `infra`
    - `postgres-bootstrap` 和 dev compose 默认数据库名已改为 `lynxus_core`
    - worker dev compose 已补 datasource 指向 `lynxus_core`
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.session.SessionRuntimeServiceTest :apps:api:compileJava` 通过
- 当前结论：
  - “API 拉 `currentProjection()` 同步控制面”的旧思路已退出主路径
  - worker 已成为 `session / session_event / playbook_run` 的主动持久化执行者
  - shared core DB 的默认口径已切到 `lynxus_core`
- 下一步：
  - 进入 playbook 执行层改造
  - 先补 `STEP -> sandbox` 和 `TOOL_TASK -> runtime capability`

### 记录 031：补齐 playbook 真实执行层与 `RUN_PLAYBOOK` 输入校验

- 已改内容：
  - `apps/worker`
    - `SessionWorkflowImpl` 已对 `RUN_PLAYBOOK.playbookInput` 执行 `inputSchema` 校验
    - `PlaybookStartRequest` 已冻结 owner agent 快照，供 child workflow 的固定工具执行使用
    - `PlaybookNodeActivitiesImpl` 不再是 `cancel/fail/statePatch` 占位：
      - `STEP` 通过 sandbox 执行 `config.runtime + config.code`
      - `TOOL_TASK` 改为经 worker 调 runtime 新入口执行固定 tool task
    - 新增 `SandboxGateway`，并把 worker 配置与 dev/local infra 接到 sandbox
  - `apps/agent-runtime`
    - 新增 `/playbook-tool-tasks/execute`
    - `tooling.py` 已支持固定 `toolId + operation` 的 direct execution、参数模板渲染、statePatch/routeKey 映射
  - `infra`
    - dev/local compose 已补 sandbox 服务
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:compileJava` 通过
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_decisioning_loop.py -q` 通过
- 当前结论：
  - playbook 的 `STEP / TOOL_TASK` 已从占位实现推进到真实执行链
  - `RUN_PLAYBOOK` 缺失 `inputSchema` 校验的问题已关闭
- 下一步：
  - 继续补 playbook timeout/retry policy 真正生效
  - 收口 runtime prompt context 的 memory window / 最小上下文暴露

### 记录 032：完成 retry/timeout 与 runtime prompt context 收口，并关闭最终验收

- 已改内容：
  - `apps/worker`
    - `SessionWorkflowImpl` 的 agent turn activity 已启用实际 retry policy
    - `PlaybookWorkflowImpl` 已按 playbook `timeoutPolicy / retryPolicy` 动态生成 Temporal activity options
    - 新增 `TemporalPolicySupport` 统一解析 timeout/retry 字符串策略
  - `apps/agent-runtime`
    - `PromptRuntimeMessages` 已按 `memoryWindowSize` 裁剪 recent events
    - `sharedState` 暴露改为“预算内 slice + truncation metadata + 按需 get_shared_state tool 再读”
    - 新增 playbook tool task 测试与 prompt memory window 测试
  - `packages/contracts`
    - 删除 TS 中遗留的 `SessionProjection` 死类型
    - 补齐 `PlaybookStartRequest.ownerAgent` 与 playbook tool task 对应类型
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.session.SessionRuntimeServiceTest --tests com.lynxus.platform.catalog.CatalogServiceTest --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest :apps:api:compileJava` 通过
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_decisioning_loop.py apps/agent-runtime/tests/test_playbook_tool_task.py -q` 通过
  - `pnpm --dir apps/web lint` 通过
  - `rg -n "AssistantRunWorkflow|GraphSnapshot|ResumeIntervention|ConversationSession|LangGraph|AssistantOrchestration|currentProjection\\(" apps packages -g '!**/build/**' -S` 无命中
- 当前结论：
  - reopen 阶段识别的所有结构性缺口已关闭
  - `docs/doing` 重新回到 final 状态

### 记录 033：补齐 `.env` sample 对新增配置的同步

- 触发原因：
  - 最终收口后复核发现 `.env.example` / `.env.dev.example` 仍残留旧 `lynxus_api` 数据库名，且未补入 sandbox 相关 sample 配置
- 已改内容：
  - `.env.example`
    - `SPRING_DATASOURCE_URL` 改为 `lynxus_core`
    - 新增 `LYNXUS_SANDBOX_BASE_URL`
    - 新增 `LYNXUS_IMAGE_SANDBOX`
  - `.env.dev.example`
    - `SPRING_DATASOURCE_URL` 改为 `lynxus_core`
    - 新增 `LYNXUS_SANDBOX_BASE_URL`
    - 新增 `LYNXUS_IMAGE_SANDBOX`
- 当前结论：
  - sample 配置文件已与当前实现口径重新对齐

### 记录 034：清理文档中的旧数据库名口径

- 触发原因：
  - 复查发现仓内对外说明文档仍有 `lynxus_api` 旧口径，和当前 `lynxus_core` 实现不一致
- 已改内容：
  - `README.md`
  - `docs/architecture/local-development.md`
  - `infra/dev/docker-compose.yml`
  - `infra/local/docker-compose.yml`
  - `infra/local/postgres-bootstrap/init-databases.sh`
- 当前结论：
  - 仓内剩余 `lynxus_api` 命中仅保留在 `docs/doing` 的历史执行记录中，不再出现在当前实现、环境变量名或运行文档里

### 记录 035：修复 worker 启动时的 Temporal activity interface 注册失败

- 触发原因：
  - worker 启动时报错：`Class doesn't implement any non empty interface annotated with @ActivityInterface`
  - 根因是新加的 `SessionPersistenceActivities` 缺少 `@ActivityInterface`；`PlaybookNodeActivities` 也存在同类遗漏
- 已改内容：
  - `apps/worker/src/main/java/com/lynxus/worker/session/SessionPersistenceActivities.java`
  - `apps/worker/src/main/java/com/lynxus/worker/session/PlaybookNodeActivities.java`
- 验证：
  - `./gradlew :apps:worker:compileJava` 通过
- 当前结论：
  - Temporal activity 注册口径已恢复一致，worker 可继续启动

### 记录 012：为 session workflow 骨架补上第一层状态机 guardrail

- 已改内容：
  - `apps/worker/src/main/java/com/lynxus/worker/session/SessionWorkflowImpl.java`
    - 为 `submitUserMessage` 增加 `playbook active / human handoff active / draining` 拒绝条件
    - 对 `SWITCH_OWNER / RUN_PLAYBOOK / SESSION_HUMAN_HANDOFF` 增加 owner/action/playbook 级校验，不再无条件接受 agent-runtime 决策
    - `humanResume / externalCallback / endHumanHandoff` 现在会清理对应运行态并标记 `pendingOwnerReevaluation`
    - playbook run id 改为包含 `playbookId`，便于活跃 playbook 摘要与后续观测
- 原因：
  - 删除旧链后，新的 session workflow 仍然过于骨架化；若不先补最基本的 guardrail，后续继续填事件与 child workflow 时会把非法状态直接带进主链
- 验证：
  - `./gradlew :apps:worker:compileJava :packages:contracts-jvm:compileJava` 通过
  - `./gradlew :apps:api:test --tests com.lynxus.platform.catalog.CatalogServiceTest --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - session workflow 仍未达到设计文档完整状态，但已经不再是“完全无校验的 happy-path skeleton”
- 下一步：
  - 继续补齐 playbook child workflow 启动/完成回传、session event 产出、resume/callback 与 reevaluation 的真实闭环

### 记录 013：进入 session projection 闭环阶段

- 当前判断：
  - 现阶段最大缺口不是旧链残留，而是 worker 尚未把 owner/playbook/handoff 的运行结果投影成 `session event + playbook run`
  - API 目前仅持久化 `USER_MESSAGE`，无法形成设计文档要求的 append-only session timeline
- 当前阶段目标：
  - 在 worker 内维护权威 `session projection`
  - 通过 Temporal query 暴露 `snapshot + events + playbookRuns`
  - 由 API 在用户消息/查询路径同步 projection 到控制面持久化
- 设计依据：
  - `docs/architecture/session_workflow_redesign.md` §3.1, §3.3, §5, §6, §7, §9

### 记录 014：打通 worker session projection 与 API 同步入口

- 已改内容：
  - `packages/contracts-jvm`
    - `SessionContracts` 新增 `SessionProjection`
    - `SessionWorkflow` 新增 `currentProjection()` query
  - `apps/worker`
    - `SessionWorkflowImpl`
      - 维护权威 `events + playbookRuns + snapshot`
      - 用户消息由 workflow 自己写 `USER_MESSAGE`
      - owner reply / owner switch / playbook start / playbook waiting / playbook resumed / handoff start/end / turn failed / decision rejected 等事件开始进入 workflow projection
      - `RUN_PLAYBOOK` 现在会真实启动 `PlaybookWorkflow` child workflow，并在 workflow 内保存活跃 playbook promise
    - `PlaybookWorkflowImpl` 继续作为最小 child workflow 骨架，但已纳入 session projection 管理路径
  - `apps/api`
    - `SessionWorkflowGateway` 新增：
      - `currentProjection`
      - `humanResume`
      - `externalCallback`
      - `endHumanHandoff`
    - `SessionRuntimeService`
      - 不再在 API 侧手写 `USER_MESSAGE`
      - 改为在消息提交后查询 worker projection，并同步 `session_runtime_session / session_runtime_event / session_runtime_playbook_run`
      - `listSessions` / `getSessionDetail` 也会尽量先同步活跃 workflow projection
    - `SessionRuntimeController` 新增：
      - `/api/session-runtime/sessions/{sessionId}/human-resume`
      - `/api/session-runtime/sessions/{sessionId}/external-callback`
      - `/api/session-runtime/sessions/{sessionId}/handoff/end`
    - `JdbcSessionRuntimeRepository.appendEvent(...)` 改为按 `event_id` 幂等插入
  - `packages/contracts`
    - TS contracts 与 OpenAPI 补上 `SessionProjection`、`HumanResumeRequest`、`ExternalCallbackRequest` 与新增 session-runtime 入口
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:compileJava` 通过
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.catalog.CatalogServiceTest --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest :apps:api:compileJava` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - session-runtime 已不再只是“session snapshot + API 侧猜测事件”
  - worker 开始成为 owner/playbook 事件流的权威来源，API 退回到 projection persistence 角色
- 剩余缺口：
  - playbook waiting/resume/completed 仍是最小骨架，尚未覆盖真实节点遍历与完整状态流转
  - owner reevaluation 仍未完整达到设计文档所述闭环
  - agent-runtime 仍是占位式决策，不是最终真实推理循环

### 记录 015：进入 playbook 状态机与 reevaluation 收口阶段

- 当前判断：
  - 新 session 主链已经形成，但 `PlaybookWorkflow` 与 `agent-runtime` 仍停留在“能通骨架”的级别，距离设计文档的最终状态还有明显差距
  - 若不先把 playbook child workflow 做实，后续的 owner reevaluation、resume/callback 命中校验、handoff 语义都无法真正闭环
- 当前阶段目标：
  - 先把 `PlaybookWorkflow` 补成真实节点状态机，至少落地：
    - `STEP / TOOL_TASK / HUMAN_TASK / EXTERNAL_INTERACTION / END`
    - `RUNNING / WAITING / SUCCEEDED / FAILED / CANCELLED`
    - `waitingReason = human_task:<nodeKey> / external_interaction:<nodeKey>`
  - 再同步收口 `SessionWorkflow`：
    - `PLAYBOOK_WAITING / RESUMED / COMPLETED` 事件 payload
    - reevaluation 的一次性消费语义
    - `humanResume / externalCallback / handoff end` 的命中校验与幂等
  - 最后把 `apps/agent-runtime` 从命令式占位规则推进到真实单轮推理输入/输出结构
- 设计依据：
  - `docs/architecture/session_workflow_redesign.md` §3.2, §3.3, §5.1, §5.2, §6, §9.3, §9.5
- 下一步：
  - 先改 worker contracts/implementation，再回补 runtime 与验证

### 记录 016：补齐 playbook child workflow、handoff 人工回复与 runtime prompt 主链

- 已改内容：
  - `packages/contracts-jvm`
    - 新增 `PlaybookProgressType / PlaybookProgressUpdate / HumanOperatorReplySignal`
    - `SessionWorkflow` 新增内部 Signal：
      - `syncPlaybookProgress(...)`
      - `humanOperatorReply(...)`
  - `apps/worker`
    - 新增：
      - `PlaybookNodeActivities.java`
      - `PlaybookNodeActivitiesImpl.java`
      - `PlaybookDefinition.java`
    - `PlaybookWorkflowImpl`
      - 不再是“单次 resume 即成功”的骨架
      - 已按 `STEP / TOOL_TASK / HUMAN_TASK / EXTERNAL_INTERACTION / END` 执行节点
      - `HUMAN_TASK / EXTERNAL_INTERACTION` 会进入 `WAITING`，并通过 `syncPlaybookProgress(...)` 回推父 session workflow
      - `END` 节点会产出 `SUCCEEDED / FAILED / CANCELLED` 终态 run
    - `SessionWorkflowImpl`
      - 修正消息准入：playbook 活跃期间允许继续接收用户消息；handoff 期间接受用户消息但不触发 agent turn
      - 新增 `syncPlaybookProgress(...)`，把 child workflow 的等待/恢复状态写入 `PLAYBOOK_WAITING / PLAYBOOK_RESUMED`
      - `humanResume / externalCallback` 现在按 `WAITING + waitingReason` 命中校验后再恢复
      - 新增 `humanOperatorReply(...)`，handoff 期间可写 `HUMAN_OPERATOR_REPLY`
      - `SESSION_HUMAN_HANDOFF` 改为符合设计文档的幂等短路语义
      - `copySnapshot(...)` 不再无条件重置 idle deadline，并补上 `idle timer / maxWorkflowAge / maxWorkflowHistoryEvents / draining` 的实际行为
  - `apps/api`
    - `SessionWorkflowGateway / SessionRuntimeService / SessionRuntimeController / SessionRuntimeDtos`
      - 新增 `human-reply` 入口，允许 handoff 期间人工回复写入当前 session event 流
  - `apps/agent-runtime`
    - 删除 `langgraph` 依赖
    - 拆分为：
      - `models.py`
      - `prompting.py`
      - `decisioning.py`
      - `main.py`
    - runtime 现在会先构建 `PromptInstruction + PromptRuntimeMessages + PromptCapabilities`
    - 若配置了 OpenAI-compatible provider，则走 `/chat/completions` JSON 决策路径；否则回落到 deterministic fallback policy
    - 新增 `test_prompting.py`
  - `packages/contracts`
    - 新增 `HumanOperatorReplyRequest`
  - `packages/contracts/openapi`
    - 新增 `/session-runtime/sessions/{sessionId}/human-reply`
    - 新增 `HumanOperatorReplyRequest` schema
  - `apps/web`
    - `api.ts` 与 session types 已补上 `human-reply / human-resume / external-callback / handoff/end` 调用契约
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:compileJava` 通过
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py -q` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - `PlaybookWorkflow`、`SessionWorkflow`、handoff 回复入口与 `agent-runtime` prompt 主链已经不再是占位骨架
  - 当前新的最大缺口转移为：catalog 尚未落地独立 playbook 配置对象，`SessionRuntimeService` 仍拿不到真实 `playbook definitions`
- 下一步：
  - 进入 catalog/playbook 配置模型落地
  - 打通 `assistant release -> session start request -> available playbooks`

### 记录 017：把 playbook 定义接入 catalog 与 session start request

- 已改内容：
  - `apps/api` catalog
    - 新增 playbook DTO 与请求对象：
      - `PlaybookDto`
      - `PlaybookExecutionPolicyDto`
      - `PlaybookNodeDto`
      - `PlaybookEdgeDto`
      - `CreatePlaybookRequest`
      - `UpdatePlaybookRequest`
    - `CatalogService`
      - 新增内存态 `playbooks`
      - 新增 `list/get/create/update/deletePlaybook`
      - assistant view 与 assistant release 现在会携带 `playbooks`
      - agent create/update 现在校验 `playbookIds` 必须引用同 assistant 下的真实 playbook
      - assistant release 创建时会把 playbook 定义冻结进当前 release
    - `CatalogController`
      - 新增 `/api/playbooks` CRUD 入口
    - `CatalogRepository / JdbcCatalogRepository / InMemoryCatalogRepository`
      - `CatalogSnapshot` 新增 `playbooks`
      - 新增 `catalog_playbook` 持久化
    - DB migration
      - 新增 `V7__catalog_playbook.sql`
  - `apps/api` session runtime
    - `SessionRuntimeService.buildStartRequest(...)` 不再传空 `playbooks`
    - 当前 assistant release 中冻结的 playbook 定义已会转换成 `SessionContracts.PlaybookConfig` 注入 session workflow
  - `apps/web`
    - catalog types 增加 `Playbook / PlaybookNode / PlaybookEdge / PlaybookExecutionPolicy`
    - `Assistant` / `AssistantRelease` 已包含 `playbooks`
    - `AgentPage` 现在从当前 assistant 的真实 `playbooks` 列表里选择 `playbookIds`，不再是盲填
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.catalog.CatalogServiceTest --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest :apps:api:compileJava` 通过
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py -q` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - `RUN_PLAYBOOK` 主链已经不再缺少 catalog 来源的 `PlaybookConfig`
  - assistant release 现在可以把 playbook 定义冻结进 runtime 输入，session workflow 能收到真实 `availablePlaybooks`
- 剩余缺口：
  - web 还没有独立的 playbook 管理页，当前只完成了类型消费与 agent 侧真实选择
  - OpenAPI / TS contracts 还没有完整暴露 catalog playbook CRUD
  - object reference / deletion preview 还未把 playbook 纳入一等治理对象

### 记录 018：固化“阶段完成后自动自检并进入下一阶段”的执行规则

- 用户新增要求：
  - 每次完成一个阶段任务后，不等待人工催促，必须自行：
    - 自检
    - 更新 `docs/doing`
    - 进入下一阶段
  - 持续重复，直到 `docs/doing` 达到最终状态
- 已落地动作：
  - 已将该要求写入 `00_master_plan.md` 的执行约束与阶段闭环规则
- 对后续执行的影响：
  - 后续不再允许把“某阶段已完成”当作停顿点
  - 每次阶段完成都必须伴随：
    - 文档同步
    - 编译/测试
    - 结构检查
    - 下一阶段切换
- 当前下一阶段：
  - 补齐 playbook 的治理与配置面收口：
    - OpenAPI / TS contracts
    - web playbook 管理页
    - object reference / deletion preview

### 记录 019：完成 playbook 治理收口，并在最终核查中识别出 API 串行锁缺口

- 已改内容：
  - `apps/api`
    - `CatalogService` 已把 `PLAYBOOK` 纳入：
      - `objectReferences(...)`
      - `deletionPreview(...)`
      - 统一删除阻断消息
    - `ObjectReferenceAnalyzer` 的 playbook 引用分析已正式接入 service 主线
  - `apps/api` tests
    - `CatalogServiceTest` 新增 playbook 引用分析、删除阻断、删除预览断言
    - fixture 现已包含真实 playbook 与 agent-playbook 引用关系
  - `packages/contracts` / `packages/contracts/openapi` / `apps/web`
    - `ReferenceObjectType` 已加入 `PLAYBOOK`
    - Playbook 独立管理页已接入 `ObjectReferencePanel`
    - playbook 删除已切到统一 deletion preview 流
    - reference presentation 已补上 playbook relation label 与 object label
- 验证：
  - `./gradlew :apps:api:test --tests com.lynxus.platform.catalog.CatalogServiceTest --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest :apps:api:compileJava` 通过
  - `pnpm --dir apps/web lint` 通过
- 设计核查新发现：
  - 最终逐节对照设计文档时，发现 API 侧还缺少 `sessionId / 对话绑定键` 串行投递锁
  - 这不是文档性差异，而是会影响单活 session 与并发投递语义的真实实现缺口
- 下一步：
  - 先补 `SessionRuntimeService` 的串行投递锁与 active-session 复用语义
  - 然后执行最终全链路验收与 `04_final_checklist.md` 闭环

### 记录 020：完成 API 串行锁与 ended-session 收口，并闭合最终验收

- 已改内容：
  - `apps/api`
    - 新增 `SessionDispatchLockService`
      - 以 `conversation(customerId + assistantId)` 与 `sessionId` 两级锁串行化创建/投递路径
    - `SessionWorkflowGateway`
      - 新增 `isWorkflowOpen(...)`
      - 通过 Temporal describe 能力识别 workflow 是否已结束
    - `SessionRuntimeService`
      - `createSession(...)` 现在会先按对话键加锁，再复用同对话下的活跃 session
      - 若旧 workflow 已结束，则会先把旧 session 持久化为 `ENDED`，再创建新 session
      - `sendMessage(...)` 现在按 `sessionId` 串行化
      - 向已结束 workflow 投递消息时，会把 session 显式落为 `ENDED` 并拒绝继续投递
  - `apps/api` tests
    - 新增 `SessionRuntimeServiceTest`
      - 覆盖活跃 session 复用
      - 覆盖已结束 session 的 `ENDED` 落盘
      - 覆盖关闭 workflow 上的消息拒绝
- 验证：
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.session.SessionRuntimeServiceTest --tests com.lynxus.platform.catalog.CatalogServiceTest --tests com.lynxus.platform.catalog.CatalogReferenceProjectionTest :apps:api:compileJava` 通过
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py -q` 通过
  - `pnpm --dir apps/web lint` 通过
  - `rg -n "LangGraph|AssistantRunWorkflow|ConversationSession|ResumeIntervention|WorkflowInstance|GraphSnapshot|DecisionType|AssistantOrchestration" apps packages -S` 无命中
- 结构检查：
  - API 并发边界已收敛到独立锁服务，没有把串行化逻辑散落到 controller 或 repository
  - worker / runtime / catalog / web 的职责边界维持清晰，没有引入新的旧逻辑回流
- 当前结论：
  - 设计文档主路径已全部落地
  - `docs/doing` 进入最终闭环状态
  - 后续只剩工程化增强建议，不再存在主路径未完成项

### 记录 021：用户复核指出 agent-runtime 缺少真实 act/tool loop，重新打开最终 runtime 收口

- 新发现：
  - 当前 `agent-runtime` 虽已移除旧 `LangGraph`，但 OpenAI-compatible 路径仍基本是一轮 prompt 直接生成最终 `AgentDecision`
  - 这与设计文档中“推理循环内部处理工具调用和 tool result 注入”的要求仍有差距
- 当前判断：
  - 这是 runtime 侧最后一个真实结构缺口，不属于可忽略的后续增强
  - 必须把 `agent-runtime` 推进为有上限的 `LLM -> tool_call -> tool_result -> final_decision` 循环后，才能重新宣告 `docs/doing` 进入最终状态
- 下一步：
  - 先为当前 request 结构补一组内建 inspection tools
  - 再实现 OpenAI-compatible tool loop
  - 最后补测试、回写验收清单并重新闭环

### 记录 022：完成 agent-runtime act/tool loop，并重新闭合最终验收

- 已改内容：
  - `apps/agent-runtime`
    - 新增 `tooling.py`
      - 定义当前单轮推理可调用的内建 inspection tools
      - 包括 owner capabilities / available agents / available playbooks / active playbook / sharedState / recent events / capability resource directory 等读取能力
    - `decisioning.py`
      - 从单次 one-shot prompt 改为有上限的 `LLM -> tool_call -> tool_result -> final_decision` 循环
      - OpenAI-compatible provider 现在会携带 `tools` 发起 chat completion
      - 若模型返回 `tool_calls`，runtime 会执行对应内建 tools，并把 `tool_result` 作为下一轮输入继续推理
      - 超出最大步数或 provider/tool loop 失败时，仍统一回落 deterministic fallback
    - `prompting.py`
      - 明确 instruct 模型可以先调用 tools，再返回最终 JSON 决策
    - `README.md`
      - 更新当前边界说明，明确 runtime 已有 act loop
  - `apps/agent-runtime` tests
    - 新增 `test_decisioning_loop.py`
      - 覆盖真实 tool loop 完成后返回最终决策
      - 覆盖 tool loop 无法收敛时回落 deterministic fallback
- 验证：
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_decisioning_loop.py -q` 通过
  - `./gradlew :apps:api:compileJava :apps:worker:compileJava :packages:contracts-jvm:compileJava` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - `agent-runtime` 已不再是“一次性产出最终 decision”的 one-shot 模式
  - 本次重构重新回到最终闭环状态

### 记录 023：用户复核指出 tool / skill 仍未从前端到 runtime 真消费，重新打开最终收口

- 新发现：
  - 前端与 catalog 已能配置 `skillResourceIds / toolResourceIds`
  - assistant release 也已冻结对应 resource version
  - 但 `SessionRuntimeService` 启动 session 时仍只把 resource id 传给 runtime，没有把 release 冻结后的 descriptor 一并注入
  - 因此 runtime 虽有 act/tool loop，实际消费的仍主要是内建 inspection tools，而不是前端配置出的真实业务 tool / skill
- 当前判断：
  - 这不是附加增强，而是“tool / skill 从前端到 runtime 消费闭环”未完成
  - 必须同时修正：
    - API 注入源：改为 `release.agents + release.resources`
    - shared contracts：改为携带真实 model / skill / tool descriptor
    - runtime 消费：tools 真执行 provider config，skills 走目录暴露 + 按需读取
- 下一步：
  - 先重写 runtime request 快照模型
  - 再补 API/worker 注入和 Python runtime 真消费
  - 最后补测试并重新闭合最终验收

### 记录 024：完成 tool / skill 从前端到 runtime 的真实消费闭环，并重新闭合最终验收

- 已改内容：
  - `packages/contracts-jvm` / `packages/contracts`
    - `AgentConfig` 不再只携带 `skillResourceIds / toolResourceIds`
    - 新增冻结后的 `LlmModelDescriptor / SkillDescriptor / ToolDescriptor` 契约
  - `apps/api`
    - `SessionRuntimeService`
      - `buildStartRequest(...)` 改为使用 `release.agents + release.resources`
      - session start request 现在注入 release 冻结后的 model / skill / tool descriptor
      - 不再把当前 catalog draft agent 直接作为 runtime 输入
    - `SessionRuntimeServiceTest`
      - 新增断言，验证 start request 注入的是 release 冻结快照，而不是 assistant 当前 draft 配置
  - `apps/agent-runtime`
    - `models.py`
      - owner agent 结构已切到真实 descriptor
    - `tooling.py`
      - resource tools 会基于真实 tool descriptor 生成 OpenAI function definitions
      - HTTP / MCP provider 已按配置执行真实调用
      - tool input/output schema 已进入 runtime 消费
    - `prompting.py`
      - prompt capabilities 现在暴露真实 skill directory 与 tool catalog
      - skills 改为“目录先暴露、详情按需加载”
    - `decisioning.py`
      - 在 tool loop 内补入 `skillReads -> 注入 skill detail -> 继续推理` 路径
      - provider 选择优先读取当前 owner 的 model descriptor
    - `json_schema.py`
      - 新增轻量 JSON schema 校验，约束 tool 参数与返回
    - `README.md`
      - 更新当前边界说明，反映真实 descriptor 注入与消费已完成
- 验证：
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_decisioning_loop.py -q` 通过
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.session.SessionRuntimeServiceTest :apps:api:compileJava` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - tool / skill 已完成从前端配置、catalog 发布冻结、API 注入到 runtime 真消费的闭环
  - 本次重构重新回到最终闭环状态

### 记录 025：知识检索边界按“runtime 远程调用 knowledge-service”重新打开对齐

- 新判断：
  - 设计文档当前把 `knowledge-service` 表述成“合并到 runtime”
  - 但从当前工程结构和用户确认后的方向看，更合理的边界是：
    - 统一 knowledge store 继续保留
    - 在线检索入口由 runtime 远程调用 knowledge-service
    - 离线导入 / 切片 / 建索引仍由 knowledge-service 负责
- 当前缺口：
  - 文档表述未对齐
  - `AgentTurnRequest` 尚未携带冻结 knowledge binding snapshot
  - runtime 尚未补回 builtin `knowledge_search / knowledge_read` 的远程调用链
- 下一步：
  - 先修正文档与 contracts
  - 再把 release knowledge binding 注入 runtime request
  - 最后补 runtime builtin knowledge tools 与测试

### 记录 026：完成 knowledge 远程检索闭环并恢复最终验收

- 已改内容：
  - `docs/architecture/session_workflow_redesign.md`
    - 明确 runtime 通过内部接口远程调用 knowledge-service 完成在线检索
    - 不再要求把 knowledge-service 的存储层、导入链路、索引构建链路整体并入 runtime
    - 模块划分中补足 knowledge-service 的离线 ingest / indexing / retrieval data API 职责
  - `packages/contracts-jvm` / `packages/contracts`
    - 为 runtime 注入新增冻结后的 `KnowledgeBindingDescriptor`
  - `apps/api`
    - `SessionRuntimeService` 基于 assistant release 冻结知识绑定并下发到 `AgentTurnRequest`
  - `apps/agent-runtime`
    - `models.py` 增加 `KnowledgeBindingDescriptor`
    - `tooling.py` 增加 builtin `knowledge_search / knowledge_read`
    - builtin knowledge tools 通过 internal API 远程调用 knowledge-service `/internal/retrieve` 与 `/internal/read-chunks`
    - `prompting.py` / `README.md` 同步反映 knowledge binding 与远程检索边界
  - 测试：
    - `apps/agent-runtime/tests/test_decisioning_loop.py` 覆盖 knowledge search/read 远程调用链
    - `apps/api/src/test/java/com/lynxus/platform/session/SessionRuntimeServiceTest.java` 校验冻结 knowledge binding 已注入 workflow start request
- 原因：
  - 用户明确要求保留“runtime 远程调用检索”的系统边界
  - 需要把此前设计文档中的“合并 knowledge-service”表述与当前实现彻底对齐
- 验证：
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_decisioning_loop.py -q` 通过
  - `./gradlew :packages:contracts-jvm:compileJava :apps:worker:compileJava :apps:api:test --tests com.lynxus.platform.session.SessionRuntimeServiceTest :apps:api:compileJava` 通过
  - `pnpm --dir apps/web lint` 通过
- 当前结论：
  - knowledge 检索已形成“前端配置/发布冻结 -> API 注入 -> runtime builtin tools -> knowledge-service 远程读取”的真实闭环
  - `docs/doing` 已恢复为最终验收完成状态

### 记录 027：knowledge builtin tools 重新收紧为按需注入

- 新发现：
  - 当前 runtime 只要看到 `knowledgeBinding != null` 就会暴露 builtin `knowledge_search / knowledge_read`
  - 这不满足“按实际需要注入”的目标；若 `knowledgeEnabled=false`，即使存在残留 binding，也不应注入 builtin knowledge tools
- 决策：
  - 恢复统一的 effective binding 判定层
  - knowledge tool 暴露、prompt capability 展示、owner capability 回显、tool 执行全部基于同一判定结果
- 下一步：
  - 修改 `apps/agent-runtime` 注入逻辑
  - 补“disabled 但携带 binding 时不注入”的回归测试
  - 验证后再把 `docs/doing` 收回最终状态

### 记录 028：完成 knowledge builtin tools 按需注入收口

- 已改内容：
  - `apps/agent-runtime/lynxus_agent_runtime/tooling.py`
    - 新增统一的 `resolve_knowledge_binding(...)`
    - builtin knowledge tool 暴露、owner capability 回显、knowledge tool 执行均改为基于 effective binding 判定
  - `apps/agent-runtime/lynxus_agent_runtime/prompting.py`
    - prompt capabilities 中的 `knowledgeBinding` 改为基于 effective binding 暴露
  - 测试：
    - `apps/agent-runtime/tests/test_decisioning_loop.py`
      - 新增“knowledge disabled 但携带 binding 时不注入 knowledge tools”断言
    - `apps/agent-runtime/tests/test_prompting.py`
      - 新增“knowledge disabled 时 prompt 中不暴露 knowledgeBinding”断言
- 实际注入规则：
  - 仅当当前 owner `knowledgeEnabled=true` 且解析出有效 `knowledgeBinding` 时，才注入 builtin `knowledge_search / knowledge_read`
  - 若配置脏数据导致 `knowledgeEnabled=false` 但 request 仍带 binding，则 runtime 不暴露、不提示、也不允许执行 knowledge builtin tools
- 验证：
  - `uv run pytest apps/agent-runtime/tests/test_internal_auth.py apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_decisioning_loop.py -q` 通过
  - `uv run python -m compileall apps/agent-runtime/lynxus_agent_runtime` 通过
- 当前结论：
  - knowledge builtin tools 已满足“按需注入”目标
  - `docs/doing` 已恢复最终验收完成状态

### 记录 029：按设计文档逐句审计后重新打开最终验收

- 动作：
  - 重新通读 `docs/architecture/session_workflow_redesign.md` 全文
  - 按章节核查 `packages/contracts*`、`apps/api`、`apps/worker`、`apps/agent-runtime`、`apps/web`
- 关键发现：
  - `RUN_PLAYBOOK` 只校验 playbook 存在性和白名单，未校验 `playbookInput` 是否符合 `inputSchema`
  - `STEP` / `TOOL_TASK` 节点执行仍是占位逻辑，未实现文档要求的沙箱执行与真实工具调用
  - `AgentTurnActivity` 与 `PlaybookNodeActivities` 都配置为 `maximumAttempts=1`，`playbook timeoutPolicy / retryPolicy` 未真正生效
  - `session event` / `playbook run` 的控制面持久化当前依赖 API 同步 `currentProjection()`，未按文档要求由 worker / child workflow 主动落盘
  - `PromptRuntimeMessages` 尚未实现 memory window / 字节预算裁剪，`memoryWindowSize` 也未真正参与 prompt 组装
  - 设计文档内部还存在一处口径冲突：§3.2 仍写 `facts / artifacts / agentScope`，但 §5 / §10 已改为扁平 `sharedState`
- 影响：
  - 当前不能继续把最终状态判定为 fully aligned
- 下一步：
  - 先由用户确认审计结论
  - 若继续实施，需按上述差异重新分阶段收口
