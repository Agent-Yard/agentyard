# Channel Gateway 首版落地计划（方案 A）

## Summary

新增 `apps/channel-gateway` 作为独立 Spring Boot 服务，承担外部渠道接入与渠道数据持久化；`apps/web` 继续只连 `apps/api`，`apps/api` 新增 `channel-admin` 控制台门面并通过内部鉴权调用 `channel-gateway`。  
首版范围固定为：独立服务骨架、最小渠道表集、控制台聚合 API、飞书 webhook 占位入口；**不做真实飞书消息闭环、不改登录体系、不让 `apps/web` 直连新服务**。

## Key Changes

### 1. 新服务与模块边界

- 新增 Gradle 子项目 `apps:channel-gateway`，技术栈沿用现有 Java 25 + Spring Boot 4。
- `channel-gateway` 只承担：
  - 渠道账号配置与状态
  - 外部会话绑定
  - 入站事件落盘与幂等
  - 出站投递记录
  - 渠道 provider 入口占位
- `channel-gateway` 不承担：
  - 控制台用户登录
  - OIDC / HttpSession
  - Web 控制台聚合逻辑
- `apps/api` 作为控制台唯一用户后端，新增 `ChannelGatewayClient` 和 `channel-admin` service/controller。
- `apps/web` 本阶段不新增页面；只为后续控制台接入预留 API/TS 类型即可。

### 2. 持久化与数据库基线

- 继续沿用当前统一基线：**Flyway 迁移放在 `apps/api/src/main/resources/db/migration`，jOOQ 继续由 `packages/persistence-jvm` 生成**。
- `channel-gateway` 自己连接 core DB，但 **不开 Flyway**；由现有迁移链统一管理 schema。
- 在 `packages/persistence-jvm` 新增 channel store，供 `channel-gateway` 使用；`apps/api` 不直接读写这些表，只通过内部 HTTP 调用新服务。

首版最小表集：

- `channel_account`
  - 渠道账号/应用配置
  - 字段至少包含：`id`、`provider_type`、`name`、`status`、`config(jsonb)`、`created_at`、`updated_at`
- `channel_conversation_binding`
  - 外部会话与内部 `sessionId` 的绑定
  - 字段至少包含：`id`、`channel_account_id`、`external_conversation_id`、`external_user_id`、`assistant_id`、`customer_id`、`session_id`、`status`、`metadata(jsonb)`、时间戳
- `channel_inbound_event`
  - 外部回调/消息入站审计与幂等
  - 字段至少包含：`event_id`、`channel_account_id`、`provider_type`、`event_type`、`external_event_id`、`external_conversation_id`、`external_message_id`、`dedup_key`、`raw_payload(jsonb)`、`normalized_payload(jsonb)`、`status`、时间戳
- `channel_outbound_delivery`
  - 出站消息投递记录
  - 字段至少包含：`delivery_id`、`channel_account_id`、`provider_type`、`session_id`、`session_message_id`、`external_conversation_id`、`payload(jsonb)`、`status`、`attempt_count`、`last_error`、时间戳

明确不在首版引入：
- `channel_action_ticket`
- 渠道级任务调度器/重试 worker
- 渠道级 Redis 编排

### 3. API 与接口边界

`channel-gateway` 暴露两组接口：

- `GET /actuator/health`
- `GET /internal/channel-admin/accounts`
- `POST /internal/channel-admin/accounts`
- `GET /internal/channel-admin/accounts/{id}`
- `PUT /internal/channel-admin/accounts/{id}`
- `GET /internal/channel-admin/accounts/{id}/bindings`
- `GET /internal/channel-admin/accounts/{id}/inbound-events`
- `GET /internal/channel-admin/accounts/{id}/outbound-deliveries`
- `POST /connectors/feishu/webhook`
  - 只做飞书入口占位
  - 支持签名/挑战校验骨架
  - 通过后将原始事件标准化并落 `channel_inbound_event`
  - 当前不继续路由到 `session-runtime`

