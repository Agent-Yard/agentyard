# Lynxus 生产级系统全局 TODO

> 目标：从当前可运行原型演进到可交付生产环境的企业级 Agent 编排平台。
> 原则：每一阶段交付后系统都应该是"可用的"，不做半成品堆砌。
> 本文件只保留“当前仍待办”的事项。已完成工作不再罗列在主线，统一沉淀到末尾的“已完成工作回顾”段，详细过程留档见 `docs/develop_record/`。

---

## 阶段一：工程基座（Engineering Foundation）

> 已完成：身份与请求级鉴权（§旧 1.1）、关键路径测试（§旧 1.2）、结构化日志统一（§旧 1.4）、CI 流水线（当前 1.1）。详见末尾“已完成工作回顾”。

阶段一主线已清零：仓库已补齐单文件 GitHub Actions workflow，覆盖 Java `./gradlew check`、Node `pnpm install && pnpm lint && pnpm build`、Python `uv sync --all-packages && uv run --all-packages pytest` 三条门禁链路。

---

## 阶段二：运行态成熟（Runtime Maturity）

> 目标：让运行链路足够健壮，能承受真实业务场景而非仅 demo 演示。
> 已完成：平台事件日志（§旧 2.1）、SSE 跨实例推送（§旧 2.2）。详见末尾“已完成工作回顾”。

### 2.1 Session / Playbook 恢复链路产品化

现状：底层恢复链路已经切到 `session workflow + playbook waiting` 语义；API、Worker 和契约层已经统一采用：

- `POST /api/session-runtime/sessions/{sessionId}/human-resume`
- `POST /api/session-runtime/sessions/{sessionId}/external-callback`
- `POST /api/session-runtime/sessions/{sessionId}/handoff/end`
- `POST /api/session-runtime/sessions/{sessionId}/human-reply`

但 Web 运行页只有会话创建、消息发送、事件查看和 playbook run 查看，没有针对 handoff / human resume / external callback 的内置操作面板。

下一步目标：

1. 在 Web 运行页补人工回复、结束 handoff、恢复 waiting playbook 的操作面板
2. 对 `PLAYBOOK_WAITING / SESSION_HUMAN_HANDOFF_STARTED` 增加明确操作提示，把等待态和人工接管态从“仅看事件时间线”升级为“事件 + 操作台”组合体验
3. 区分“业务用户发送消息”和“人工操作员处理会话”两类入口
4. 对过期恢复、幂等命中和错误恢复结果补更明确的用户可见反馈

为什么单列：这是 external interaction（§2.2）和人工处理闭环真正可用的前置条件。

### 2.2 External Interaction 第二步：首个真实 provider

现状：通用框架已落地，包括 playbook `EXTERNAL_INTERACTION` 等待节点、`SessionWorkflow` 的 `external-callback` Signal、`waitingReason` 权威校验、回调恢复后回流 owner reevaluation。当前公开入口为 `POST /api/session-runtime/sessions/{sessionId}/external-callback`，回调必须命中 `sessionId / playbookRunId / WAITING / waitingReason ∈ external_interaction:*` 四元约束，否则视为无效。

下一步目标：

1. 接入一个真实支付或 OAuth provider，端到端跑通业务场景
2. 抽象 Provider adapter，落地 webhook 签名校验与幂等处理
3. 主动查单补偿机制，覆盖 webhook 丢失 / 延迟场景
4. 若业务需要对外暴露交互卡片或回跳链接，必须直接挂在 session / playbook 模型上，而不是重建旧 `external_interaction_task`

依赖：§2.1（恢复链路产品化）。

### 2.3 软删除与生命周期治理

现状：核心目录对象全部硬删除，删除后不可恢复；发布快照和资源版本能保留一部分历史锚点，但缺少统一生命周期模型。

目标：

1. 核心目录对象引入 `lifecycle_status`（`ACTIVE / ARCHIVED`）
2. 删除操作改为归档（`ARCHIVED`），主视图默认过滤
3. 提供归档视图、恢复入口
4. 记录归档原因、操作人、操作时间（复用已落地的 platform event 审计账本）
5. 归档对象的引用关系保留，但不参与新的绑定和发布
6. 彻底清理作为独立管理员操作，需二次确认

### 2.4 长 Session 历史分页与派生视图

现状：`GET /api/session-runtime/sessions/{sessionId}` 直接返回完整 `events + playbookRuns`，运行页直接展示完整时间线。session 历史增长后，整量返回 detail 会越来越重；当前没有按 event type、owner、playbook run 的过滤与聚合能力。

