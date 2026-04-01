# Lynxus 生产级系统全局 TODO

> 目标：从当前可运行原型演进到可交付生产环境的企业级 Agent 编排平台。
> 原则：每一阶段交付后系统都应该是"可用的"，不做半成品堆砌。

---

## 阶段一：工程基座（Engineering Foundation）

> 目标：让代码库具备持续演进的基本保障。没有这一层，后续所有功能都在沙上建塔。

### 1.1 真实身份与请求级鉴权

现状：已完成 OIDC-first 登录流首版、API 请求级鉴权、前端登录页与 API 托管会话；已补齐跨服务 internal token 与首版粗粒度 RBAC，当前保留四角色模型并按治理写权限收口。

目标：

1. [x] 引入标准化 OIDC 登录流；Lynxus 核心只依赖 OIDC 协议，不内置 Keycloak / Casdoor 等特定 IdP
2. [x] API 层统一 auth filter：校验 session / JWT，注入 `SecurityContext`
3. [x] 前端补登录页、API 托管会话、401 拦截与自动跳转
4. [x] Agent Runtime 与 Knowledge Service 的服务间调用引入 internal token 校验（不走 OIDC，走共享密钥或 service account）
5. [x] 预留 RBAC 注解，并按当前四角色实现首版粗粒度权限：`PLATFORM_ADMIN / DOMAIN_ADMIN / DEVELOPER` 可治理写，`BUSINESS_USER` 仅运行态与只读访问

为什么排最前：没有 auth 的系统不能交给任何真实用户，也无法做审计。

### 1.2 关键路径测试

现状：几乎零测试覆盖。

目标（不追求覆盖率，只保护关键路径）：

1. **编排图校验器单测**：合法图 / 非法图 / 边界情况，防止发布出非法拓扑
2. **发布快照冻结逻辑单测**：确保资源版本锚点和知识版本在快照中被正确冻结
3. **Workflow start → signal → resume 集成测试**：使用 Temporal TestWorkflowEnvironment，覆盖正常完成、人工恢复、失败三条路径
4. **Agent Runtime 图执行单测**：给定快照 JSON，验证节点推进顺序和输出结构
5. **Knowledge Service 导入链路单测**：source → import job → document → snapshot 状态机流转

技术方案：Java 用 JUnit 5 + Testcontainers（PostgreSQL）；Python 用 pytest + httpx AsyncClient；前端暂不要求测试。

### 1.3 CI 流水线

现状：无 CI。

目标（GitHub Actions，单 workflow 文件）：

1. Java：`./gradlew check`（compile + test）
2. Node：`pnpm install && pnpm lint && pnpm build`
3. Python：`uv sync --all-packages && uv run pytest`
4. 门禁：PR 不过 CI 不能合并

依赖：1.2 测试先有内容，CI 才有意义。

### 1.4 结构化日志统一

现状：已完成四个后端服务的结构化日志统一。Java 服务在默认 profile 下输出结构化 JSON，本地 `local` profile 保留可读文本；Python 服务统一切到 `structlog` + `contextvars`，默认本地 `console`、非本地 `json`。

目标：

1. [x] Java 服务统一结构化日志输出，包含 `service / traceId / spanId / sessionId / workflowId / customerId / userId / level / message`
2. [x] Python 服务统一采用 `structlog`，通过共享初始化模块与 `contextvars` 注入上下文
3. [x] API、Worker、Agent Runtime、Knowledge Service 统一透传 `traceparent`、`X-Lynxus-Session-Id`、`X-Lynxus-Workflow-Id`、`X-Lynxus-Customer-Id`、`X-Lynxus-User-Id`
4. [x] Workflow 输入上下文扩展 `traceId / sessionId / workflowId / customerId / userId`，由 API 显式传给 Worker，不依赖线程黑盒传播
5. [x] 本地开发保留可读格式：Java 通过 `local` profile，Python 通过 `LYNXUS_LOG_FORMAT=console|json`

落地说明：

- `customerId` 表示业务客户或外部终端用户，允许为空；`userId` 表示平台系统用户，仅在存在平台认证上下文时写入
- API 请求入口会绑定日志上下文，Worker 在 workflow/activity 边界恢复上下文，Python 服务在 FastAPI middleware 中绑定并清理上下文
- Web 运行态表单语义已对齐：会话对话使用 `customerId`，流程观测里的人工恢复使用 `userId`
- Agent Runtime 与 Knowledge Service 已移除高噪音 prompt/response 整段日志，改为摘要型结构化日志，降低敏感信息暴露风险

为什么放基座：后续 OTel、审计、排障全部依赖结构化日志。

---

