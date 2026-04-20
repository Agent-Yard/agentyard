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

1. [x] **编排图校验器单测**：合法图 / 非法图 / 边界情况，防止发布出非法拓扑
2. [x] **发布快照冻结逻辑单测**：确保资源版本锚点和知识版本在快照中被正确冻结
3. [x] **SessionWorkflow / PlaybookWorkflow 集成测试**：使用 Temporal TestWorkflowEnvironment，覆盖用户消息、playbook 等待恢复、终态回流三条路径
4. [x] **Agent Runtime 单轮推理单测**：给定 `AgentTurnRequest`，验证 tool calling、skill 读取和结构化决策输出
5. [x] **Knowledge Service 导入链路单测**：source → import job → document → snapshot 状态机流转

技术方案：Java 用 JUnit 5 + Testcontainers（PostgreSQL）；Python 用 pytest + httpx AsyncClient；前端暂不要求测试。

### 1.3 CI 流水线

现状：无 CI。

目标（GitHub Actions，单 workflow 文件）：

1. Java：`./gradlew check`（compile + test）
2. Node：`pnpm install && pnpm lint && pnpm build`
3. Python：`uv sync --all-packages && uv run --all-packages pytest`
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
3. 运行态关键操作写事件：session 创建、owner reply / switch、playbook waiting / resumed / completed、handoff 开始 / 结束
4. 暂不做 event sourcing（投影仍由业务代码维护），事件日志只用于审计和排障
5. 提供 `GET /api/events?aggregateType=&aggregateId=&since=` 查询接口
6. 控制台对象详情页增加"操作历史"面板

为什么不做 event sourcing：当前阶段投影模型够用，event sourcing 的复杂度不值得。但 append-only 事件日志几乎零成本，却能解决审计、排障、合规三大问题。

### 2.2 SSE 实时推送替代部分轮询

现状：运行会话页当前通过拉取 `session detail` 轮询刷新，浪费资源且延迟不稳定。

目标：

1. API 新增 `GET /api/session-runtime/sessions/{sessionId}/stream`（SSE endpoint）
2. 推送内容围绕 `session / session event / playbook run`，不再暴露旧 `task / workflow instance`
3. 前端运行态页面优先使用 SSE，降级回轮询
4. 非运行态页面（catalog 管理）保持按需刷新，不需要 SSE

为什么选 SSE 而非 WebSocket：单向推送足够；SSE 天然支持断线重连和 `Last-Event-ID`；不需要额外的连接管理复杂度。

### 2.3 Session / Playbook 恢复链路产品化

现状：底层恢复链路已经切到 `session workflow + playbook waiting` 语义；API、Worker 和契约层已经统一采用：

- `POST /api/session-runtime/sessions/{sessionId}/human-resume`
- `POST /api/session-runtime/sessions/{sessionId}/external-callback`
- `POST /api/session-runtime/sessions/{sessionId}/handoff/end`
- `POST /api/session-runtime/sessions/{sessionId}/human-reply`

下一步目标：

1. 在 Web 运行页补齐人工接管、human resume、external callback 的操作面板
2. 把等待态和人工接管态从“仅看事件时间线”升级为“事件 + 操作台”组合体验
3. 对过期恢复、幂等命中和错误恢复结果补更明确的用户可见反馈

为什么单列：这是 external interaction（§2.5）和人工处理闭环真正可用的前置条件。

### 2.4 草稿默认模型策略收敛

现状：已完成。草稿默认模型不再使用“取第一个可用 LLM”的隐式策略。

完成内容：

1. Assistant 草稿模型策略统一改为显式 `defaultModelResourceId`，Catalog / OpenAPI / Web 类型与引用分析同步收敛
2. `CatalogService` 不再自动选择第一个 `LLM_MODEL`；草稿未配置默认模型时保持 `null`
3. 发布前新增阻断校验：未配置 `defaultModelResourceId` 的 Assistant 不能发布
4. 发布快照新增 `defaultModelBinding`，显式冻结发布时实际绑定的模型资源、版本、provider 和 modelId
5. Runtime 预检改为：
   `currentRelease` 存在时继续按冻结发布版运行；只有“未发布草稿且未配置默认模型”才阻断 `createSession / sendMessage`
