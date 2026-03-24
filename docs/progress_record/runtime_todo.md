# Runtime TODO

## 1. 去掉 API 同步等待首个业务结果

现状：

- `POST /tasks`
- `POST /runtime/sessions/{sessionId}/messages`

这两条链路当前都会在 API 层同步等待 workflow 暴露第一个 `WorkflowResult`。
虽然已经把等待窗口放宽，但本质上仍然依赖固定超时时间，不够可靠。

问题：

- LLM、Skill、MCP 任一节点稍慢，API 就可能先返回 timeout
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