## 阶段二：运行态成熟（Runtime Maturity）

> 目标：让运行链路足够健壮，能承受真实业务场景而非仅 demo 演示。

### 2.1 平台事件日志

现状：只有投影表，没有变更事件记录。投影表只保留最新态，无法回溯"谁在什么时候做了什么"。

目标：

1. 新增 `platform_event` 表（append-only）：`id / event_type / aggregate_type / aggregate_id / actor_id / payload / occurred_at`
2. 控制面关键操作写事件：创建、更新、删除、发布、归档
3. 运行态关键操作写事件：workflow 启动、暂停、恢复、完成、失败
4. 暂不做 event sourcing（投影仍由业务代码维护），事件日志只用于审计和排障
5. 提供 `GET /api/events?aggregateType=&aggregateId=&since=` 查询接口
6. 控制台对象详情页增加"操作历史"面板

为什么不做 event sourcing：当前阶段投影模型够用，event sourcing 的复杂度不值得。但 append-only 事件日志几乎零成本，却能解决审计、排障、合规三大问题。

### 2.2 SSE 实时推送替代部分轮询

现状：全局 3 秒轮询，浪费资源且延迟不稳定。

目标：

1. API 新增 `GET /api/runtime/sessions/{sessionId}/stream`（SSE endpoint）
2. 推送事件：新消息、workflow 状态变更、人工干预请求
3. 前端运行态页面优先使用 SSE，降级回轮询
4. 非运行态页面（catalog 管理）保持按需刷新，不需要 SSE

为什么选 SSE 而非 WebSocket：单向推送足够；SSE 天然支持断线重连和 `Last-Event-ID`；不需要额外的连接管理复杂度。

### 2.3 Workflow 恢复模型泛化

现状：已完成全链路恢复模型泛化。共享契约、API、Worker、Agent Runtime、Web 统一采用 `ResumeAction` / `WAITING_RESUME` 语义，并在 checkpoint 中显式记录 `resumeContext`。

完成内容：

1. [x] `ResumeAction` 统一包含 `type + source`，支持 `HUMAN / EXTERNAL_SYSTEM / TIMEOUT_POLICY`
2. [x] `PauseReason` / `ResumeTask` 的 `source` 统一改为通用挂起来源枚举，并预留 `EXTERNAL_INTERACTION_REQUIRED`
3. [x] Workflow checkpoint 显式记录 `resumeContext`（包含 interactionTaskId / interactionType / timeoutPolicyKey 等）
4. [x] API 恢复入口统一为 `/workflows/{workflowId}/resume`，移除旧 `human-action` 语义
5. [x] 运行态主状态统一改为 `WAITING_RESUME`，前后端观测与恢复表单同步更新

为什么提前做：这是 external interaction（§2.5）的前置依赖，也让 workflow 模型更准确。

### 2.4 草稿默认模型策略收敛

现状：已完成。草稿默认模型不再使用“取第一个可用 LLM”的隐式策略。

完成内容：

1. Assistant 草稿模型策略统一改为显式 `defaultModelResourceId`，Catalog / OpenAPI / Web 类型与引用分析同步收敛
2. `CatalogService` 不再自动选择第一个 `LLM_MODEL`；草稿未配置默认模型时保持 `null`
3. 发布前新增阻断校验：未配置 `defaultModelResourceId` 的 Assistant 不能发布
4. 发布快照新增 `defaultModelBinding`，显式冻结发布时实际绑定的模型资源、版本、provider 和 modelId
5. Runtime 预检改为：
   `currentRelease` 存在时继续按冻结发布版运行；只有“未发布草稿且未配置默认模型”才阻断 `launchTask / sendMessage`
6. `WorkflowResult / WorkflowInstance` 新增 `modelHits`，agent-runtime 在真实发起 LLM 调用前记录模型命中快照
7. 控制台已拆开展示三层语义：
   草稿默认模型、当前发布冻结模型、最近一次运行命中模型；Workflow 页也可查看本次运行的完整模型命中明细

### 2.5 External Interaction 一等能力

现状：已完成第一步通用框架，并进一步收敛为 runtime 统一消息出口。共享契约、OpenAPI、API Runtime DTO、`external_interaction_task / event` 持久化、interaction card 渲染、前端回跳 ack、后端 callback ingest 与基于 `ResumeAction` 的立即恢复链路均已落地。当前协议约束为：前端回跳不是可信结果信源；`RETURNED` 或 `PROCESSING` 的首次有效事件都会立即恢复 workflow；是否在恢复后调用工具确认真实结果，属于 assistant / workflow 配置逻辑，不由系统层硬编码。第二步的 provider adapter、签名校验和主动查单补偿仍未实现。 

