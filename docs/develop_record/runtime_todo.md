# Runtime TODO

## 1. 去掉 API 同步等待首个业务结果

现状：

- `POST /tasks`
- `POST /runtime/sessions/{sessionId}/messages`

这两条链路当前都会在 API 层同步等待 workflow 暴露第一个 `WorkflowResult`。
虽然已经把等待窗口放宽，但本质上仍然依赖固定超时时间，不够可靠。

问题：

- LLM、Tool 任一节点稍慢，API 就可能先返回 timeout
- 真实错误可能已经发生，但前端先收到的是“workflow did not expose a result before timeout”
- Query 轮询 `currentResult` 只是观察，不是可靠同步点

后续目标：

1. `sendMessage` / `launchTask` 只负责启动 workflow，并立即返回 `workflowId`
2. workflow 启动后尽快写入一个 `RUNNING` 状态的中间结果
3. 前端改为轮询 `/workflows/{workflowId}` 或使用 SSE/WebSocket 订阅
4. `WAITING_HUMAN / COMPLETED / FAILED` 都通过 workflow 详情自然传播，而不是依赖 API 同步等待

## 2. 统一 seed / release 的默认模型语义

现状：

- seed 助手的默认模型不是写死“自定义兼容模型”，而是通过 `defaultLlmResourceId()` 动态决定
- 逻辑位置：
  - `apps/api/src/main/java/com/lynxus/platform/catalog/CatalogService.java`
- 该逻辑会根据环境变量选择：
  - 若存在 `LYNXUS_OPENAI_COMPATIBLE_BASE_URL` 或 `OPENAI_COMPATIBLE_API_KEY`，选 `resource-llm-compatible`
  - 否则若存在 `OPENAI_API_KEY`，选 `resource-llm-openai`
  - 否则回退到默认值

这意味着“seed 的助手看起来是自定义模型”这件事并不是硬编码事实，而是一次 seed 时根据环境算出来的结果。

另外还有一个更关键的点：

- assistant 发布时会冻结 release snapshot
- runtime 执行优先使用 release snapshot 中冻结下来的资源锚点
- 如果数据库里已经存在早期 seed / release，那么后续即使改了代码默认值，旧数据也不会自动改写

所以你看到“为什么代码里好像应该走兼容模型，实际运行却还会打 OpenAI 或别的地址”，通常有两类原因：

1. 当时 seed 的环境变量不同，最初就选成了 `resource-llm-openai`
2. 数据库里已经有旧的 assistant release snapshot，后来改代码不会回写旧快照

后续目标：

1. 增加一个显式的“演示模式默认模型策略”，不要再隐含依赖环境变量优先级
2. 为 demo seed 增加版本戳或迁移逻辑，必要时自动重建演示数据
3. 在 UI 上明确展示：
   - assistant 当前草稿默认模型
   - 当前发布 release 使用的冻结模型
   - 实际 workflow 命中的模型资源

## 3. 增强 workflow 失败可观测性

现状：

- workflow 失败时已经会尽量写出 `FAILED` 结果
- 但前端和 API 侧仍可继续增强失败原因展示

后续目标：

1. 在 workflow 详情页直接展示 root cause、失败节点、失败资源
2. 在会话页把“timeout / runtime failure / tool failure”区分展示
3. 为 tool / llm / mcp 失败补充统一错误码和更稳定的错误摘要

## 4. 持久化 runtime 会话 / workflow 观测状态

现状：

- `RuntimeService` 中的 `sessions / tasks / workflows` 仍主要保存在 API 进程内存里
- 当前页面刷新可以依赖同一进程内存做“超时后找回 workflow 并继续恢复”
- 但如果 API 进程重启，已有会话、人工待办、workflow 观测状态和失败摘要都会丢失

问题：

- Temporal workflow 还在，但控制面可能失去对应的业务视图与恢复入口
- 人工节点虽然可以继续等待 signal，但控制台无法稳定列出“待处理 workflow”
- 真实失败已经发生时，前端未必还能看到之前同步下来的失败摘要

后续目标：

1. 把 `ConversationSession / TaskInstance / WorkflowInstance / HumanIntervention` 持久化到数据库
2. workflow 启动、进入 `WAITING_HUMAN`、恢复、完成、失败时都增量落库，而不是只存内存快照
3. API 启动后支持从数据库重建运行态列表，并和 Temporal 当前 execution 做对账
4. 把“待人工处理 workflow”做成稳定查询，不依赖单个 API 进程存活
5. 为超时恢复场景补充一个明确的“按 sessionId / workflowId 找回并继续处理”入口

## 5. 把结构化 agent 响应升级为显式契约

现状：

- `agent-runtime` 已经支持结构化 `toolRequests / routeDecision / finish / humanRequest` 响应
- 但当前仍以 runtime 内部 JSON 解析和 fallback 逻辑为主
- JVM contracts、OpenAPI、控制面 DTO 里还没有一套正式公开的 agent 节点结构化响应契约

问题：

- Python runtime、控制面和文档之间的语义约束仍然偏松，后续演进容易出现字段漂移
- 目前的结构化输出更多是 prompt 约定，不是跨端共享的强类型接口
- 一旦要做更稳定的多轮 tool loop、调试观测或 provider 扩展，就会缺少统一 wire shape

后续目标：

1. 在 `packages/contracts-jvm`、`packages/contracts` 和 OpenAPI 中新增显式的 agent 结构化响应模型
2. 明确 `message / routeDecision / toolRequests / finish / humanRequest` 的字段定义、必填性和约束
3. 让 Python runtime、worker、API 和前端观测统一消费这套结构化契约，而不是各自推断 JSON
4. 为结构化响应补充单测、集成测试和失败回退策略，确保新旧 prompt 过渡稳定
