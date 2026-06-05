# AgentYard jOOQ 落地与 SQL 工程化升级方案

> 本文是 [`docs/project_todos.md`](../project_todos.md) §3.5「安全加固」中“SQL 注入防护审计”的延伸方案，也是对 [`docs/architecture/code-framework.md`](../architecture/code-framework.md) 和 [`docs/todo/multi_instance_plan.md`](./multi_instance_plan.md) 的持久层补全。
> 本文不讨论“是否要换一个数据库访问框架玩一玩”，而是回答：当前 AgentYard 是否已经需要从粗犷 `JdbcTemplate + 字符串 SQL` 升级为更工程化的 SQL-first 持久层，以及应该如何一次性落到目标架构。

## 1. 目标与边界

### 1.1 前提

本方案默认以下前提：

1. 项目当前处于开发阶段，优先直接对齐目标架构，不做兼容性优先设计
2. 不为了保留现有 `JdbcTemplate` 写法而设计“双轨持久层”
3. 不把 ORM 作为目标；AgentYard 的目标是 SQL 工程化，而不是对象图持久化
4. `apps/api` 与 `apps/worker` 共享同一套 core PostgreSQL schema，持久层设计必须考虑共享与复用
5. `knowledge-service` 仍是 Python 服务，不强行纳入 JVM jOOQ 体系

### 1.2 目标

把当前 Java 侧 core 数据库访问从“可工作但字符串驱动”的状态，重构为具备以下能力的目标架构：

1. Flyway 继续作为 core schema 的唯一权威来源
2. `apps/api` 与 `apps/worker` 通过共享的、类型安全的 schema model 访问同一批 core 表
3. 新增和修改 SQL 时，表名、字段名、类型变化尽量前移到编译期暴露
4. 动态查询、`jsonb`、`on conflict`、分页、排序、条件拼装有统一工程化写法
5. 同一张表在 `api` 与 `worker` 中重复的持久化逻辑被抽到共享模块
6. 停止继续扩散 `JdbcTemplate + StringBuilder + 手写列名字符串` 模式
7. 为后续多实例、缓存失效、审计、性能调优提供更稳定的 SQL 语言和边界

### 1.3 不在本文范围

本文不覆盖：

1. `knowledge-service` Python 侧 ORM/SQL 框架替换
2. PostgreSQL/pgvector 检索算法本身优化
3. Flyway 迁移脚本具体业务内容
4. 发布流程、灰度切换、历史数据迁移

### 1.4 当前本地代码现状

以下内容基于当前工作树中的本地代码，包括未提交改动。

这意味着本文不是站在“仓库主干还完全停留在旧状态”的角度写迁移方案，而是要承认一部分基础工作已经在本地推进。

当前已经发生的关键变化：

1. 已新增共享模块 `packages/shared-redis-jvm`，并由 `apps/api`、`apps/worker` 共同依赖
2. API 已引入 `spring-session-data-redis`，并通过 `SessionRedisConfiguration` 明确 Redis session serializer
3. `SessionDispatchLockService` 已从进程内锁切到 `RedisLockService + RedisKeyspace`
4. `session runtime` 的跨实例流式链路已开始落地：
   - API 侧已有 `SessionRuntimeStreamService`
   - API 侧已有 `SessionRuntimeChangeNoticePublisher`
   - worker 侧已有 `SessionRuntimeChangePublisher`
   - contracts 中已抽出 `SessionRuntimeChangeNotice`
5. `CatalogService` / `KnowledgeService` 已经明显向 repository-first 读写模型收敛，并开始接入 revision/invalidation 语义
6. `CatalogRepository` / `KnowledgeRepository` 已从整快照 `load/save` 形态，转向更细粒度的 `upsert/delete/replace` 接口
7. `JdbcCatalogRepository` / `JdbcKnowledgeRepository` 已开始承载读写事务、revision 预留和派生引用表刷新等职责

这组变化的含义是：

1. 多实例与共享状态基建不是“待开始”，而是“已在进行”
2. jOOQ 方案不能假设需要先从头建设 Redis/shared-state 基础
3. jOOQ 的落地必须承接当前 repository-first 与共享模块化方向，而不是把这波本地改动推翻重来

## 2. 先说结论

AgentYard 当前确实已经到了必须做 SQL 工程化升级的阶段。

但正确方向不是：