目标（分两步）：

**第一步 — 通用框架：**

1. [x] 共享契约引入 `ExternalInteractionType / Status / Task / Result / Event`
2. [x] `ConversationMessage` 切换为统一的 `payloadType + payload` 消息模型，文本消息也使用 `TEXT` payload 表达
3. [x] RuntimeService 新增 interaction task 创建 / 查询 / return / callback、幂等事件表与状态机
4. [x] Workflow 使用泛化后的 `ResumeAction` 处理 external interaction 恢复；恢复载荷统一放入 `ResumeAction.attributes`
5. [x] 前端支持 interaction 卡片渲染、回跳参数解析、状态轮询
6. [x] 持久化：`external_interaction_task` + `external_interaction_event` 表
7. [x] `WorkflowResult` 收敛为“流程状态 + 累计 outputMessages”，assistant 文本输出与 `EXTERNAL_INTERACTION` 共用统一出站协议
8. [x] `conversation_message` 增加 `message_key`，workflow 投影改为按 `(workflow_instance_id, messageKey)` 幂等落消息
9. [x] interaction task 创建闭环改为：agent-runtime 发出 `EXTERNAL_INTERACTION` output message，控制面 API 在消费 workflow 结果时内部创建 task 和卡片投影

当前实现说明：

- 已提供 `GET /api/runtime/interactions/{taskId}`、`POST /api/runtime/interactions/{taskId}/return`、`POST /api/runtime/interactions/callbacks/{provider}`
- interaction 创建由控制面 API 内部负责，不对外暴露单独的公共创建入口
- runtime 出站统一采用 `WorkflowResult.outputMessages`；当前先落地 `TEXT` 与 `EXTERNAL_INTERACTION`
- `outputMessages` 采用 workflow 级累计 append-only 语义，`messageKey` 在单 workflow 内唯一且不可变
- `ExternalInteractionTask` 是唯一真相源；卡片消息只是 task 的展示投影
- 首次有效 `FRONTEND_RETURN` 会把 task 推进到 `RETURNED` 并立即触发 `EXTERNAL_SYSTEM` resume
- 首次有效 `PROVIDER_CALLBACK` 会把 task 推进到 `PROCESSING`，如有可信结果则一并写入 `latestResult`，并立即触发 `EXTERNAL_SYSTEM` resume
- 后续重复 return/callback 只记事件，不重复 resume

**第二步 — 首个真实 provider：**

7. 接入一个真实支付或 OAuth provider
8. Provider adapter 抽象、webhook 签名校验、幂等处理
9. 主动查单补偿机制

依赖：2.3（恢复模型泛化）。

### 2.6 软删除与生命周期治理

现状：全部硬删除，删除后不可恢复。

目标：

1. 核心目录对象引入 `lifecycle_status`（`ACTIVE / ARCHIVED`）
2. 删除操作改为归档（`ARCHIVED`），主视图默认过滤
3. 提供归档视图、恢复入口
4. 记录归档原因、操作人、操作时间（复用 §2.1 事件日志）
5. 归档对象的引用关系保留，但不参与新的绑定和发布
6. 彻底清理作为独立管理员操作，需二次确认

---

## 阶段三：生产加固（Production Hardening）

> 目标：让系统可以部署到真实环境并稳定运行。

### 3.1 容器化与部署

现状：只有 docker-compose 用于本地依赖，应用本身无容器镜像。

目标：

1. 为每个应用编写 Dockerfile（多阶段构建）
   - API + Worker：Gradle build → JRE 运行镜像
   - Web：pnpm build → Nginx 静态文件
   - Agent Runtime + Knowledge Service：uv build → Python slim 镜像
2. 编写生产级 docker-compose（所有应用 + 依赖一键启动）
3. 编写 Kubernetes manifests（Deployment / Service / ConfigMap / Secret / Ingress）
4. 每个服务实现 health check endpoint（readiness + liveness）
5. 每个服务实现 graceful shutdown

### 3.2 OpenTelemetry 统一观测

现状：无指标、无追踪。

目标：

1. Java 服务接入 OpenTelemetry Java Agent（零代码侵入追踪）
2. Python 服务接入 opentelemetry-python（FastAPI 自动 instrument）
3. 统一 trace context 传播：API → Worker → Agent Runtime / Knowledge Service
4. 关键业务指标（Micrometer / OTel Metrics）：
   - Workflow 启动/完成/失败计数与耗时
   - Agent 节点执行耗时
   - Knowledge 检索延迟与命中率
   - API 请求延迟 P50/P95/P99
