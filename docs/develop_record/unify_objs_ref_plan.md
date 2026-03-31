# P2.1 统一对象级引用分析

## Summary
目标是把当前散落在删除逻辑、资源中心、知识库页中的引用判断，收敛成一套统一的对象级引用分析能力，覆盖 `DOMAIN / SCENARIO / ASSISTANT / AGENT / RESOURCE / KNOWLEDGE_BASE`，并在 Web 各对象详情页提供统一入口。  
本轮不做删除预检弹窗本身，但返回结构必须能被 `P2.2` 直接复用；不做局部补丁，直接把 resource/knowledge 现有能力并入统一协议。

## Key Changes
- 后端新增统一引用分析模型，至少包含：
  - `objectType`、`objectId`、`objectName`
  - `relations[]`
  - 每条 relation 含 `relationKind`、`relationRole`、`targetType`、`targetId`、`targetName`
  - 语义字段固定为 `DIRECT / INDIRECT`、`BLOCKS_DELETION / ADVISORY`
  - 可选上下文字段用于发布冻结/版本锚点：如 `releaseId`、`releaseVersion`、`resourceVersionId`、`resourceVersion`
- API 统一为对象分析入口，不再继续扩散专用只读接口：
  - 推荐新增 `GET /api/catalog/references/{objectType}/{objectId}`
  - 返回单对象完整分析结果
  - 现有 `resource-center` 保留统计用途，但其引用列表改为复用统一服务生成
  - 现有 `GET /knowledge-bases/{id}/references` 改为复用统一服务；若保留旧路由，仅作兼容壳层并返回同构数据
- 服务层新增统一引用分析服务，集中生成所有对象的下游关系：
  - `DOMAIN` 直接关系：场景、资源、知识库；这些关系全部 `DIRECT + BLOCKS_DELETION`
  - `SCENARIO` 直接关系：助手；全部 `DIRECT + BLOCKS_DELETION`
  - `ASSISTANT` 直接关系：智能体、助手私有资源、助手私有知识库、编排；发布快照为 `DIRECT + ADVISORY` 或 `INDIRECT + BLOCKS_DELETION`，按当前删除语义定死
  - `AGENT` 直接关系：模型覆盖、技能、工具、知识库覆盖；被助手编排节点引用、被已发布助手快照冻结引用
  - `RESOURCE` 沿用现有 active binding + release frozen，但映射到统一 relation 结构
  - `KNOWLEDGE_BASE` 沿用现有 binding + release frozen + effective release 阻断语义，也映射到统一 relation 结构
- 删除逻辑不再各自拼字符串判断：
  - `deleteDomain/deleteScenario/deleteAssistant/deleteAgent/deleteResource/deleteKnowledgeBase/delete*Release`
  - 改为先调用统一分析或统一 blocker 提取逻辑
  - 错误消息由统一 relation 到文案映射生成，保证“分析面板看到什么，删除失败就为什么”
- Repository/投影层按全局视角补齐查询能力：
  - 继续复用现有 4 张引用投影表作为 resource/knowledge 的高效来源
  - 对 `domain/scenario/assistant/agent` 不新增无意义投影表，优先基于现有 catalog 主表和编排/发布快照做聚合查询
  - 如 `assistant -> orchestration`、`agent -> orchestration node`、`assistant/agent -> release frozen` 的关系无法高效从现有结构稳定提取，再补最小必要查询封装，不先设计新表
- 公共契约同步更新：
  - `apps/api` DTO
  - `packages/contracts/openapi/control-plane.yaml`
  - `packages/contracts/src/index.ts` 增加统一引用分析类型
  - `apps/web/src/types/catalog.types.ts` 直接复用或薄包装新共享类型，不再新增第三套本地结构
- Web 统一详情入口：
  - 抽一个通用引用分析面板组件，接受 `objectType/objectId`
  - `DomainPage`、`ScenarioPage`、`AssistantPage`、`AgentPage`、`ResourceLibraryPage`、`KnowledgeLibraryPage` 都接入
  - Resource/Knowledge 页面从各自专用列表切到统一面板
  - 先做详情侧展示，不在本轮引入批量操作和删除预览弹窗
- 文档同步更新：
  - `docs/project_todos.md` 将 `2.1` 标为已完成并注明能力边界
  - `docs/todo/catalog_todo.md` 把“统一对象级引用分析”现状改为已落地，并注明 `P2.2/P2.5` 直接复用该协议

## Public Interfaces
- 新增共享类型：
  - `ReferenceObjectType`
  - `ReferenceRelationMode`
  - `ReferenceImpactLevel`
  - `ObjectReferenceRelation`
  - `ObjectReferenceAnalysis`
- 新增 API：
  - `GET /api/catalog/references/{objectType}/{objectId}`
- 旧接口处理：
  - `resource-center` 继续存在，但引用明细由统一结构派生
  - `knowledge-bases/{id}/references` 若保留，则内部调用统一分析并转换为兼容壳层；若实现时确认前端已全部迁移，可直接删除并同步清理调用点与 OpenAPI

## Test Plan
- API/服务单测覆盖：
  - `DOMAIN` 返回场景/资源/知识库关系，并标记阻断删除
  - `SCENARIO` 返回助手关系，并标记阻断删除
  - `ASSISTANT` 返回智能体、私有资源、私有知识库、编排、发布快照关系
  - `AGENT` 返回模型/技能/工具/知识库/编排/发布冻结关系
  - `RESOURCE` 返回 active binding 与 release frozen，且 blocker 与删除行为一致
  - `KNOWLEDGE_BASE` 返回 active binding、release frozen、published release blocker，且与删除行为一致
- 回归测试：
  - 现有 `CatalogReferenceProjectionTest` 继续保留并扩展到统一服务层
  - 为 `CatalogService`/`KnowledgeService` 新增删除阻断一致性测试，断言统一分析结果与 delete 异常一致
  - 若保留旧知识库引用接口，补一个“旧接口结果来自统一服务”的测试
- Web 场景验证：
  - 六个详情页都能加载统一引用面板
  - 空引用对象显示空态
  - 阻断关系与提示关系视觉区分
  - 资源/知识库页迁移后功能无回退

## Assumptions
- 本轮按你已确认的范围执行：知识库并入统一协议，前端提供统一详情入口。
- `P2.1` 只统一分析能力和展示入口，不实现删除前确认弹窗和批量分析接口；但返回结构必须为二者预留。
- 不为 `domain/scenario/assistant/agent` 先行引入新关系投影表，只有在现有主表/快照查询无法稳定支持时才补最小必要持久化结构。
- 目录侧新公共协议进入 `packages/contracts`，但不借机把全部 catalog DTO 一次性迁入共享包，只迁本轮新增的引用分析模型。