`apps/api` 对 `apps/web` 暴露控制台门面：

- `GET /api/channel-admin/accounts`
- `POST /api/channel-admin/accounts`
- `GET /api/channel-admin/accounts/{id}`
- `PUT /api/channel-admin/accounts/{id}`
- `GET /api/channel-admin/accounts/{id}/bindings`
- `GET /api/channel-admin/accounts/{id}/inbound-events`
- `GET /api/channel-admin/accounts/{id}/outbound-deliveries`

鉴权规则固定：

- `apps/web -> apps/api` 继续沿用现有登录态
- `apps/api` 的 `channel-admin` 读接口使用 `@RequireGovernanceAccess`
- 写接口使用 `@RequireGovernanceWrite`
- `apps/api -> channel-gateway` 使用现有 `agentyard.internal-auth.token`
- `channel-gateway /internal/**` 仅接受内部 Bearer token
- `channel-gateway /connectors/**` 不走内部 token，走 provider 自身校验逻辑

### 4. 配置、启动与运维骨架

新增配置：

- `AGENTYARD_CHANNEL_GATEWAY_PORT`
- `AGENTYARD_CHANNEL_GATEWAY_BASE_URL`
- 复用 `SPRING_DATASOURCE_*`
- 复用 `AGENTYARD_INTERNAL_AUTH_TOKEN`

新增运行接入：

- `settings.gradle.kts` 增加 `include("apps:channel-gateway")`
- 新增 `apps/channel-gateway/build.gradle.kts`
- 新增 `apps/channel-gateway/src/main/resources/application.yml`
- 新增 `scripts/local/channel-gateway.sh`
- 新增 `scripts/dev/channel-gateway.sh`
- 更新根 `package.json`，增加 `local:channel-gateway`
- 更新 `scripts/local/all.sh` / `scripts/dev/all.sh`
- 更新 `infra/dev/docker-compose.yml` 增加 `channel-gateway` 容器
- 本地默认端口定为 `8082`

同时在 `apps/api` 中新增：

- `agentyard.channel-gateway.base-url`
- `ChannelGatewayClient`
- `channel-admin` controller/service/dto
- 对 `packages/contracts` / `apps/web` 的 TS 类型与 API client 占位同步，但不建页面、不改菜单

## Test Plan

- `channel-gateway` 启动测试：
  - 健康检查可用
  - 未配置内部鉴权 token 时启动失败
- `channel-gateway` 鉴权测试：
  - `/internal/**` 无 token 或错误 token 返回 401/403
  - `/connectors/feishu/webhook` 不走内部 token链路
- 持久化测试：
  - `channel_account` CRUD
  - `channel_inbound_event` 按 `dedup_key` 幂等
  - `bindings/events/deliveries` 列表查询正确
- `apps/api` 门面测试：
  - 已登录且有 governance 权限可访问 `channel-admin`
  - 无权限被拒绝
  - `ChannelGatewayClient` 正确转发并处理错误
- 飞书占位测试：
  - challenge/校验请求能返回预期结构
  - 合法 webhook 可入库为 `RECEIVED`
  - 重复 webhook 不产生重复入站事件
- 基线测试：
  - `./gradlew :apps:channel-gateway:test :apps:api:test`
  - `./gradlew verifyJooqGenerated`

## Assumptions

- 模块名称固定为 `apps/channel-gateway`。
- 首版只做飞书 connector 占位，不接真实飞书消息到 `session-runtime`，不实现发送闭环。
- 渠道 schema 继续并入 core DB，不拆独立数据库。
- DB migration 仍由 `apps/api` 统一维护，`channel-gateway` 不自带 Flyway 执行。
- 控制台前端这次不做页面，只补后端门面与前端可消费类型。
- 不考虑旧数据兼容与渐进替换，直接以目标架构建新边界。