5. 推荐后端：Grafana Tempo（trace）+ Prometheus（metrics）+ Grafana（dashboard）

依赖：1.4 结构化日志先到位，OTel 日志 bridge 才能统一。

### 3.3 数据安全与备份

目标：

1. PostgreSQL：配置 WAL archiving + 定期 pg_dump，至少支持 point-in-time recovery
2. MinIO：配置 bucket versioning + 定期 mirror 到备份存储
3. pgvector：知识索引可重建，备份优先级低于 PostgreSQL
4. Temporal：使用 Temporal 自带的 visibility store，不额外备份
5. 敏感配置（数据库密码、API key、OIDC secret）统一走 K8s Secret 或 Vault，不在代码/配置文件中明文存储

### 3.4 性能基线与优化

目标：

1. API 分页审计：确保所有列表接口支持分页，数据库查询有索引
2. 连接池配置：HikariCP（Java）、SQLAlchemy pool（Python）参数调优
3. 前端：继续优化路由级代码分割（Vue Router 已引入，控制台菜单页已完成真实路由）
4. Knowledge Service：基于 `PostgreSQL + pgvector + pg_trgm + tsvector` 持续优化检索质量与索引性能
   - 优势：减少基础设施依赖，数据库与检索链路统一运维
   - 当前方案：保留 `LEXICAL / VECTOR / HYBRID` 三种模式，embedding 由 OpenAI-compatible `/embeddings` 提供
   - 关注点：大规模 chunk 量级下持续评估索引参数、查询延迟与 embedding 成本
5. 缓存策略：catalog 热数据（发布快照）考虑进程内缓存或 Redis

### 3.5 安全加固

目标：

1. API 输入校验全覆盖（Spring Validation 已引入，确保无遗漏）
2. CORS 收紧为生产域名白名单
3. Rate limiting：API 层引入基础限流（Bucket4j 或 API Gateway 层面）
4. SQL 注入防护审计（当前 JDBC template 需确认参数化查询无遗漏）
5. 前端 XSS 防护：确保用户输入内容展示时经过转义
6. Webhook 签名校验（§2.5 external interaction 依赖）
7. 文件上传安全：类型白名单、大小限制、病毒扫描（可选）

---

## 阶段四：平台演进（Platform Evolution）

> 目标：从单租户单实例演进为可规模化运营的平台。当前阶段规划即可，不急于实现。

### 4.1 RBAC 细粒度权限

1. 扩展角色体系：`admin / editor / viewer` + 自定义角色
2. 对象级权限：谁能编辑哪个 domain / assistant
3. 操作级权限：谁能发布、谁能删除
4. 权限 UI：角色管理、成员管理、权限分配

### 4.2 资源治理增强

1. 版本 diff 与变更摘要
2. "资源变更影响哪些已发布助手"可视化
3. 批量操作：批量发布、批量归档、批量替换资源锚点
4. 资源版本和知识发布版本的删除预览

### 4.3 灰度发布与回滚

1. 发布支持灰度策略：按比例、按标签、按用户分组
2. 发布支持一键回滚到上一个快照
3. 发布影响预估：新快照与当前快照的 diff 分析

### 4.4 多租户隔离

1. 数据层：schema 级或 row 级租户隔离
2. 运行态：Temporal namespace 级隔离
3. 资源层：MinIO bucket 级隔离
4. 计量：按租户统计用量

### 4.5 成本治理与 SLO

1. Workflow 级别成本归因（token 消耗、工具调用次数）
2. 按 assistant / scenario / domain 聚合成本报表
3. SLO 定义与告警：workflow 完成率、P95 延迟、失败率阈值
4. 配额管理：按租户/用户设置用量上限

### 4.6 知识检索质量治理

1. 召回评估：给定 query，对比不同快照的召回结果
2. 命中质量分析：相关性评分、噪声比例
3. A/B 测试：不同 embedding 模型 / chunk 策略的效果对比
4. 自动重建策略：源文档更新后自动触发重新导入与索引

---

## 架构演进建议（供决策参考）

以下是在全局复盘中识别到的架构级优化方向，不作为 TODO 排期，而是在对应阶段实施时一并考虑：

### A. PostgreSQL 检索栈持续优化

- 当前知识检索已统一收敛到 `pgvector + pg_trgm + tsvector`
- 后续重点不再是替换检索后端，而是调优 embedding 模型、索引参数、候选集规模与混合召回策略
- 对早期规模（<100 万文档 chunks）当前架构足够，后续按实际负载决定是否引入独立搜索系统

### B. Vue Router 持续完善

