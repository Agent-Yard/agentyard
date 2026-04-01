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

现状：各服务日志格式不统一，不利于排障。

目标：

1. Java 服务统一 JSON 日志格式（Logback JSON encoder），包含 `traceId / spanId / service / level / message`
2. Python 服务统一 structlog 或 python-json-logger
3. 约定公共字段：`service`、`traceId`、`sessionId`、`workflowId`、`userId`
4. 本地开发仍输出可读格式，通过 profile 切换

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

现状：`HumanAction` 是唯一的恢复信号，external interaction 需要伪装成人工操作。

目标：

1. 将 `HumanAction` 泛化为 `ResumeAction`，包含 `source`（`HUMAN / EXTERNAL_SYSTEM / TIMEOUT_POLICY`）
2. `PauseReason` 扩展 `EXTERNAL_INTERACTION_REQUIRED`
3. Workflow checkpoint 记录 `resumeContext`（包含 interactionTaskId 等）
4. 保持向后兼容：纯人工恢复仍可用原有接口，内部映射为 `ResumeAction(source=HUMAN)`

为什么提前做：这是 external interaction（§2.5）的前置依赖，也让 workflow 模型更准确。

### 2.4 草稿默认模型策略收敛

现状：草稿默认模型是"取第一个可用 LLM"的隐式策略。

目标：

1. Assistant 草稿增加显式 `defaultModelResourceId` 字段
2. 无默认模型时，控制台给出明确引导而非静默回退
3. 发布快照中区分：草稿配置的模型 vs 冻结时实际绑定的模型 vs 运行时命中的模型
4. 控制台在助手详情页明确展示三层模型语义

### 2.5 External Interaction 一等能力

现状：已有详细设计方案（`docs/todo/external_interaction_plan.md`），未实现。

目标（分两步）：

**第一步 — 通用框架：**

1. 共享契约引入 `ExternalInteractionType / Status / Task / Result`
2. `ConversationMessage` 扩展 `payloadType + payload` 结构化载荷
3. RuntimeService 新增 interaction task CRUD 和状态机
4. Workflow 使用泛化后的 `ResumeAction` 处理 external interaction 完成
5. 前端支持 interaction 卡片渲染、回跳参数解析、状态轮询
6. 持久化：`external_interaction_task` + `external_interaction_event` 表

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
3. OpenSearch / pgvector：知识索引可重建，备份优先级低于 PostgreSQL
4. Temporal：使用 Temporal 自带的 visibility store，不额外备份
5. 敏感配置（数据库密码、API key、OIDC secret）统一走 K8s Secret 或 Vault，不在代码/配置文件中明文存储

### 3.4 性能基线与优化

目标：

1. API 分页审计：确保所有列表接口支持分页，数据库查询有索引
2. 连接池配置：HikariCP（Java）、SQLAlchemy pool（Python）参数调优
3. 前端：路由级代码分割（当前单页无路由，需引入 Vue Router）
4. Knowledge Service：评估 pgvector 替代 OpenSearch
   - 优势：减少一个基础设施依赖，运维复杂度大幅降低
   - 劣势：大规模向量检索性能不如 OpenSearch
   - 建议：当前阶段（<100 万文档）pgvector 足够，后续按需切换
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

### A. pgvector 替代 OpenSearch（建议在阶段三评估）

- 当前 OpenSearch 只用于知识库向量检索，维护成本高
- pgvector 与现有 PostgreSQL 共享运维，减少一个基础设施组件
- 对早期规模（<100 万文档 chunks）完全够用
- 迁移路径：Knowledge Service 的 index/search 接口抽象不变，只换底层实现

### B. Vue Router 引入（建议在阶段三）

- 当前页面切换由 App.vue 的 activeKey 管理，无真实路由
- 不利于：浏览器前进后退、URL 直接访问、代码分割、SSE 回跳
- 引入 Vue Router 后可支持路由级懒加载，减小首屏体积

### C. 消息 payload 结构化（阶段二 §2.5 前置）

- 当前 ConversationMessage 只有 content 文本字段
- External interaction、rich card、code block 等都需要结构化载荷
- 建议尽早引入 `payloadType + payload` 模式，避免在 content 中塞 JSON

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