1. 把 `JdbcTemplate` 零散替成另一套“更顺手的 JDBC 包装”
2. 把 repository 改写成 Hibernate/JPA 实体图
3. 把复杂 SQL 搬进 MyBatis XML

正确方向是：

1. 保持 **SQL-first / PostgreSQL-first**
2. 引入 **jOOQ** 作为 Java core 数据库访问主框架
3. 在现有 **shared-redis-jvm + repository-first + shared-state** 基础上，以 **Flyway + jOOQ codegen + shared persistence module** 形成新的工程化基线

本文建议的最终排序：

1. 主路线：`jOOQ`
2. 备选：`Jdbi`
3. 不作为主路线：`MyBatis`
4. 不作为主路线：`Spring Data JDBC`
5. 不作为主路线：`Hibernate/JPA`

## 3. 为什么当前写法已经不够了

以下判断直接基于当前仓库代码，而不是抽象偏好。

### 3.1 当前不是简单 CRUD 项目

当前 core 数据访问已经明显包含：

1. PostgreSQL 方言：`jsonb`、`on conflict`
2. 动态筛选与游标分页
3. 子查询、聚合、`coalesce(...)`
4. 运行态投影写入与查询并存
5. `api` / `worker` 对同一组表的共享读写

代表性代码：

- [`apps/api/src/main/java/com/agentyard/platform/session/JdbcSessionRuntimeRepository.java`](../../apps/api/src/main/java/com/agentyard/platform/session/JdbcSessionRuntimeRepository.java)
- [`apps/worker/src/main/java/com/agentyard/worker/session/JdbcSessionProjectionRepository.java`](../../apps/worker/src/main/java/com/agentyard/worker/session/JdbcSessionProjectionRepository.java)
- [`apps/api/src/main/java/com/agentyard/platform/event/JdbcPlatformEventRepository.java`](../../apps/api/src/main/java/com/agentyard/platform/event/JdbcPlatformEventRepository.java)
- [`apps/api/src/main/java/com/agentyard/platform/catalog/JdbcCatalogRepository.java`](../../apps/api/src/main/java/com/agentyard/platform/catalog/JdbcCatalogRepository.java)
- [`apps/api/src/main/java/com/agentyard/platform/knowledge/JdbcKnowledgeRepository.java`](../../apps/api/src/main/java/com/agentyard/platform/knowledge/JdbcKnowledgeRepository.java)

这已经不是“几张表、几个简单查询”的规模。

### 3.2 当前问题不是 SQL 太多，而是 SQL 工程化仍未完成

当前痛点主要有六类：

#### 1. schema 变更暴露太晚

当前表名、列名、排序字段、`payload` 字段访问、`on conflict` 字段集合，大量以字符串存在。

结果：

- 列改名、字段删除、类型变化，大多只能在运行时或集成测试时暴露
- `api` 和 `worker` 共用表时，schema 漂移的爆炸半径更大

#### 2. 相同 SQL 语义仍然重复出现

`session_runtime_session` / `session_runtime_event` / `session_runtime_playbook_run` 的 upsert/append/query 逻辑，在 `api` 和 `worker` 里已经有明显重复。

这违反了当前项目“相同或高度相似逻辑出现 3 次以上就抽共享”的规则，也会直接放大后续 schema 调整成本。

#### 3. 动态 SQL 仍靠字符串拼接

例如 [`JdbcPlatformEventRepository`](../../apps/api/src/main/java/com/agentyard/platform/event/JdbcPlatformEventRepository.java) 的查询构造，当前仍然靠 `StringBuilder + 命名参数`。

这在功能上可行，但问题是：

1. 条件组合与字段引用没有统一抽象
2. 排序、游标、条件复用成本高
3. 安全审计必须继续靠人工逐条确认

#### 4. PostgreSQL 特性被埋在字符串里

`jsonb`、`on conflict`、`cast(? as jsonb)` 这些都不是例外情况，而是当前核心模型的一部分。

继续把这些语义埋在 SQL 字符串中，意味着：

1. 业务代码需要同时承担 SQL 拼装和 schema 记忆负担
2. `jsonb`、enum、时间字段映射没有统一规范
3. 查询可读性和可维护性持续下降

#### 5. 持久层边界仍然不够清晰

当前 repository 层同时存在：

1. 纯持久化写入
2. 查询拼装
3. JSON 序列化/反序列化
4. 反射式 `id()` 读取
5. `delete all + replace all` 快照刷新

这导致持久层既缺统一语言，也缺稳定边界。