- Vue Router 已引入，`/login` 与 `/console/...` 以及控制台 11 个菜单页均已具备真实路由
- 当前已支持：浏览器前进后退、URL 直接访问菜单页、路由级懒加载，首屏体积已较单页模式收敛
- 后续待补的是对象级深链能力：如 workflow / session / knowledge / resource 的选中对象通过 URL 直达与恢复

### C. 消息 payload 结构化（阶段二 §2.5 前置）

现状：

- 已完成统一消息 payload 改造：对外 `ConversationMessage` / `ConversationMessageRequest` / `CreateConversationSessionRequest` 已统一采用 `payloadType + payload`
- 运行态内部存储与 `SessionContext` 保留 `content`，用于 LLM 上下文、列表摘要和历史回放文本语义

统一目标：

- 持久化消息、API 返回消息、前端渲染消息统一采用 `payloadType + payload` 模型
- 文本消息不再特殊对待，改为 `payloadType = TEXT`，通过结构化 `payload` 表达
- `content` 不再作为对外 canonical message 字段，只作为内部持久化和运行时文本投影保留

v1 类型：

- `TEXT`：承载普通对话文本
- `EXTERNAL_INTERACTION`：承载外部交互卡片及其关联 task 信息
- `RICH_CARD / CODE_BLOCK` 等只保留为后续扩展方向，本阶段不定义具体 schema

跨层影响：

- `packages/contracts`、OpenAPI、API Runtime DTO、`conversation_message` 表、Worker / Agent Runtime `SessionContext`、Web 消息渲染层已同步切换到统一消息 payload
- 数据库存储已采用 `payload_type + payload_json + content` 双层模型：payload 负责 canonical 语义，content 负责内部文本语义
- 后续 external interaction 需要直接复用这套消息基座，而不是再引入旁路字段或把 JSON 塞回文本

实施顺序：

1. [x] 已完成统一消息契约与存储模型
2. [x] 已完成前端渲染切到 payload
3. 下一步接 `EXTERNAL_INTERACTION` 的 task / card / return 流程
4. 最后扩展其他消息类型

### D. API 版本策略（阶段三之前确定）

- 当前所有 API 在 `/api/` 下，无版本号
- 生产上线前需确定版本策略：URL 路径（`/api/v1/`）或 Header
- 建议在第一次有外部消费者之前完成

---

## 依赖关系总览

```
阶段一：工程基座
  1.1 身份与鉴权 ─────────────────────────────────────┐
  1.2 关键路径测试                                      │
       └── 1.3 CI 流水线                                │
  1.4 结构化日志 ──────────────────────────────┐        │
                                                │        │
阶段二：运行态成熟                              │        │
  2.1 事件日志 ◄──── 1.1（需要 actor_id）──────┤        │
  2.2 SSE 推送                                  │        │
  2.3 恢复模型泛化                              │        │
       └── 2.5 External Interaction ◄── 2.3    │        │
  2.4 草稿模型策略                              │        │
  2.6 软删除 ◄──── 2.1（归档事件记录）          │        │
                                                │        │
阶段三：生产加固                                │        │
  3.1 容器化与部署                              │        │
  3.2 OTel 观测 ◄──── 1.4（结构化日志）────────┘        │
  3.3 数据安全                                           │
  3.4 性能优化                                           │
  3.5 安全加固 ◄──── 1.1（请求级鉴权）──────────────────┘

阶段四：平台演进
  4.1 RBAC ◄──── 1.1
  4.2 资源治理增强
  4.3 灰度发布 ◄──── 2.6（需要快照 diff）
  4.4 多租户 ◄──── 1.1 + 3.1
  4.5 成本与 SLO ◄──── 3.2（需要 metrics）
  4.6 知识检索质量
```

---

## 已完成工作回顾（本块仅作旧记录参考，不再改动）

以下工作项已在前序开发中完成，不再列为 TODO（详见 `docs/develop_record/2026-03-project_todos_snapshot.md`）：

- [x] P0.1 运行态持久化：session/task/workflow/humanIntervention 落库
- [x] P0.2 同步等待改异步 + 轮询观测闭环
- [x] P1.1 结构化决策契约对齐
- [x] P1.2 Workflow 失败可观测性：结构化错误码 + root cause
- [x] P1.3 JSONB catalog store 关键路径关系模型化
- [x] P1.4 主链路移除 demo/seed
- [x] P2.1 统一对象级引用分析接口
- [x] P2.2 删除前影响预览
- [x] P2.3 知识库真实导入与检索链路
- [x] P2.4 身份与权限体系 Phase 1
- [x] P3.1 前端大文件拆分
- [x] P3.3 Python 服务共享依赖管理（uv workspace）
