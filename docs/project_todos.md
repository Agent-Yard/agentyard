
## Lynxus 全量工作项优先级排序

### P0 — 阻碍主链可靠性，必须最先做

| # | 工作项 | 来源 | 理由 |
|---|--------|------|------|
| **0.1 已完成** | **运行态持久化：session/task/workflow/humanIntervention 落库** | runtime_todo §4 | 已完成：runtime 主投影已落 PostgreSQL，API 启动会主动对账非终态 Temporal workflow，控制台不再依赖单进程内存视图。 |
| **0.2 已完成** | **同步等待改异步 + 轮询观测闭环** | runtime_todo §1 | 已完成：`sendMessage / launchTask / human-action` 已改为立即返回已受理投影，前端统一依赖 workflow 轮询收口，首结果超时不再作为产品语义。 |

---

### P1 — 影响工程质量和跨服务协作，紧随其后

| # | 工作项 | 来源 | 理由 |
|---|--------|------|------|
| **1.1 已完成（第一波）** | **结构化决策契约对齐（Python → contracts → contracts-jvm → OpenAPI）** | runtime_todo §5 | 已完成第一波：共享契约、`agentTurnState` 和 workflow 观测页已打通，后续只剩更完整的审计/历史化能力。 |
| **1.2 已完成** | **workflow 失败可观测性：结构化错误码 + root cause 字段** | runtime_todo §3 | 已完成：`latestFailure` 已贯通 agent-runtime、worker、API 投影、PostgreSQL、OpenAPI 和 Web 观测页；终态失败与“错误转人工”都能按 category/code/rootCause 查询和展示。 |
| **1.3 已完成** | **JSONB catalog store 关键路径关系模型化** | review 新增 | 已完成：V6 migration 新增 4 张关系投影表（`catalog_ref_resource_binding`、`catalog_ref_knowledge_binding`、`catalog_ref_release_resource`、`catalog_ref_release_knowledge`），与 JSONB 同事务维护；读路径已切换到投影表查询（`CatalogService.listResourceReferences`、`KnowledgeService.listKnowledgeBaseReferences`），JSONB 保留为 source of truth；投影表引用分析单元测试已覆盖。 |
| **1.4 已完成** | **主链路移除 demo/seed** | runtime_todo §2 + review | 已完成：API、knowledge-service、agent-runtime 与开发态用户会话已移除内置 demo/seed 逻辑，主链路不再耦合 `demo.local` 或应用内假响应。 |

---

### P2 — 治理能力补齐，提升平台专业度

| # | 工作项 | 来源 | 理由 |
|---|--------|------|------|
| **2.1 已完成** | **统一对象级引用分析接口** | catalog_todo §1 | 已完成：新增统一对象引用分析接口，覆盖 `domain / scenario / assistant / agent / resource / knowledge_base`；资源中心与知识库旧引用接口已复用同一分析源，Web 六个治理详情页已接入统一引用分析面板，可直接作为 §2.2 删除预览和 §2.5 资源治理视图的基础。 |
| **2.2** | **删除前影响预览** | catalog_todo §2 | 依赖 §2.1。用户在点击删除前应能看到影响摘要（阻断对象、受影响快照、级联回收清单），而不是失败后才知道原因。 |
| **2.3** | **知识库真实导入与检索链路补齐** | catalog_todo §4 | 知识服务已有基础能力，但目录侧缺少稳定的导入任务模型（状态机、失败重试、进度追踪）。知识库作为一级对象，其运维模型不能比其他资源弱。 |
| **2.4** | **身份与权限体系（OIDC/IAM）** | 规划文档 | 重要但可阶段性引入。先从"替换 mock 登录 + 接入基础 OIDC"开始，不必一步到位做细粒度 RBAC。 |
| **2.5** | **资源治理视图增强（版本 diff、发布影响、批量操作）** | catalog_todo §5 | 依赖 §2.1。让"资源变更影响哪些已发布助手"可视化，是发布治理的核心体验。 |

---

### P3 — 工程化基建，越早越好但不阻碍功能

| # | 工作项 | 来源 | 理由 |
|---|--------|------|------|
| **3.1 已完成** | **前端大文件拆分（App.vue 25KB, types.ts 15KB）** | review 新增 | 已完成：`apps/web/src/types.ts` 已拆为 `catalog.types.ts`、`runtime.types.ts`、`orchestration.types.ts` 并保持 barrel re-export；`apps/web/src/App.vue` 已瘦身为薄编排层，布局、状态、轮询、catalog actions、runtime actions、page registry 已分别拆入 `layouts/`、`composables/`、`config/`。 |
| **3.2** | **测试体系建立（关键路径优先）** | review 新增 | 不需要一步到位追求覆盖率。建议从三个最关键路径开始：(1) 图校验器单测（合法/非法图）；(2) 发布快照冻结逻辑单测；(3) workflow start/signal/resume 集成测试。这三项测试的投入产出比最高。 |
| **3.3 已完成** | **Python 服务共享依赖管理** | review 新增 | 已完成：根目录已引入 `uv` workspace，`agent-runtime` 与 `knowledge-service` 改为各自 `pyproject.toml` + 根级共享约束，开发脚本统一走 `uv run`，旧 `requirements.txt` 安装路径已移除。 |
| **3.4** | **CI 基础设施** | 规划文档 | 有了 §3.2 的测试后，补上 CI 流水线（lint + type check + 关键测试），防止回归。 |

---

### P4 — 生产化深水区，当前阶段规划即可

| # | 工作项 | 来源 | 理由 |
|---|--------|------|------|
| **4.1** | **软删除、归档与生命周期治理** | catalog_todo §3 | 核心对象引入 `ACTIVE / ARCHIVED` 状态，归档视图、恢复入口、删除原因记录。重要的治理能力但不紧急。 |
| **4.2** | **基础设施职责收敛（OpenSearch / MinIO / Redis）** | 规划文档 | 明确每个依赖在正式架构中的唯一职责，建立监控、备份、容量规划。可以考虑 pgvector 替代 OpenSearch 简化早期运维。 |
| **4.3** | **OpenTelemetry 统一观测** | 技术路线 | 指标/日志/Trace 统一关联，需要在 §0.1 持久化和 §1.2 错误码之后才有意义。 |
| **4.4** | **灰度发布、回滚、分批发布** | 技术路线 | 发布治理的高级能力，依赖 §2.5 资源治理视图和稳定的快照模型。 |
| **4.5** | **多租户治理** | project_description | 长期目标，当前单租户边界已预留模型空间，不急。 |
| **4.6** | **成本治理与 SLO** | project_description | 需要运行态持久化 + 观测体系成熟后才有数据基础。 |

---

### 依赖关系总览

```
P0.1 运行态持久化（已完成）
 ├── P0.2 异步观测 (需要持久化作为数据源)
 ├── P1.2 结构化错误码 (需要有字段可以持久化)
 └── P4.3 OpenTelemetry (需要持久化 + 错误码)

P1.3 关系模型化（已完成）
 └── P2.1 统一引用分析 (需要高效 JOIN)
      ├── P2.2 删除前影响预览
      └── P2.5 资源治理视图增强

P1.1 契约对齐
 └── P3.2 测试体系 (契约稳定后测试才不会频繁重写)

P3.2 测试体系
 └── P3.4 CI (有测试才有 CI 的意义)
```
