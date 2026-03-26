# 知识库独立域改造执行计划

## Summary
本次改造按“完全独立、分阶段落地”执行：把知识库从通用 `ResourceType` 中移出，形成独立一级菜单、独立领域对象、独立 API 边界和独立运行时契约；`Resource` 仅保留 `Tool / LLM_MODEL / SKILL`。  
智能体在草稿态关联 `KnowledgeBase`，助手发布时统一冻结为 `KnowledgeRelease`，运行时只消费冻结后的 `KnowledgeBindingSnapshot`，不再把知识库伪装成通用资源版本。

目标落地后的主链路为：  
`KnowledgeBase -> ImportJob / File / Document / Chunk -> Snapshot -> Release -> AssistantRelease / AgentRuntimeBinding`

## Key Changes

### 1. 目标信息架构与领域边界
1. 控制台一级菜单调整为：`平台设计 / 助手构建 / 知识库 / 能力资源 / 运行与观测`。
2. `知识库` 一级下提供两个二级页：
   - `知识库目录`：左侧知识库列表，右侧知识库工作台。
   - `知识库新建`：只负责创建知识库头信息。
3. `知识库目录` 工作台内统一使用页签承载：
   - `概览`
   - `内容导入`
   - `文档`
   - `索引快照`
   - `发布版本`
   - `引用分析`
4. 现有 `资源目录 / 资源新建` 仅保留 `Tool / LLM Model / Skill`，不再出现 `KNOWLEDGE_BASE` 分支。
5. 后端模块边界调整为：
   - `catalog`：业务域、场景、助手、智能体、非知识资源
   - `knowledge`：知识库头、知识发布、知识引用分析、知识服务聚合、知识工作流网关
   - `knowledge-service`：文件、导入、文档、chunk、快照、检索

### 2. 新的领域模型
1. 新增控制面对象：
   - `KnowledgeBase`
   - `KnowledgeRelease`
   - `KnowledgeReference`
   - `KnowledgeBindingDraft`
   - `KnowledgeBindingSnapshot`
2. `KnowledgeBase` 作为治理对象保留现有平台治理字段：
   - `id / domainId / name / summary / steward / tags / ownerType / ownerId / shareScope`
3. `KnowledgeRelease` 作为业务发布锚点，字段固定为：
   - `id / knowledgeBaseId / version / status(DRAFT|PUBLISHED) / summary / snapshotId / retrievalProfile / createdAt / publishedAt`
4. `retrievalProfile` 固定包含：
   - `defaultTopK`
   - `retrievalMode`
   - `minScore`
5. `Snapshot` 继续归知识服务管理，语义是不可变索引产物，不承担业务生效语义。
6. `Chunk` 只作为知识服务内部对象，不进入控制面主 DTO；控制面只展示 `documentCount / chunkCount` 等摘要。
7. `ResourceType` 收缩为：
   - `TOOL`
   - `LLM_MODEL`
   - `SKILL`
8. 删除所有基于 `ResourceType.KNOWLEDGE_BASE` 的通用资源分支、默认配置、蓝图和摘要逻辑。

### 3. 助手与智能体的知识绑定模型
1. 助手草稿态新增默认知识策略：
   - `enabled`
   - `knowledgeBaseId`
2. 智能体草稿态新增知识策略：
   - `inheritAssistantKnowledge`
   - `ragEnabled`
   - `knowledgeBaseId`
3. 草稿态只允许绑定 `KnowledgeBase`，不允许直接绑定 `Snapshot` 或 `KnowledgeRelease`。
4. 助手发布时，控制面解析所有知识绑定：
   - 助手默认知识库解析为该知识库当前 `PUBLISHED` 的 `KnowledgeRelease`
   - 智能体若覆盖知识库，则解析其知识库当前 `PUBLISHED` 的 `KnowledgeRelease`
5. 发布冻结后的 `KnowledgeBindingSnapshot` 固定包含：
   - `knowledgeBaseId`
   - `knowledgeBaseName`
   - `knowledgeReleaseId`
   - `knowledgeReleaseVersion`
   - `snapshotId`
   - `defaultTopK`
   - `retrievalMode`
   - `minScore`