6. owner agent 命中的模型信息已经冻结在 release descriptor 中，后续如需补运行态 `modelHits`，应直接挂到 session / playbook 观测模型下，而不是回退到旧 `WorkflowResult`
7. 控制台已拆开展示三层语义：
   草稿默认模型、当前发布冻结模型；运行态命中明细后续应放在 session runtime 观测区补齐

### 2.5 External Interaction 一等能力

现状：external interaction 已经不再作为独立 runtime 主模型存在，而是 playbook 的等待点之一。当前代码已经具备：

- playbook `EXTERNAL_INTERACTION` 节点
- session runtime 外部回调入口
- `PLAYBOOK_WAITING / EXTERNAL_CALLBACK_RECEIVED / PLAYBOOK_RESUMED` 事件链路

但 provider adapter、签名校验和主动查单补偿仍未实现。

目标（分两步）：

**第一步 — 通用框架：**

1. [x] Playbook 支持 `EXTERNAL_INTERACTION` 等待节点
2. [x] `SessionWorkflow` 支持 `external-callback` Signal 并写入 `EXTERNAL_CALLBACK_RECEIVED`
3. [x] `playbook_run.waitingReason` 已成为恢复目标校验的权威状态
4. [x] 外部回调恢复后会继续推进 playbook，并在终态时回流 owner reevaluation
5. [ ] 若业务需要对外暴露交互卡片或回跳链接，必须直接挂在 session / playbook 模型上，而不是重建旧 `external_interaction_task`

当前实现说明：

- 当前公开入口是 `POST /api/session-runtime/sessions/{sessionId}/external-callback`
- 回调恢复目标必须同时命中：
  - 正确 `sessionId`
  - 正确 `playbookRunId`
  - 当前 `playbook_run.status = WAITING`
  - 当前 `waitingReason` 属于 `external_interaction:*`
- 不满足条件的回调只能视为无效 / 过期 / 重复请求，不得再次推进状态机
- 如果未来需要面向前端暴露站外交互卡片，也必须把它当成 session 运行时间线的一部分，而不是独立 runtime 子系统

**第二步 — 首个真实 provider：**

7. 接入一个真实支付或 OAuth provider
8. Provider adapter 抽象、webhook 签名校验、幂等处理
9. 主动查单补偿机制

依赖：2.3（Session / Playbook 恢复链路产品化）。

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
   - Session / Playbook 启动、完成、失败计数与耗时
   - Agent turn 执行耗时
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

1. Session / Playbook 级别成本归因（token 消耗、工具调用次数）
2. 按 assistant / scenario / domain 聚合成本报表
3. SLO 定义与告警：session 成功率、playbook 完成率、P95 延迟、失败率阈值
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
- 后续待补的是对象级深链能力：如 session / playbook / knowledge / resource 的选中对象通过 URL 直达与恢复

### C. Session Event Payload 规范化

现状：

- 当前运行态对外主模型已经是 `SessionRuntimeDetail`
- 用户消息、owner 回复、playbook 进度、handoff、恢复信号都通过 `SessionEvent.payload` 表达结构化事实
- Web 运行页已经直接渲染 `SessionEvent` 与 `PlaybookRun`

统一目标：

- 让各类 `SessionEventType` 的 payload schema 明确化、稳定化
- 避免继续依赖“某个字段碰巧存在”的弱约定来渲染运行页
- 为后续 SSE、审计查询和外部集成提供稳定契约

优先类型：

- `USER_MESSAGE`
- `OWNER_REPLY`
- `OWNER_SWITCH`
- `PLAYBOOK_STARTED`
- `PLAYBOOK_WAITING`
- `PLAYBOOK_RESUMED`
- `PLAYBOOK_COMPLETED`
- `SESSION_HUMAN_HANDOFF_STARTED`
- `SESSION_HUMAN_HANDOFF_ENDED`
- `HUMAN_RESUME_RECEIVED`
- `EXTERNAL_CALLBACK_RECEIVED`

跨层影响：

- `packages/contracts-jvm` 中的 `SessionContracts.SessionEvent`
- OpenAPI 与 TypeScript contracts
- API `session-runtime` DTO
- Web 运行页事件渲染逻辑
- 后续 SSE event payload 与审计查询接口

实施顺序：

1. 先为高频事件补显式 schema 和最小字段集
2. 再让 Web 运行页按 event type 做稳定渲染，而不是直接打印任意 JSON
3. 最后再把这套 schema 复用到 SSE 和审计查询

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

- [x] P0.1 运行态持久化：session / session_event / playbook_run 落库
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