#### 6. 多实例与共享状态升级已经开始，但仍会继续受 SQL 层拖累

[`docs/todo/multi_instance_plan.md`](./multi_instance_plan.md) 已经明确要求：

1. repository-first 读模型
2. 先消除单机建模，再谈缓存
3. 共享状态和失效协议要统一

如果底层查询语言还是大量字符串 SQL，后续做：

- 缓存 key 与失效边界
- 查询热点识别
- 共享 repository 提取
- SQL 审计与性能调优

都会继续高成本。

### 3.3 当前已经完成的正确方向，不应在 jOOQ 迁移中被回退

本地未提交改动里，已经有几项方向是正确的，后续 jOOQ 落地必须直接承接：

#### 1. `shared-redis-jvm` 已经形成共享模块雏形

这说明当前仓库已经接受“跨 app 的基础设施能力要抽到 `packages/*-jvm`”这个方向。

jOOQ 落地时不该另起一套松散 helper，而应对齐这种模块化方式，新增并建设：

- `packages/persistence-jvm`

#### 2. `session runtime` 已经走向跨实例通知与 SSE replay

这意味着：

1. `session runtime` 仍然是 jOOQ 首批迁移优先级最高的领域
2. 持久层重构必须服务于现有 stream/change-notice/replay 方案，而不是与其脱节

#### 3. `catalog` / `knowledge` 已经开始去单机内存镜像

虽然当前底层仍是 `JdbcTemplate + jsonb payload`，但 service 和 repository 边界已经发生实质变化：

1. repository-first 的读写模型已经在形成
2. invalidation/revision 机制已经开始进入控制面治理链路

所以后续对 `catalog` / `knowledge` 的 jOOQ 升级，不应再按旧文档里那种“先拆掉内存态、再做 repository-first”的顺序描述，而应改成：

1. 保留并固化当前 repository-first 方向
2. 在这个基础上把底层 `JdbcTemplate` 替换为更工程化的 schema-safe 持久层

## 4. 为什么选 jOOQ，而不是别的

### 4.1 选择标准

这次不是选“最流行”的框架，而是看四个维度是否同时成立：

1. 是否适合当前 **SQL-first / PostgreSQL-first** 模型
2. 是否能把 schema 约束前移到编译期
3. 是否能和 Spring Boot + Flyway + Gradle 无缝结合
4. 社区是否仍活跃，近期是否持续发布

### 4.2 社区与活跃度快照

以下信息按 **2026-04-21** 查询：

| 框架 | GitHub 信号 | 最近版本/日期 | 结论 |
|---|---:|---|---|
| `jOOQ` | 约 **6.7k stars** | **3.21.2 / 2026-04-16** | 活跃，且长期多版本线维护稳定 |
| `Jdbi` | 约 **2.1k stars** | **3.52.1 / 2026-04-09** | 活跃，适合 SQL-first，但工程化深度不如 jOOQ |
| `Spring Data Relational` | 约 **818 stars** | **4.0.5 / 2026-04-17** | 背靠 Spring，维护稳定，但抽象不匹配 |
| `MyBatis core` | 约 **20.4k stars** | **3.5.19 / 2025-01-02** | 社区大，但核心近期节奏较慢 |
| `MyBatis Spring Boot Starter` | 约 **4.2k stars** | **4.0.1 / 2025-12-28** | 集成层仍活跃 |
| `Hibernate ORM` | 约 **6.4k stars** | 官网最新稳定线 **7.2** | 社区强，但方向不适合当前项目 |

注意：

1. `stars` 不能直接代表适配度
2. 对 AgentYard 更重要的是“抽象是否匹配”，不是“社区是否最大”

### 4.3 为什么 `jOOQ` 最匹配

`jOOQ` 的核心价值不是替你隐藏 SQL，而是把 SQL 变成 Java 内部 DSL，并且可以从数据库 schema 生成类型安全代码。

这正好命中 AgentYard 当前需求：

1. 保留 SQL-first 思维，不引入 ORM 对象图语义
2. 对 PostgreSQL 友好，能承接 `jsonb`、upsert、复杂查询
3. 能把 Flyway 迁移后的 schema 直接变成编译期可见模型
4. Spring Boot 已有现成 `DSLContext` 自动配置能力

官方文档：