6. 若发布时某个已启用知识库没有 `PUBLISHED` release，则助手发布失败。
7. 运行时不再从通用 `resources[]` 中查找知识库，而是直接读取 `assistant default knowledge` 和 `agent knowledge binding snapshot`。

### 4. API 与契约改造
1. 新增控制面 API：
   - `GET /api/knowledge-bases`
   - `POST /api/knowledge-bases`
   - `GET /api/knowledge-bases/{knowledgeBaseId}`
   - `PUT /api/knowledge-bases/{knowledgeBaseId}`
   - `DELETE /api/knowledge-bases/{knowledgeBaseId}`
   - `GET /api/knowledge-bases/{knowledgeBaseId}/releases`
   - `POST /api/knowledge-bases/{knowledgeBaseId}/releases`
   - `PATCH /api/knowledge-bases/{knowledgeBaseId}/releases/{releaseId}/publish`
   - `DELETE /api/knowledge-bases/{knowledgeBaseId}/releases/{releaseId}`
   - `GET /api/knowledge-bases/{knowledgeBaseId}/references`
2. 新增知识工作台聚合 API：
   - `POST /api/knowledge-bases/{knowledgeBaseId}/upload-sessions`
   - `POST /api/knowledge-bases/{knowledgeBaseId}/upload-sessions/{uploadSessionId}/complete`
   - `POST /api/knowledge-bases/{knowledgeBaseId}/url-imports`
   - `GET /api/knowledge-bases/{knowledgeBaseId}/files`
   - `GET /api/knowledge-bases/{knowledgeBaseId}/import-jobs`
   - `GET /api/knowledge-bases/{knowledgeBaseId}/documents`
   - `GET /api/knowledge-bases/{knowledgeBaseId}/snapshots`
   - `POST /api/knowledge-bases/{knowledgeBaseId}/snapshots`
3. 删除旧知识聚合路径：
   - `/api/resources/{resourceId}/knowledge/*`
4. TypeScript / OpenAPI / JVM contracts 统一新增：
   - `KnowledgeBaseDto`
   - `KnowledgeReleaseDto`
   - `KnowledgeSnapshotDto`
   - `KnowledgeBindingDraftDto`
   - `KnowledgeBindingSnapshot`
5. `AssistantRunSnapshot` 契约改造：
   - `resources[]` 只保留 Tool / LLM / Skill
   - 新增 `assistantKnowledge`
   - `AgentExecutionPolicySnapshot` 新增 `knowledge`
6. `RuntimeService`、`agent-runtime`、`contracts-jvm` 同步切到新契约，彻底移除知识库经由 `ResourceVersionSnapshot` 传递的路径。

### 5. 控制面实现顺序
1. 第一阶段：契约和模型拆分
   - 新增 `knowledge` 包、DTO、服务、控制器和测试骨架
   - 新增 `KnowledgeBase / KnowledgeRelease` 存储
   - 让 `catalog` 仍可读取助手/智能体，但知识字段改为新模型
2. 第二阶段：知识工作台 API 迁移
   - `CatalogService` 中所有知识相关逻辑迁入 `knowledge` 模块
   - `KnowledgeServiceClient`、`KnowledgeWorkflowGateway` 移至 `knowledge` 模块
   - 旧 `/resources/*/knowledge/*` 入口删除
3. 第三阶段：助手发布与运行时切换
   - 助手发布逻辑改为冻结 `KnowledgeBindingSnapshot`
   - `RuntimeService` 改为传递独立知识绑定
   - `agent-runtime` 改为直接按知识绑定调用检索
4. 第四阶段：前端 IA 与页面切换
   - 新增 `KnowledgeLibraryPage`、`KnowledgeCreatePage`
   - 将现有 `ResourceLibraryPage` 中知识分支彻底移除
   - 助手/智能体配置页切到新的知识库选择器
5. 第五阶段：清理与收口
   - 删除 `KNOWLEDGE_BASE` 相关资源蓝图、默认配置、摘要组件分支、旧 mock 数据和旧文档
   - README、架构文档、OpenAPI、seed 说明同步更新

### 6. 运行时与检索链路
1. `knowledge-service` 继续拥有：
   - `UploadSession`
   - `KnowledgeFile`
   - `ImportJob`
   - `Document`
   - `Chunk`
   - `IndexSnapshot`