下一步目标：

1. 为 session event 增加分页、过滤和按类型聚合查询
2. 为 playbook run 增加按状态、waiting reason 的快捷筛选
3. 在 UI 中补“仅看用户可见消息 / 仅看系统事件 / 仅看 playbook 事件”视图

### 2.5 Catalog 治理增量

> 这部分原本散落在 `docs/todo/catalog_todo.md`，主线工作已完成，仅保留下列增量。

1. 把资源版本删除、知识发布版本删除也纳入统一 `DeletionImpactPreview` 能力
2. 在批量删除场景直接复用当前预览结构做批量聚合
3. 资源治理：补齐版本 diff 与变更摘要、强化“资源变更影响哪些已发布助手”的可视化、为后续批量发布 / 归档 / 替换资源锚点预留入口
4. 知识库：在当前稳定 source/job/document/snapshot 链路上，补检索治理能力（召回评估、对比测试、命中质量分析）

---

## 阶段三：生产加固（Production Hardening）

> 目标：让系统可以部署到真实环境并稳定运行。

### 3.1 部署配套补齐

现状：

- 五个应用（api / worker / web / agent-runtime / knowledge-service）均已具备 Dockerfile（多阶段构建）
- API 已暴露 `/api/system/health`
- 本地与开发服务器依赖通过 `deploy/local`、`deploy/dev` 提供 docker-compose 编排，公共初始化脚本与网关配置放在 `deploy/common`

待补：

1. 编写生产级 docker-compose（所有应用 + 依赖一键启动）
2. 编写 Kubernetes manifests（Deployment / Service / ConfigMap / Secret / Ingress）
3. 完善 readiness / liveness 区分，Python 服务补健康端点
4. 各服务统一 graceful shutdown（Spring `server.shutdown=graceful`、FastAPI lifespan、Temporal worker drain）

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

依赖：结构化日志已就绪，OTel 日志 bridge 可直接复用现有上下文字段。

### 3.3 数据安全与备份

目标：

1. PostgreSQL：配置 WAL archiving + 定期 pg_dump，至少支持 point-in-time recovery
2. S3-compatible object storage：配置 bucket versioning + 定期 mirror 到备份存储
3. pgvector：知识索引可重建，备份优先级低于 PostgreSQL
4. Temporal：使用 Temporal 自带的 visibility store，不额外备份
5. 敏感配置（数据库密码、API key、OIDC secret）统一走 K8s Secret 或 Vault，不在代码/配置文件中明文存储

### 3.4 性能基线与优化

目标：

1. API 分页审计：确保所有列表接口支持分页，数据库查询有索引（与 §2.4 协同）
2. 连接池配置：HikariCP（Java）、SQLAlchemy pool（Python）参数调优
3. 前端：继续优化路由级代码分割（Vue Router 已引入，控制台菜单页已完成真实路由）
4. Knowledge Service：基于 `PostgreSQL + pgvector + pg_trgm + tsvector` 持续优化检索质量与索引性能
   - 当前方案保留 `LEXICAL / VECTOR / HYBRID` 三种模式，embedding 由 OpenAI-compatible `/embeddings` 提供
   - 关注大规模 chunk 量级下的索引参数、查询延迟与 embedding 成本
5. 缓存策略：catalog 热数据（发布快照）考虑 Redis 共享缓存；失效协议已由 §3.6 的 `RedisInvalidationBus` 提供，待出现真实热点再铺开

### 3.5 安全加固

目标：

1. API 输入校验全覆盖（Spring Validation 已引入，确保无遗漏）
2. CORS 收紧为生产域名白名单
3. Rate limiting：API 层引入基础限流（Bucket4j + Redis backend，沿用 §3.6 共享能力层）
4. SQL 注入防护审计（当前 JDBC template 需确认参数化查询无遗漏）
5. 前端 XSS 防护：确保用户输入内容展示时经过转义
6. Webhook 签名校验（§2.2 external interaction 依赖）
7. 文件上传安全：类型白名单、大小限制、病毒扫描（可选）

> 本节聚焦“数据入口”防护。LLM 上下文“数据出口”的隐私脱敏（防止 PII 流向公网 LLM）已由隐私映射层交付，详见末尾「已完成工作回顾」与 `docs/develop_record/privacy_mapping_plan.md`。

### 3.6 多实例部署一致性与共享状态

