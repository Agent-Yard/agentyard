# AgentYard 知识库导入与检索子系统两阶段实现计划

## Summary
- 新增独立 `Python FastAPI` 知识服务，专门承接文件存储、解析、切片、索引构建和检索；`apps/api` 继续负责资源治理、版本发布和页面聚合，`apps/agent-runtime` 只负责调用检索结果，不再自己扫文档文本。
- 采用“两阶段演进”。
  第一阶段先落地文件上传、结构化切片、`PostgreSQL` 过渡型词法检索、索引快照和运行时引用闭环。
  第二阶段再切 `OpenSearch`、补向量召回、融合检索、重排、URL 导入和 OCR。
- 不做兼容保留。
  直接废弃知识库版本里的 `documents[]` 设计，改为“资源版本绑定不可变 `indexSnapshotId`”。

## Key Changes
### 1. 架构与职责
- 新增独立知识服务 `apps/knowledge-service`。
  它负责 `upload / import / parse / chunk / index / retrieve` 全链路，并管理自己的 PostgreSQL 表和 MinIO bucket 前缀。
- `apps/api` 只保留知识库治理与发布语义。
  它维护知识库资源头、资源版本、资源页面聚合、发布校验，并通过内部接口调用知识服务。
- `apps/worker` 新增两个 Temporal workflow：
  `KnowledgeImportWorkflow` 负责导入与解析；
  `KnowledgeIndexBuildWorkflow` 负责快照构建与失败重试。
- `apps/agent-runtime` 把当前基于字符串列表的知识召回逻辑替换为知识服务检索适配层，返回带出处和分数的结构化命中结果。

### 2. 第一阶段交付范围
- 首版知识源只支持文件上传：`pdf / docx / md / txt / html / csv`。
- 上传流程固定为：
  Web 向 API 创建上传会话；
  API 向知识服务申请预签名上传；
  Web 直传 MinIO；
  Web 回调 API 完成上传；
  API 启动 `KnowledgeImportWorkflow`。
- 解析流程固定为：
  文件抽取正文；
  生成结构化文档树；
  写入 `knowledge_document`；
  按切片策略生成 `knowledge_chunk`；
  更新导入任务状态。
- 首版切片策略固定为结构优先切片，不做用户自定义：
  标题树优先；
  FAQ 一问一答一片；
  表格按“表名 + 表头 + 行块”保留；
  普通正文按段落簇切片；
  超长段落按句子二次拆分。
- 首版 chunk 默认参数固定：
  目标大小 `400 tokens`；
  overlap `60 tokens`；
  单 chunk 最大 `600 tokens`；
  标题、页码、来源 URI、文档类型、序号作为强制元数据。
- 首版检索模式固定为 `LEXICAL`。
  使用 PostgreSQL 过渡实现，不做 embedding、不做 rerank、不做真正 hybrid。
- 词法检索实现固定为：
  服务侧统一归一化文本；
  中文与混合文本用服务侧分词结果 + `pg_trgm` 相似检索；
  非中文文本额外用 `tsvector`；
  排序权重固定为 `title > heading_path > body > metadata hit`。
- 用户不能直接从“当前导入结果”发布。
  必须先从已成功导入的文档生成不可变 `index snapshot`，资源版本只允许绑定 `READY` 的快照。
- 资源新建页改为只维护知识库元信息和检索参数，不再内嵌文档编辑器。
  新增“内容工作台”页签，展示文件、导入任务、解析状态、文档数、chunk 数、快照列表。
- 资源版本摘要不再展示文档标题拼接，改为展示 `snapshotId / 文档数 / chunk 数 / 构建时间 / 检索模式`。

### 3. 第二阶段预留但不在首版实现
- 引入 `OpenSearch` 作为正式检索后端。
- 增加 embedding 生成、向量召回、词法/向量融合、cross-encoder rerank。
- 增加 URL 导入、网页抓取、扫描 PDF 的 OCR。
- 首版所有外部接口和快照模型都按“后端可替换”设计，但只实现 `POSTGRES_LEXICAL` 后端。

## Public APIs / Interfaces / Types
- `KnowledgeBaseConfig` 改为：
  `indexSnapshotId`
  `defaultTopK`
  `retrievalMode`
  `minScore`
  首版 `retrievalMode` 固定为 `LEXICAL`。
- 新增 API 聚合接口，统一挂在 `/api/resources/{resourceId}/knowledge/*`：
  `POST /upload-sessions` 创建上传会话
  `POST /upload-sessions/{id}/complete` 完成上传并启动导入
  `GET /files` 查询文件清单
  `GET /import-jobs` 查询导入任务
  `GET /documents` 查询解析后的文档
  `POST /index-snapshots` 从成功文档构建快照
  `GET /index-snapshots` 查询快照列表
- 新增知识服务内部接口：
  `POST /internal/uploads`
  `POST /internal/import-jobs/{jobId}/run`
  `POST /internal/index-snapshots/{snapshotId}/build`
  `POST /internal/retrieve`
- `resource version publish` 的校验规则改为：
  只有 `indexSnapshotId` 存在且状态为 `READY` 时才能发布知识库版本。
- runtime 侧知识命中结果改为结构化对象，不再是字符串数组。
  每条命中至少包含：
  `chunkId`
  `documentTitle`
  `sourceUri`
  `snippet`
  `score`
  `pageNumber`
  `headingPath`
- prompt 注入规则固定为：
  最多注入 `topK` 条命中；
  每条都保留来源信息；
  若所有命中低于 `minScore`，则返回“低召回置信度”并不注入知识正文。

## Test Plan
- 知识服务单测：
  文件类型识别、正文抽取、标题树构建、FAQ 切片、表格切片、超长段落二次拆分、chunk 元数据完整性。
- 知识服务检索测试：
  中文命中、英文命中、标题强匹配、正文弱匹配、无命中低置信度、元数据过滤、生效快照隔离。
- Temporal 集成测试：
  上传完成后触发导入；
  导入失败进入 `FAILED`；
  重试成功后状态恢复；
  快照构建失败不允许绑定发布。
- API 集成测试：
  创建知识库；
  上传文件；
  查询导入状态；
  生成快照；
  创建资源版本；
  发布时校验快照状态；
  删除被发布快照引用的文档或快照时被阻断。
- Runtime 测试：
  发布快照能解析到 `indexSnapshotId`；
  检索结果正确注入 prompt；
  无高分命中时不注入伪知识；
  返回结果带出处元数据。
- Web 测试：
  资源新建不再出现手工文档编辑；
  内容工作台可上传文件、查看任务、查看失败原因、生成快照、绑定版本并发布。

## Assumptions / Defaults
- 允许 breaking change，直接删除旧的 `documents[]` 配置路径、旧 seed 和旧 mock。
- 首版不支持 URL 导入、外部系统同步、OCR；扫描 PDF 解析失败时明确落为 `FAILED`，由 UI 展示失败原因。
- 一个知识库资源版本只绑定一个不可变 `indexSnapshotId`，运行时永远按已发布版本绑定的快照检索。
- 首版检索后端固定为 `PostgreSQL + pg_trgm` 过渡实现；第二阶段替换为 `OpenSearch` 时，不改变 API 聚合层和 runtime 调用契约。
- MinIO 只存原始文件和必要中间产物；检索正文、chunk、快照元数据全部落 PostgreSQL。