2. 快照构建工作流保持不变：
   - `KnowledgeImportWorkflow`
   - `KnowledgeIndexBuildWorkflow`
3. `KnowledgeRelease` 只在 API/catalog 数据库中持久化，不下沉到 knowledge-service。
4. runtime 检索入参固定改为：
   - `snapshotId`
   - `query`
   - `topK`
   - `minScore`
   - `retrievalMode`
5. runtime 的知识命中结构保持现有方向，不再经资源解析中转。
6. assistant/agent 未启用知识时，runtime 不访问知识服务。

### 7. 删除约束与治理规则
1. `KnowledgeBase` 删除阻断条件：
   - 被助手草稿默认知识策略引用
   - 被智能体草稿知识策略引用
   - 存在 `PUBLISHED` release
   - 被已发布助手冻结引用
2. `KnowledgeRelease` 删除阻断条件：
   - 自身为 `PUBLISHED`
   - 被任何助手发布快照冻结引用
3. `Snapshot` 删除阻断条件：
   - 被任何 `KnowledgeRelease` 绑定
4. `Document` 删除策略：
   - 不直接提供控制面删除；通过重新导入和新快照替代
5. 引用分析单独从 `resource-center` 拆出，形成 `knowledge references` 视图。

## Public APIs / Interfaces / Types
1. `ResourceType` 从 `TOOL | KNOWLEDGE_BASE | LLM_MODEL | SKILL` 改为 `TOOL | LLM_MODEL | SKILL`。
2. `RagPolicy`、`AgentExecutionPolicy` 中的知识字段从 `knowledgeBaseResourceId` 改为 `knowledgeBaseId`。
3. 助手发布快照新增 `AssistantKnowledgeBindingSnapshot` 与 `AgentKnowledgeBindingSnapshot`。
4. `ResourceBlueprint` 删除知识库蓝图。
5. 资源摘要、资源版本配置编辑器、资源版本配置摘要组件不再包含知识库分支。
6. OpenAPI 文档新增 `knowledge-bases` 与 `knowledge-releases` 路径，删除旧 `resources/{id}/knowledge/*`。

## Test Plan
1. API 单测与集成测试：
   - 创建知识库、更新知识库、删除知识库
   - 上传文件、URL 导入、查看导入任务、查看文档、生成快照
   - 从 `READY` 快照创建 release
   - 发布 release、切换生效 release、删除草稿 release
   - 助手发布时解析知识库到 release；无已发布 release 时失败
2. Runtime 测试：
   - 助手默认知识绑定正确冻结
   - 智能体覆盖知识库正确冻结
   - 未启用知识时不调用知识服务
   - 命中低置信度时不注入知识正文
3. Worker / knowledge-service 测试：
   - 导入成功/失败流
   - 快照构建成功/失败流
   - release 绑定的 snapshot 必须存在且为 `READY`
4. Web 测试：
   - 新菜单展示正确
   - `知识库目录` 工作台可完成上传、快照、release 发布闭环
   - 助手页、智能体页只能选择知识库，不能选择快照
   - 资源页不再出现知识库类型
5. 端到端冒烟：
   - 新建知识库 -> 导入 -> 快照 -> release 发布 -> 助手绑定 -> 助手发布 -> 对话检索命中

## Assumptions / Defaults
1. 不做兼容保留；旧知识库资源数据、旧接口、旧页面路径允许直接删除。
2. 本地开发数据库和 seed 数据允许重置，不编写旧资源到新知识模型的数据迁移脚本。
3. `KnowledgeRelease` 采用与资源版本相同的 `DRAFT / PUBLISHED` 语义，每个知识库同一时刻只允许一个生效发布版本。
4. 草稿态绑定 `KnowledgeBase`，发布态冻结 `KnowledgeRelease`，这是本次改造的固定规则。
5. `Chunk` 不进入控制面公共模型；如后续需要调试 chunk，只新增只读调试接口，不进入主工作台。
6. `knowledge-service` 仍是快照和检索唯一事实来源；API 不复制文档/chunk/snapshot 主数据，只做聚合和发布治理。