状态（截至 2026-04-21）：shared-state refactor 已将多实例主干能力全面落地，详细盘点与剩余工作见 `docs/develop_record/multi_instance_plan.md`。

已完成的主干能力：

- 共享模块 `packages/shared-redis-jvm`：统一 keyspace、JSON codec、Pub/Sub bus、分布式锁
- API：`spring-session-data-redis` 接入 + `SessionRedisConfiguration`；`RedisIdempotencyService`、`RedisInvalidationBus`、`SharedStateInvalidationSubscriber` 收口
- `SessionDispatchLockService` 从进程内 `ReentrantLock` 切到 `RedisLockService`
- Session Runtime 跨实例流：`SessionRuntimeStreamService` + `SessionRuntimeReplayStore` + `SessionRuntimeChangeNoticePublisher`（API）与 `SessionRuntimeChangePublisher`（worker）通过 Redis Pub/Sub 协同；Web 侧 `EventSource` 已接入，轮询作为 fallback
- `CatalogService` / `KnowledgeService` 去 JVM 内存镜像，切到 repository-first 读写；发布 / 更新通过 `RedisInvalidationBus` 广播失效
- `external-callback` 幂等闭环：显式 `Idempotency-Key` 优先，缺失时由服务端按 `sessionId + playbookRunId + payload` 派生稳定 key；Web 侧 helper 与 OpenAPI 描述已对齐
- API 双实例语义测试已建立：共享登录态、共享幂等键、分布式 session 锁、SSE 跨实例 broadcast / replay 已有自动化覆盖

仍未闭环：

1. **分布式限流**：尚未引入 Bucket4j 或同级方案（与 §3.5 合并推进）
2. **幂等覆盖面**：`external-callback` 已完成稳定 key 规范与跨实例验证；剩余缺口集中在真实 webhook 与高风险控制面写接口的体系化接入（依赖 §2.2 第一个真实 provider）
3. **多实例专项测试**：API 双实例核心语义已覆盖；双 Worker 环境、Redis / API / Worker 故障注入与恢复验证仍未建立
4. **生产交付面**：生产级 compose / K8s manifest、TLS、Secret 注入策略仍待补齐（与 §3.1 合并）

依赖：与 §3.1（部署配套）、§3.4（缓存）、§3.5（限流）协同；§4.4 多租户隔离也在多实例验证闭环后才有规模化意义。

---

## 阶段四：平台演进（Platform Evolution）

> 目标：从单租户单实例演进为可规模化运营的平台。当前阶段规划即可，不急于实现。

### 4.1 RBAC 细粒度权限

> 当前已实现 `PLATFORM_ADMIN / DOMAIN_ADMIN / DEVELOPER / BUSINESS_USER` 四角色粗粒度模型。下一步演进：

1. 扩展角色体系：自定义角色
2. 对象级权限：谁能编辑哪个 domain / assistant
3. 操作级权限：谁能发布、谁能删除
4. 权限 UI：角色管理、成员管理、权限分配

### 4.2 资源治理增强

1. 版本 diff 与变更摘要
2. “资源变更影响哪些已发布助手”可视化
3. 批量操作：批量发布、批量归档、批量替换资源锚点
4. 资源版本和知识发布版本的删除预览

### 4.3 灰度发布与回滚

1. 发布支持灰度策略：按比例、按标签、按用户分组
2. 发布支持一键回滚到上一个快照
3. 发布影响预估：新快照与当前快照的 diff 分析

### 4.4 多租户隔离

1. 数据层：schema 级或 row 级租户隔离
2. 运行态：Temporal namespace 级隔离
3. 资源层：对象存储 bucket 级隔离
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

- Vue Router 已引入，`/login` 与 `/console/...` 以及控制台菜单页均已具备真实路由
- 当前已支持：浏览器前进后退、URL 直接访问菜单页、路由级懒加载
- 后续待补：对象级深链能力，如 session / playbook / knowledge / resource 的选中对象通过 URL 直达与恢复

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
阶段二：运行态成熟
  2.1 恢复链路产品化
       └── 2.2 External Interaction 第二步 ◄── 2.1
  2.3 软删除 ◄──── 已就绪的 platform event 审计账本（归档事件记录）
  2.4 长 Session 分页
  2.5 Catalog 治理增量

阶段三：生产加固
  3.1 部署配套补齐
  3.2 OTel 观测 ◄──── 已就绪的结构化日志
  3.3 数据安全
  3.4 性能优化 ◄──── 与 2.4 协同
  3.5 安全加固 ◄──── 已就绪的请求级鉴权 + 2.2 webhook 签名校验
  3.6 多实例一致性剩余项 ◄──── 3.1 / 3.4 / 3.5 协同（Redis 基座已就绪）