- [Spring Boot: Using jOOQ](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [jOOQ Manual: SQL building](https://www.jooq.org/doc/latest/manual/sql-building/)
- [jOOQ Manual: Code generation](https://www.jooq.org/doc/latest/manual/code-generation/)
- [jOOQ Manual: Using jOOQ with Flyway](https://www.jooq.org/doc/latest/manual/getting-started/tutorials/jooq-with-flyway/)

### 4.4 为什么不是 `Jdbi`

`Jdbi` 是唯一值得认真当备选的方案。

优点：

1. 仍然是 SQL-first
2. 比 `JdbcTemplate` 更现代、更舒服
3. 有 Spring 和 Postgres 支持

但它更像“更好的 JDBC”，不是“schema 工程化平台”。

换句话说：

- `Jdbi` 能降低样板代码
- `jOOQ` 能同时降低样板代码、提升 schema 安全、统一查询语言

对当前 AgentYard，更重要的是后者。

### 4.5 为什么不是 `MyBatis`

`MyBatis` 的问题不是过时，而是它解决的问题不对。

对当前项目，引入它大概率只是把：

- Java 里的 SQL 字符串

搬成：

- 注解 SQL
- XML mapper

复杂度并没有真正降低，schema 编译期约束也没有根本提升。

### 4.6 为什么不是 `Spring Data JDBC` / `Hibernate`

这两类方案的问题都不是社区弱，而是建模方向不对。

AgentYard 当前面临的是：

1. 运行态投影
2. 审计账本
3. JSONB payload
4. PostgreSQL 方言
5. 共享表读写

这是 **SQL 工程化问题**，不是 **对象关系映射问题**。

## 5. 目标架构

### 5.1 总体原则

升级后的 core JVM 持久层必须遵守以下原则：

1. **Flyway 是 schema 唯一权威**
2. **jOOQ 是 Java core DB 访问唯一主框架**
3. **Repository 仍按业务域组织，不引入通用 DAO 泛化层**
4. **共享表的共享逻辑必须提到共享模块**
5. **允许 raw SQL escape hatch，但只能作为少数例外**
6. **JSONB 只在确实需要 schema 弹性的地方保留，不再作为默认逃生舱**

### 5.2 模块布局

建议新增 JVM 共享模块：

`packages/persistence-jvm`

职责：

1. 承载 jOOQ 生成代码
2. 承载 core schema 相关 converter / binding / helper
3. 承载 `api` 与 `worker` 共享的持久化组件
4. 作为 core PostgreSQL schema 的 JVM 共享访问层

这个模块应当与当前已存在的：

- `packages/shared-redis-jvm`

形成并列关系，而不是取代它。

建议目录：

```text
packages/persistence-jvm/
  build.gradle.kts
  src/main/java/com/agentyard/persistence/
    config/
    jooqsupport/
    session/
    event/
    auth/
  src/generated/jooq/com/agentyard/persistence/jooq/
```

根 Gradle 需要新增：

1. `settings.gradle.kts` 增加 `include("packages:persistence-jvm")`
2. `apps/api`、`apps/worker` 依赖 `project(":packages:persistence-jvm")`

注意：

当前 `settings.gradle.kts` 已经注册了 `packages:shared-redis-jvm`。

因此这里不是“第一次引入共享 JVM 模块”，而是把“共享基础设施模块”扩展到持久层。

### 5.3 共享与归属边界

#### 放进 `packages/persistence-jvm` 的内容

1. jOOQ generated tables / records / enums
2. `jsonb` / enum / time 类型映射
3. `session_runtime_*` 相关共享存储逻辑
4. `platform_event` 相关共享存储逻辑
5. `platform_user` 等 core 公共表的共享存储逻辑

#### 继续放在 `apps/api` 的内容

1. `catalog`
2. `knowledge`
3. API 用例特定的查询对象、聚合 DTO、服务层事务编排

#### 继续放在 `apps/worker` 的内容

1. activity 语义
2. workflow 对持久层的调用编排

但 `worker` 不应再维护自己独立的一套相同 SQL。

### 5.4 codegen 与 Flyway 的关系

当前 core schema migration 位于：

- [`apps/api/src/main/resources/db/migration`](../../apps/api/src/main/resources/db/migration)

这意味着：

1. `apps/api` 继续作为 core schema migration owner
2. `packages/persistence-jvm` 的 jOOQ codegen 必须直接消费这套 migration 结果

建议流程：

1. 启动临时 PostgreSQL
2. 对临时库执行 `apps/api` 的 Flyway migration
3. 用 jOOQ codegen 读取迁移后的 schema
4. 生成 `packages/persistence-jvm/src/generated/jooq`
5. Java 编译依赖生成结果

不要采用以下路线：

1. 手写表结构镜像
2. 每个 app 各自生成一份 jOOQ 代码
3. `api` 和 `worker` 各自维护自己的 schema model

### 5.5 生成代码是否入库

首版建议：**生成代码入库**。

理由：

1. 最短路径，降低 IDE 与本地构建门槛
2. `api` / `worker` 都依赖同一份 schema contract，入库更容易做 code review
3. 当前仓库尚未建立完善的“临时库 + 自动 codegen + IDE source attachment”体验，先缩短落地路径更重要

配套要求：

1. 生成代码只允许由 task 更新，不允许手改
2. CI 增加“重新生成后工作树必须干净”的校验
3. 若后续 codegen 体验稳定，再评估是否切为“不入库”

### 5.6 数据模型目标

这次升级不是“只换框架不改模型”。

需要明确以下目标：

#### `session` / `event` / `auth`

继续保持关系化表结构，直接迁到 jOOQ。

#### `catalog` / `knowledge` control-plane

当前 `payload jsonb + delete all / replace all + 反射 readId()` 的写法，不是最终目标架构。

目标应为：

1. 顶层治理对象改成类型化列建模
2. 关联关系继续用独立 binding 表
3. `jsonb` 只保留给真正结构弹性的嵌套对象、发布快照或审计 payload

也就是说：

- `jsonb` 可以存在
- 但不能再作为 catalog/knowledge 治理存储的默认建模方式

## 6. 落地规范

### 6.1 查询与写入规则

升级后默认规则：

1. 默认使用 jOOQ DSL 构造查询和 DML
2. 禁止新增 `JdbcTemplate` / `NamedParameterJdbcTemplate` repository
3. 禁止直接拼接表名、列名、排序字段字符串
4. 允许 `plain SQL`，但仅限 jOOQ 明显不便表达的极少数场景，并要求代码注释说明原因

### 6.2 动态查询规则

所有动态查询统一采用：

1. query object 输入
2. jOOQ `Condition` 逐步拼装
3. 排序字段由枚举或受限映射控制

禁止继续使用：

1. `StringBuilder` 拼 where/order by
2. 用户输入直接控制列名
3. 多段硬编码 SQL 分支复制

### 6.3 JSONB 规则

统一约束：

1. 业务代码不直接散落 `cast(? as jsonb)`
2. 通过统一 converter / helper 处理 JSONB 映射
3. 明确哪些字段是 JSONB contract，哪些只是过渡存储

### 6.4 事务规则

事务边界继续由 service/use-case 层控制，不下沉到通用 DAO。

规则：

1. repository 负责持久化语义，不自行扩张事务边界
2. 跨 repository 的事务由 service 层统一编排
3. `api` 和 `worker` 对同一事务语义不得各自实现一套 SQL

### 6.5 复用规则

以下情况必须抽共享：

1. 同表的 upsert 逻辑在 `api` / `worker` 同时出现
2. 同一查询条件组合出现 3 次以上
3. 相同 JSONB 映射逻辑出现 3 次以上

### 6.6 测试规则

需要建立三层测试：

1. repository 级 Testcontainers PostgreSQL 测试
2. service 级事务行为测试
3. codegen 与 migration 同步校验

目标是把“列改名/类型变更/条件拼错”的问题前移到编译期或 repository 测试阶段。

## 7. 分阶段实施方案

### 7.1 Phase 0：在现有共享基建上补齐 jOOQ 基础设施

目标：

1. 在已存在的 `packages/shared-redis-jvm` 之外，新增 `packages/persistence-jvm`
2. 接入 jOOQ 依赖与 codegen task
3. 打通 Flyway migration -> jOOQ generation -> compile 全链路
4. 建立 `DSLContext`、JSONB converter、基础测试模板

具体动作：

1. 新增 `packages/persistence-jvm/build.gradle.kts`
2. 在当前已包含 `packages:shared-redis-jvm` 的 `settings.gradle.kts` 中注册新模块
3. `apps/api` / `apps/worker` 在现有 shared-redis 依赖之外，再增加 `packages:persistence-jvm`
4. `apps/api` / `apps/worker` 增加 `spring-boot-starter-jooq`
5. 在 `packages/persistence-jvm` 配置 codegen task，schema 输入来自 `apps/api` Flyway migration
6. 生成 core schema 代码并入库
7. 补一组基础 repository integration test 模板

验收：

1. 本地可一键生成 jOOQ schema 代码
2. `api` / `worker` 可注入 `DSLContext`
3. CI 能校验生成代码与 migration 同步

### 7.2 Phase 1：承接当前多实例运行态改造，先迁 `session runtime` 与 `platform_event`

这是首批迁移面，原因：

1. SQL 复杂度最高
2. `api` / `worker` 共享最重
3. 重复逻辑最明显
4. 对当前已经在本地推进的多实例、观测、SSE、审计链路价值最大

迁移对象：

1. `session_runtime_session`
2. `session_runtime_event`
3. `session_runtime_playbook_run`
4. `platform_event`

具体动作：

1. 把 `JdbcSessionRuntimeRepository` 重写为 jOOQ 版本
2. 把 `JdbcSessionProjectionRepository` 的共享 SQL 提到 `packages/persistence-jvm`
3. 把 `JdbcPlatformEventRepository` 重写为 jOOQ 版本
4. 抽共享 `SessionRuntimeStore` / `PlatformEventStore`
5. 让 `SessionRuntimeStreamService`、`SessionRuntimeChangeNoticePublisher`、worker `SessionRuntimeChangePublisher` 复用同一套底层 schema-safe store
6. 消除 `api` 与 `worker` 在 shared tables 上的重复 SQL

验收：

1. `session` / `event` / `playbook run` 不再依赖 `JdbcTemplate`
2. `api` / `worker` 对共享表的写入逻辑不再重复
3. 关键 repository 都有 PostgreSQL integration tests

### 7.3 Phase 2：在当前 shared-state 改造基础上，迁 `auth` / `system` / 其他简单表

迁移对象：

1. `JdbcUserRepository`
2. `SystemController` 中直接 JDBC 健康探针
3. Spring Session / shared-state 周边仍直接耦合 JDBC 的简单探针或查询
4. 其他简单 core 表访问

目标：

1. 清掉剩余直连 `JdbcTemplate` 的简单路径
2. 统一 core JVM 持久层语言

### 7.4 Phase 3：承接当前 repository-first 改造，重构 `catalog` / `knowledge` 持久层

这一阶段不是简单把 `JdbcCatalogRepository` 翻译成 jOOQ，而是要直接对齐目标建模。

同时，这一阶段必须显式承接当前本地已经存在的改造，而不是把它推翻：

1. 保留 `CatalogRepository` / `KnowledgeRepository` 已经形成的细粒度读写接口
2. 保留 `inReadTransaction` / `inWriteTransaction`、revision、invalidation 方向
3. 仅替换底层实现与存储建模，不把 service 层重新改回整快照内存模型

必须完成的工作：

1. 把顶层治理对象从 `payload jsonb` 改成类型化列
2. 保留真正需要的 JSONB 字段，而不是整对象 JSONB
3. 去掉 `delete all / replace all / 反射 id()` 持久化套路
4. 对 bindings / references / release 相关查询建立类型化 repository

这一步完成后，`catalog` / `knowledge` 才算真正从“临时控制面存储”升级为工程化持久层。

### 7.5 Phase 4：清理与收口

目标：

1. 删除剩余 `Jdbc*Repository`
2. 删除多余 JDBC helper
3. 更新架构文档
4. 补全编码规范与 PR 检查项

最终状态：

1. Java core DB 访问默认只有 jOOQ
2. `JdbcTemplate` 仅可能保留在极少数基础设施探针中，且要有明确理由
3. 新功能不得再新增 JDBC string SQL repository

## 8. 构建、CI 与本地开发要求

### 8.1 本地开发

本地开发需要新增统一入口，例如：

1. `./gradlew generateJooq`
2. `./gradlew :packages:persistence-jvm:test`
3. `./gradlew build`

要求：

1. 不依赖开发者手工准备 schema
2. codegen 过程可重复、可清理
3. IDE 能直接导航到生成的表/字段代码

### 8.2 CI

CI 至少新增以下检查：

1. migration 后 codegen 是否仍可成功
2. 重新生成后工作树是否干净
3. repository integration tests 是否通过
4. `api` / `worker` 编译是否能消费同一份生成 schema

## 9. 风险与对策

### 9.1 风险：把 jOOQ 当成“更复杂的 JDBC”

如果只引入依赖，不统一规范，最终会得到：

1. 一半字符串 SQL
2. 一半 jOOQ DSL
3. 双倍维护成本

对策：

1. 从 Phase 1 开始就禁止新增 `JdbcTemplate repository`
2. 把共享表先迁掉，建立范式

### 9.2 风险：只换框架，不改重复逻辑

如果 `api` 和 `worker` 还是各写一套 jOOQ SQL，问题只会换语言继续存在。

对策：

1. 共享表逻辑必须进入 `packages/persistence-jvm`
2. Phase 1 先做共享抽取，而不是只做单类替换

### 9.3 风险：catalog/knowledge 继续维持 JSONB 全量快照

这会导致：

1. schema 类型安全收益打折
2. 查询与约束仍然依赖应用层约定
3. 后续治理能力继续受限

对策：

1. 把 `catalog` / `knowledge` 的类型化重构纳入正式 phase，不作为“以后再说”

### 9.4 风险：codegen 体验差，团队绕回手写 SQL

对策：

1. 首版生成代码入库
2. 提供统一 task
3. 在 CI 上强校验 schema 与 generated code 同步

## 10. 验收标准

满足以下条件，才算本次升级完成：

1. `apps/api` 与 `apps/worker` 已接入 jOOQ，核心 repository 不再依赖 `JdbcTemplate`
2. `session_runtime_*` 与 `platform_event` 的共享 SQL 逻辑已经抽到共享模块
3. core schema 由 Flyway 驱动，并能稳定生成 jOOQ 代码
4. 新增查询默认使用 jOOQ DSL，不再手写列名字符串
5. `catalog` / `knowledge` 顶层治理存储不再默认采用整对象 `payload jsonb`
6. repository 级 PostgreSQL integration tests 已覆盖核心路径
7. 当前已落地的 `shared-redis-jvm`、distributed lock、runtime change notice、SSE replay 等 shared-state 代码，已经切到统一的 schema-safe persistence 基座之上
8. 架构文档已经明确“jOOQ 是 Java core DB access 主框架”

## 11. 立即执行顺序

如果按最短路径启动，建议顺序是：

1. 承认并保留当前已存在的 `shared-redis-jvm`、repository-first、runtime stream/shared-state 方向
2. 新增 `packages/persistence-jvm`
3. 打通 Flyway -> jOOQ codegen -> compile
4. 先迁 `session runtime` 与 `platform_event`
5. 抽出 `api` / `worker` 的共享持久化逻辑，并接回当前 stream/change-notice 代码
6. 再迁 `auth/system`
7. 最后直改 `catalog/knowledge` 建模到目标结构

## 12. 参考资料

外部资料：

- [Spring Boot jOOQ 支持](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [jOOQ SQL building](https://www.jooq.org/doc/latest/manual/sql-building/)
- [jOOQ code generation](https://www.jooq.org/doc/latest/manual/code-generation/)
- [jOOQ with Flyway](https://www.jooq.org/doc/latest/manual/getting-started/tutorials/jooq-with-flyway/)
- [Jdbi 文档](https://jdbi.org/releases/3.49.0/)
- [MyBatis Spring Boot Starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)
- [Spring Data JDBC 文档](https://docs.spring.io/spring-data/relational/reference/jdbc.html)
- [Hibernate ORM](https://hibernate.org/orm/)

仓库内上下文：

- [`docs/architecture/code-framework.md`](../architecture/code-framework.md)
- [`docs/todo/multi_instance_plan.md`](./multi_instance_plan.md)
- [`apps/api/src/main/java/com/agentyard/platform/session/JdbcSessionRuntimeRepository.java`](../../apps/api/src/main/java/com/agentyard/platform/session/JdbcSessionRuntimeRepository.java)
- [`apps/worker/src/main/java/com/agentyard/worker/session/JdbcSessionProjectionRepository.java`](../../apps/worker/src/main/java/com/agentyard/worker/session/JdbcSessionProjectionRepository.java)
- [`apps/api/src/main/java/com/agentyard/platform/event/JdbcPlatformEventRepository.java`](../../apps/api/src/main/java/com/agentyard/platform/event/JdbcPlatformEventRepository.java)
- [`apps/api/src/main/java/com/agentyard/platform/catalog/JdbcCatalogRepository.java`](../../apps/api/src/main/java/com/agentyard/platform/catalog/JdbcCatalogRepository.java)
- [`apps/api/src/main/resources/db/migration`](../../apps/api/src/main/resources/db/migration)
