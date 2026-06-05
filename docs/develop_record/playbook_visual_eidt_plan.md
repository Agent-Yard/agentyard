## Playbook 可视化编辑实现方案

### 摘要
将当前 `Playbook` 页从 `nodesJson / edgesJson` 文本编辑，重构为 `左侧节点面板 + 中间 DAG 画布 + 右侧 Inspector` 的可视化编排台，整体布局和视觉语言对齐 `ui-sample/pages/playbook.jsx`。  
v1 按已锁定方向实现为“完整编辑器”：支持节点新增/删除、画布拖拽排布、边的新增/删除/条件编辑、节点属性编辑和保存；连线编辑采用 `Inspector 面板式`，不做拖线建边。实现基于现有 Vue + SVG + 已存在的 `graph-editor` 样式基元，不引入新的流程图库。

### 关键变更
#### 1. 契约与数据模型
- 为 `PlaybookNode` 增加显式布局字段：
  - 新增 `layout: { x: number; y: number }`
  - 该字段进入 TS 类型、OpenAPI、JVM DTO、前后端请求/响应模型
- 保持节点类型模型不变，继续使用：
  - `STEP`
  - `TOOL_TASK`
  - `HUMAN_TASK`
  - `EXTERNAL_INTERACTION`
  - `END`
- UI 仅做命名和配色增强，不改后端类型语义
- 同步更新创建/更新 Playbook 请求：
  - `CreatePlaybookRequest`
  - `UpdatePlaybookRequest`
  - `CreatePlaybookPayload`
  - `UpdatePlaybookPayload`
- 后端校验补强到可视化编辑场景：
  - `nodeKey` 唯一
  - `edgeKey` 唯一
  - `entryNodeKey` 必须存在
  - 边的 source/target 必须存在
  - `STEP` 必须有 `scriptRef + scriptVersion`
  - `TOOL_TASK` 必须有 `toolId + toolOperation`
  - `allowHumanTask=false` 时禁止 `HUMAN_TASK`
  - `allowExternalInteraction=false` 时禁止 `EXTERNAL_INTERACTION`

#### 2. 前端编辑器形态
- 将 `Playbook` 主编辑区改成三栏：
  - 左栏：节点类型 palette + 当前图统计 + 快捷创建入口
  - 中栏：DAG 画布，显示节点卡片、连线、route/label 芯片
  - 右栏：Inspector，按当前选中的节点或边显示编辑表单
- 画布能力：
  - 节点按 `layout.x / layout.y` 定位
  - 支持拖拽节点并实时更新布局
  - 支持选中节点/边
  - 空白处点击取消选中
- 节点新增：
  - 从左侧 palette 点选类型后创建
  - 新节点默认落在当前视口中心附近
  - 自动生成 `nodeKey`、默认 `nodeName`
- 节点删除：
  - 在 Inspector 或节点操作区触发
  - 同步删除关联边
  - 若删除的是 entry 节点，则要求先切换 entry 或自动回退到首个剩余节点
- 边编辑采用面板式：
  - 选中 source 节点后在 Inspector 中新增出边
  - 新增边时明确选择 target、`routeKey`、`label`、`defaultEdge`
  - 选中边后可修改或删除
  - 不实现拖线创建/改线
- Inspector 分层：
  - 所有节点通用字段：`nodeKey`、`nodeName`、`description`
  - `STEP`：`scriptRef`、`scriptVersion`、`config`
  - `TOOL_TASK`：`toolId`、`toolOperation`、`config`
  - `HUMAN_TASK` / `EXTERNAL_INTERACTION` / `END`：
    - 先只做通用字段 + `config` JSON 编辑
    - 不凭空发明结构化子表单
- `config` 继续保留原始 JSON 编辑入口，但只作为节点内部高级字段，不再作为整图主编辑方式

#### 3. Playbook 页与创建流
- 主页面编辑：
  - 当前 Playbook 的元数据表单保留，但图定义部分替换为可视化编辑器
  - `保存 Playbook` 时直接把当前画布状态序列化回 `nodes + edges + entryNodeKey`
- 创建 Drawer 收口为“轻创建”：
  - 保留 `assistantId`、名称、描述、schema、execution policy、能力开关
  - 不在创建抽屉里放完整图编辑器
  - 创建时自动生成 starter graph：
    - `start` 节点：`STEP`
    - `finish` 节点：`END`
    - 一条默认边
    - 两个节点带默认 `layout`
- 保留高级 JSON 工具，但降级为辅助能力：
  - 提供 `导入 JSON / 导出 JSON` 的次级 modal 或工具入口
  - 不再把整图 JSON textarea 暴露在主界面
- 使用已有 `graph-editor` / `graph-node` / `graph-edge-chip` 样式基元，对齐 `ui-sample` 的画布、节点卡、Inspector 气质

#### 4. 状态与交互实现
- 在前端内部新增 Playbook editor state：
  - `draftNodes`
  - `draftEdges`
  - `selectedNodeKey`
  - `selectedEdgeKey`
  - `isDirty`
  - `viewport/pan`（如需要，只做最小支持）
- 提供纯前端转换层：
  - `Playbook <-> EditorDraft`
  - 负责节点排序、默认 layout 注入、序列化清洗
- 提供前端保存前校验：
  - 先做本地校验并给出友好错误
  - 再调用现有保存 API
- 不引入新的第三方图库；直接使用现有依赖和原生 SVG/绝对定位实现

### 公共接口与类型变更
- `PlaybookNode`
  - 新增 `layout: { x: number; y: number }`
- Playbook 相关创建/更新请求
  - `nodes` 内节点结构同步带 `layout`
- OpenAPI
  - `PlaybookNode` schema 增加 `layout`
  - 新增 `PlaybookNodeLayout` schema
- JVM / TS 合同层
  - 对应 record/interface 同步新增 `layout`

### 测试方案
- 前端单测：
  - `Playbook -> EditorDraft -> Playbook` 往返序列化正确
  - 新增节点生成默认 key / 默认 layout / 默认字段正确
  - 删除节点会同步清理关联边
  - 本地校验能拦截缺失 `scriptRef`、非法 entry、断裂边
- 前端页面测试：
  - 选中节点后 Inspector 正确切换
  - 修改节点字段后保存 payload 正确
  - 新建边、删除边后保存 payload 正确
  - 创建 Drawer 生成 starter graph
- 后端测试：
  - Playbook create/update 可接受并返回 `layout`
  - 新校验规则覆盖 `STEP`、`TOOL_TASK`、非法边、非法 entry、禁用节点类型
- 集成验证：
  - `pnpm --filter @agentyard/web build`
  - `pnpm --filter @agentyard/web test`
  - API 相关现有 catalog 测试补 Playbook layout/validation 场景

### 假设与默认值
- 不引入 `vue-flow`、`x6` 等图库，直接用当前栈实现
- v1 连线编辑固定走 Inspector 面板，不做拖线交互
- v1 继续保留 JSON 导入/导出，但只作为高级辅助工具，不再是主编辑方式
- `HUMAN_TASK`、`EXTERNAL_INTERACTION` 的业务细项先继续落在 `config` 中，不额外扩展后端结构化字段
- 创建 Playbook 时默认生成一个最小可保存的 starter graph，而不是在创建抽屉里完成完整编排