阶段四：平台演进
  4.1 RBAC 细粒度 ◄──── 已就绪的粗粒度 RBAC
  4.2 资源治理增强
  4.3 灰度发布 ◄──── 2.3（需要快照 diff）
  4.4 多租户 ◄──── 已就绪的鉴权 + 3.1 + 3.6（复用共享状态基座与映射策略下发通道）
  4.5 成本与 SLO ◄──── 3.2（需要 metrics）
  4.6 知识检索质量
```

---

## 已完成工作回顾（仅作历史索引，不再改动）

详细过程留档见 `docs/develop_record/`。本次清理移除以下已完成的主线条目：

- [x] 阶段一 1.1 真实身份与请求级鉴权：OIDC 登录流、API auth filter、前端登录页与托管会话、跨服务 internal token、四角色粗粒度 RBAC（`PLATFORM_ADMIN / DOMAIN_ADMIN / DEVELOPER / BUSINESS_USER`）
- [x] 阶段一 当前 1.1 CI 流水线：新增单文件 GitHub Actions workflow，并行执行 Java `./gradlew check`、Node `pnpm install && pnpm lint && pnpm build`、Python `uv sync --all-packages && uv run --all-packages pytest`
- [x] 阶段一 1.2 关键路径测试：编排图校验器单测、发布快照冻结逻辑单测、Session/Playbook Workflow 集成测试、Agent Runtime 单轮推理单测、Knowledge Service 导入链路单测
- [x] 阶段一 1.4 结构化日志统一：Java/Python 服务统一结构化输出，统一透传 trace / session / workflow / customer / user 上下文
- [x] 阶段二 旧 2.1 平台事件日志：`platform_event` 审计账本、`GET /api/events` 分页查询、控制台对象详情页「操作历史」面板、`session_runtime_event / playbook_run` 纳入统一审计边界
- [x] 阶段二 旧 2.2 SSE 跨实例推送：`GET /api/session-runtime/sessions/{sessionId}/stream`、`SESSION_SNAPSHOT / SESSION_UPDATED` 契约、Redis replay buffer、Web `EventSource` 订阅（失败降级回轮询）
- [x] 阶段二 2.4 草稿默认模型策略收敛：`defaultModelResourceId` 显式化、发布前阻断校验、发布快照新增 `defaultModelBinding`、Runtime 预检对齐
- [x] 阶段二 2.5 第一步 External Interaction 通用框架：playbook `EXTERNAL_INTERACTION` 节点、`external-callback` Signal、`waitingReason` 权威校验、回调恢复回流 owner
- [x] 阶段三 旧 3.6 隐私脱敏映射层：assistant / agent 配置、release freeze、Redis session map、运行态出站脱敏 / 入站还原、审计与运行页统计
- [x] 阶段三 3.6（多实例）主干：共享模块 `packages/shared-redis-jvm`（keyspace / codec / pub-sub / lock）、API 接入 `spring-session-data-redis` + `RedisIdempotencyService` + `RedisInvalidationBus`、`SessionDispatchLockService` 改走分布式锁、Session Runtime 跨实例流（`SessionRuntimeStreamService / ReplayStore / ChangeNoticePublisher` + worker `SessionRuntimeChangePublisher`）、`CatalogService / KnowledgeService` 去 JVM 内存镜像并接入跨实例失效
- [x] 阶段三 3.1 部分：五个应用 Dockerfile（多阶段构建）与 `/api/system/health` 健康端点
- [x] 历史 P0/P1/P2/P3 工作（详见 `docs/develop_record/2026-03-project_todos_snapshot.md`）：
  - P0.1 运行态持久化：session / session_event / playbook_run 落库
  - P0.2 同步等待改异步 + 轮询观测闭环
  - P1.1 结构化决策契约对齐
  - P1.2 Workflow 失败可观测性：结构化错误码 + root cause
  - P1.3 JSONB catalog store 关键路径关系模型化
  - P1.4 主链路移除 demo/seed
  - P2.1 统一对象级引用分析接口
  - P2.2 删除前影响预览
  - P2.3 知识库真实导入与检索链路
  - P2.4 身份与权限体系 Phase 1
  - P3.1 前端大文件拆分
  - P3.3 Python 服务共享依赖管理（uv workspace）
