# Deployment 与 Governance

## 1. 组合部署

```yaml
x-internal-auth-env: &internal-auth-env
  AGENTYARD_INTERNAL_TOKEN_FILE: /run/secrets/agentyard_internal_token

x-core-extension-env: &core-extension-env
  <<: *internal-auth-env
  AGENTYARD_EXTENSION_REGISTRATION_FILE: /etc/agentyard/extensions.yaml
  AGENTYARD_CHANNEL_GATEWAY_BASE_URL: http://channel-gateway:8080
  AGENTYARD_AGENT_RUNTIME_BASE_URL: http://agent-runtime:8080

x-extension-volumes: &extension-volumes
  - ./extensions.yaml:/etc/agentyard/extensions.yaml:ro

services:
  api:
    image: agentyard/api
    environment: *core-extension-env
    volumes: *extension-volumes
    secrets:
      - agentyard_internal_token

  agent-runtime:
    image: agentyard/agent-runtime
    environment: *core-extension-env
    volumes: *extension-volumes
    secrets:
      - agentyard_internal_token

  channel-gateway:
    image: agentyard/channel-gateway
    environment: *core-extension-env
    volumes: *extension-volumes
    secrets:
      - agentyard_internal_token

  acme-channel-provider:
    image: acme/agentyard-channel-provider
    environment:
      <<: *internal-auth-env
      AGENTYARD_CHANNEL_GATEWAY_BASE_URL: http://channel-gateway:8080
    secrets:
      - agentyard_internal_token

  acme-crm-connector:
    image: acme/agentyard-crm-connector
    environment: *internal-auth-env
    secrets:
      - agentyard_internal_token

secrets:
  agentyard_internal_token:
    file: ./secrets/agentyard_internal_token
```

`extensions.yaml` 只声明 enterprise registration，core preset registration 由 core 自动注入：

```yaml
agentyard:
  extensions:
    # Only enterprise registrations are listed here.
    # core-channel-gateway / core-agent-runtime are injected by core and must not be declared here.
    services:
      - registrationId: acme-channel-provider
        baseUrl: http://acme-channel-provider:8080
        exposes:
          channelProviderTypes:
            - enterprise.acme.internal-im
            - enterprise.acme.ticket
        auth:
          type: INTERNAL_TOKEN

      - registrationId: acme-business-connectors
        baseUrl: http://acme-crm-connector:8080
        exposes:
          toolConnectorTypes:
            - enterprise.acme.crm
            - enterprise.acme.erp
        auth:
          type: INTERNAL_TOKEN
```

每个服务启动时合并 `extensions.yaml` 与 core preset，得到统一 registration 集合。preset 由 `AGENTYARD_CHANNEL_GATEWAY_BASE_URL` / `AGENTYARD_AGENT_RUNTIME_BASE_URL` 决定 `baseUrl`，由 core 版本决定 `exposes`，operator 不在 yaml 中声明也不允许重写。当前阶段 core preset 暴露的 reference descriptor 是 core build 契约，operator 不在 compose / Helm 中启停单个 reference descriptor。

API、`agent-runtime` 和 `channel-gateway` 必须读取同一份 yaml。`registrationId` 用于部署观测，manifest 内的 `providerType` / `connectorType` 才是业务 descriptor。

部署要求：

1. `extensions.yaml` 是 extension 注册配置文件，和 compose / Helm values / environment overlay 一起发布
2. API、`agent-runtime`、`channel-gateway` 挂载的必须是同一份 extension 注册配置内容；Kubernetes 部署推荐使用同一个 ConfigMap / Secret 投影出的只读文件
3. 不允许为不同服务分别配置 extension URL 列表
4. 不允许 API 使用一份 definition 配置、runtime 使用另一份 invocation 配置
5. 每个服务都基于该配置加载 registration，但消费范围不同：API 通过 HTTP 拉取全部 registration 并执行 full descriptor 白名单校验，`agent-runtime` 只拉取 / 校验 `exposes.toolConnectorTypes` 非空的 registration，`channel-gateway` 只拉取 / 校验 `exposes.channelProviderTypes` 非空的 registration；runtime owner 对自身 core preset 走内部 `DescriptorProvider`，对自身相关 enterprise registration 走 HTTP `/extension/manifest`
6. `AGENTYARD_CHANNEL_GATEWAY_BASE_URL` / `AGENTYARD_AGENT_RUNTIME_BASE_URL` 必须在三服务 env 中指向同一服务地址；`baseUrl` 允许 path prefix，三服务按 `static-registration.md §5` 的 URL 规范化规则统一处理 host 大小写、默认端口和 trailing slash 后再计算 `registrationConfigDigest` 并发起调用；`channel-gateway` 自身和 `agent-runtime` 自身的 env 也必须设成各自可达的同一份 URL，避免 self-loaded preset 与其他服务对同一 preset 计算出不同 normalized `baseUrl`
7. internal token 必须通过 secret / Vault / secret file 注入所有 core service 与 enterprise extension service；compose 示例统一使用 `AGENTYARD_INTERNAL_TOKEN_FILE`，Kubernetes 示例必须把同一个 Secret 投影到相关 Deployment
8. enterprise extension 不读取 `extensions.yaml`，只读取 internal token；remote channel provider 如果需要推送 normalized event，还必须读取 `AGENTYARD_CHANNEL_GATEWAY_BASE_URL`
9. Helm values 在多个 Deployment 中重复展开不等同于同一份配置；必须保证最终 pod 看到的是同一个配置对象版本或同一个配置中心 key / version
10. 当前阶段不支持 registration hot reload；修改 `extensions.yaml` 或 core preset env 后必须滚动重启 API、`agent-runtime` 和 `channel-gateway`
11. registration config 变更的部署顺序固定为：enterprise extension service → `channel-gateway` / `agent-runtime` → API；API 必须最后更新并最后执行 aggregate validation

### 1.1 Channel Gateway Runtime Topology

`channel-gateway` 支持单副本或多副本部署。多副本部署采用 active-active 模型，不设置 primary / standby，不做 leader election，不做分片调度，也不由 Temporal 接管 channel runtime 调度。

状态权威：

1. Postgres 是 channel runtime state 权威，覆盖 `channel_profile`、`channel_profile_job`、`channel_profile_job_run`、`channel_outbound_delivery`、inbound event dedup 和 conversation binding
2. `channel-gateway` 通过数据库事务、唯一约束、状态机和幂等键维护一致性
3. Redis 只用于 provider job 的多副本去重锁，lock key 为 `channel-provider-job:{jobId}`
4. outbound delivery 不使用 Redis lock，不进入 provider job，也不做定时任务重试

副本语义：

1. 任意副本都可以处理 inbound webhook、remote normalized event、outbound delivery、provider job 和 channel admin action
2. 每个副本都可以运行 provider job `@Scheduled` tick；只有获得 Redis lock 的副本执行对应 job
3. 没有副本持有全局调度所有权；抢不到 lock 的副本直接跳过该 job
4. provider job 执行进程中断后依赖 lock TTL 释放；后续 scanner 发现 `RUNNING` job 的 lock 不存在且当前 run 超过 run row 快照的 `job_timeout_seconds` 时，把 run 标记为 `TIMED_OUT`，job 回到 `ACTIVE`
5. 崩溃恢复不做立即补跑；下一次执行仍按 `schedule_config` 计算出的 `next_run_at` 触发

滚动重启语义：

1. shutdown 开始后实例停止接受新请求，并在有界 grace period 内等待当前 HTTP invocation、outbound delivery 和 provider job 结束
2. 超过 grace period 的 in-flight outbound delivery 记录为 `FAILED` 并写错误日志；provider job run 由正常完成写回或后续 stale recovery 记录为失败 / 超时
3. 已提交到 Postgres 的 inbound dedup、conversation binding、delivery 和 job run 状态保持可追踪
4. provider job 不做 leader 接管；失败后的下一次执行由原 schedule 决定

## 2. Extension 私有依赖

企业 extension 可以独立声明自己的私有依赖：

```yaml
services:
  acme-channel-provider:
    image: acme/agentyard-channel-provider
    environment:
      AGENTYARD_INTERNAL_TOKEN_FILE: /run/secrets/agentyard_internal_token
      AGENTYARD_CHANNEL_GATEWAY_BASE_URL: http://channel-gateway:8080
      ACME_VAULT_ADDR: http://vault:8200
      ACME_PROVIDER_DB_URL: jdbc:postgresql://acme-provider-db:5432/provider
    secrets:
      - agentyard_internal_token

  acme-provider-db:
    image: postgres:17

  vault:
    image: hashicorp/vault
```

这些私有依赖不进入 core 的权威状态模型。

## 3. 安全治理

1. 只允许静态配置中声明的 extension 被加载
2. extension manifest 必须校验版本、类型、endpoint 和 schema
3. Extension Plane 跨服务调用必须使用内部鉴权
4. 企业投产默认采用 extension-managed secret
5. extension service 不应返回明文密钥到 session、Temporal history 或普通运行事件
6. Web 禁止配置 extension baseUrl
7. 所有 remote invocation 需要 timeout、错误码、trace id 和审计事件
8. 当前 `INTERNAL_TOKEN` 模式的信任域等于一次 AgentYard 部署域，不提供 extension service 之间的 zero-trust 隔离
9. provider 隔离边界是 registration / descriptor 白名单；同一个 registration 内不支持多租户互不可见
10. 如果一个 enterprise extension service 同时服务多个租户，租户级隔离必须由该 extension 私有实现和审计承担，Core 只校验 registration、descriptor 和 `channelProfileId.provider_type`

## 4. 版本策略

Service manifest envelope 必须包含：

```json
{
  "extensionApiVersion": 1,
  "coreMinVersion": "0.8.0",
  "coreMaxVersion": "0.9.x"
}
```

版本校验是启动门禁：

1. `extensionApiVersion` 是 Extension Plane protocol major version
2. Core 当前只支持一个 `extensionApiVersion`
3. service manifest `extensionApiVersion` 必须等于 core 支持版本
4. `coreVersion` 必须落在 `coreMinVersion` / `coreMaxVersion` 范围内
5. 不满足时返回 `EXTENSION_API_VERSION_INCOMPATIBLE`
6. Java / Python SDK major 与 `extensionApiVersion` 对齐，但 SDK version 不作为跨语言兼容权威

### 4.1 Extension Protocol Major Upgrade

`extensionApiVersion` 升级属于破坏性协议升级，不支持在同一套 Core stack 内原地滚动升级。

推荐升级形态是 blue / green stack：

```text
old stack:
  API v1
  agent-runtime v1
  channel-gateway v1
  extension services with extensionApiVersion = 1

new stack:
  API v2
  agent-runtime v2
  channel-gateway v2
  extension services with extensionApiVersion = 2
```

升级流程：

1. 保持 old stack 继续承接线上流量
2. 部署 new stack，使用新的 API / runtime / extension image、registration config 和 supported `extensionApiVersion`
3. new stack 内部完成 manifest validation、runtime owner validation、API aggregate validation 和 smoke check
4. 从 API ingress / gateway / DNS / load balancer 将入口流量从 old API 切到 new API
5. 回滚时把入口流量切回 old API，不在运行中的 stack 内做协议降级
6. new stack 稳定后再下线 old stack

规则：

1. old stack 与 new stack 不共享 runtime registry 进程，也不要求同一进程同时加载两个 `extensionApiVersion`
2. registration config、manifest validation 和 runtime invocation 必须在各自 stack 内自洽
3. major upgrade 不依赖 registration config change deployment order；它是整套 stack 的入口流量切换
4. 如果底层数据库需要跨 major migration，必须单独设计数据迁移 / 双写 / 回滚策略；Extension Plane protocol 本身不假设原地兼容
5. API 入口切流是协议主版本升级的唯一推荐切换点

## 5. Internal Auth Header

Extension Plane 跨服务调用必须使用内部鉴权，包括：

1. Core / runtime 调 remote extension service
2. remote provider service 调 `channel-gateway` internal normalized event API

当前支持 `INTERNAL_TOKEN`。Header 集合按调用类别拆分，避免在 service-level endpoint 中误带 descriptor 上下文，或在非 mutating 调用中误带 `Idempotency-Key`。

Remote extension service-level endpoint（例如 `GET /extension/manifest`、`GET /extension/health`）：

```http
Authorization: Bearer <internal-token>
X-AgentYard-Extension-Registration-Id: acme-business-connectors
```

规则：

1. `Authorization` 是 service-level endpoint 的唯一必需协议 header
2. `X-AgentYard-Extension-Registration-Id` 是可选部署观测上下文，用于日志和排障定位；extension service 不得把它当作授权事实源
3. service-level endpoint 调用前没有唯一 descriptor，不发送也不要求 `X-AgentYard-Extension-Descriptor-Type` / `X-AgentYard-Extension-Descriptor-Id`
4. service-level endpoint 不要求 `X-AgentYard-Trace-Id` / `X-AgentYard-Request-Id`
5. `registrationId` 对应的 `exposes` 白名单校验由 Core 侧 registry 执行，不由 extension service 根据 header 自行判定

Descriptor-level runtime invocation（manifest-declared tool invoke / channel outbound / provider job，以及固定的 `/internal/channel-events/normalized`）必须带 descriptor 上下文：

```http
Authorization: Bearer <internal-token>
X-AgentYard-Extension-Registration-Id: acme-business-connectors
X-AgentYard-Extension-Descriptor-Type: TOOL_CONNECTOR
X-AgentYard-Extension-Descriptor-Id: enterprise.acme.crm
X-AgentYard-Trace-Id: trace-xxx
X-AgentYard-Request-Id: request-xxx
```

需要 remote side 幂等识别的 invocation 在 descriptor-level header 基础上追加：

```http
Idempotency-Key: tool-call-xxx
```

适用范围固定为 tool connector invoke、channel provider `sendOutbound`、channel provider `runJob` 和 remote provider 推送
`/internal/channel-events/normalized`。`Idempotency-Key` 字段格式与 envelope `idempotencyKey`
一致性规则引用 `extension-protocol.md §2.0`。

Credential lifecycle invocation（create / rotate / revoke / validate）在 request body 中携带 descriptor 身份，不使用 `X-AgentYard-Extension-*` header：

```http
Authorization: Bearer <internal-token>
X-AgentYard-Trace-Id: trace-xxx
X-AgentYard-Request-Id: request-xxx
```

规则：

1. Core 仍通过 registry 使用 `registration.baseUrl + declared credential endpoint path` 选择调用目标
2. credential lifecycle request body 中的 `descriptor.type` / `descriptor.id` 是 extension 可见的 descriptor 身份事实源
3. request header 不携带 `X-AgentYard-Extension-Registration-Id`、`X-AgentYard-Extension-Descriptor-Type` 或 `X-AgentYard-Extension-Descriptor-Id`
4. extension 不得从 header 推断 registration 或 descriptor；需要日志关联时使用 body descriptor、`traceContext.traceparent` 和 `X-AgentYard-Request-Id`

Credential lifecycle create / rotate / revoke / validate 不使用 `Idempotency-Key`，request / response DTO 也不包含
`idempotencyKey`。重复提交和并发提交只由 Core API 对同一个 `integration_account.id` 的短时排他控制。
`/extension/manifest` 不接受 `Idempotency-Key`，被调用方应忽略而非拒绝。

`X-AgentYard-Trace-Id` 与 envelope `traceContext.traceparent` 必须解析为同一 trace。envelope `traceContext` 是协议事实源，HTTP header 是不解 envelope 的中间层透传值；调用方填写 header 时必须用与 `traceContext.traceparent` 同源的 trace id。被调用方日志、审计与 metrics 以 `traceContext.traceparent` 为准，`X-AgentYard-Request-Id` 仅用于单次调用的运维定位。

Token 来源：

1. 整个 AgentYard 部署共享一个 internal token，通过 secret / Vault / secret file 注入到所有 core service 与 enterprise extension service；推荐统一使用 `AGENTYARD_INTERNAL_TOKEN_FILE`
2. Core service 访问 `AGENTYARD_CHANNEL_GATEWAY_BASE_URL` / `AGENTYARD_AGENT_RUNTIME_BASE_URL` 对应的 `/extension/manifest`，以及访问 enterprise `/extension/manifest`，都校验同一个 token
3. remote provider service 调 `channel-gateway` `POST /internal/channel-events/normalized` 校验同一个 token
4. token 不在 yaml 文件中明文出现；`extensions.yaml` 只标注 `auth.type: INTERNAL_TOKEN`，token 值来自 env 或 secret 文件
5. `AGENTYARD_INTERNAL_TOKEN_FILE` 是部署约定，不是 registration schema 字段；SDK 可以提供读取 helper，但 extension service 只需要按该约定把文件内容作为 bearer token 校验 / 发送

当前取舍：

1. token rotation 需要同时滚动所有 core service 与 enterprise extension service
2. 任意持有 token 的 service 都处在同一部署信任域内；runtime invocation 中的 registration / descriptor header 只提供观测、请求一致性校验和审计定位，不提供每个 registration 独立密钥隔离
3. 当前阶段不实现 per-registration token、mTLS client identity 或动态 token exchange
4. 如未来要求 extension 间强隔离，必须把 `auth.type` 扩展为 per-registration credential 或 mTLS，并同步更新 registration config digest、manifest fetch 和 Core internal endpoint 鉴权规则

规则：

1. token 由部署配置注入，不进入 Web、release snapshot、session、Temporal history 或普通日志
2. 被调用方必须校验 `Authorization`
3. `registrationId` / descriptor header 只用于观测、请求一致性校验、审计和排障，不替代 token 鉴权；extension service 不得把 `registrationId` 当作授权事实源
4. tool invoke、channel outbound、provider job 和 remote normalized event 必须带 `Idempotency-Key`，字段规则引用 `extension-protocol.md §2.0`；credential lifecycle 明确不使用该 header
5. header 中不得携带 credential 明文、`externalSecretRef` 或其他 secret reference
6. remote provider service 调 Core internal endpoint 时，`X-AgentYard-Extension-Descriptor-Type` 必须为 `CHANNEL_PROVIDER`
7. core service 之间互调（包括 API → `channel-gateway` `/extension/manifest`、API → `agent-runtime` `/extension/manifest`）使用同一个 `INTERNAL_TOKEN`，不引入额外鉴权层

## 6. Extension Health Check

remote extension service 必须暴露 health endpoint：

```text
GET /health/live
GET /health/ready
GET /extension/health
```

语义：

1. `/health/live` 只表示进程存活
2. `/health/ready` 表示 extension 可接收 invocation
3. `/extension/health` 返回 extension 维度的详细状态，用于 API / runtime 聚合排障

`/extension/health` 响应示例：

```json
{
  "status": "READY",
  "extensionApiVersion": 1,
  "descriptors": [
    {
      "descriptorType": "TOOL_CONNECTOR",
      "descriptorId": "enterprise.acme.crm",
      "status": "READY"
    }
  ],
  "dependencies": [
    {
      "name": "vault",
      "status": "READY"
    }
  ]
}
```

聚合规则：

1. Core runtime 启动必须先完成 registry validation；validation 失败则进程退出
2. extension health 用于部署后 smoke check、依赖状态展示和运维排障，不参与 Core 服务启动门禁
3. extension health failed 不替代 manifest validation，也不导致 API、`agent-runtime` 或 `channel-gateway` 启动失败
4. extension health 失败可以导致部署 smoke check 失败，从而阻止本次 registration 配置发布标记为完成
5. 第一版不定义 `EXTENSION_HEALTH_FAILED` registry validation 错误码；health 诊断结果按 smoke check / 运维状态页展示，不进入 registry validation error code 集合
6. `registrationId` 是 Core 静态注册项字段，extension service 不需要在 `/extension/health` 中返回；Core 聚合展示时按调用的 registration config 补齐
7. core preset registration 不要求 `channel-gateway` / `agent-runtime` 暴露 `/extension/health`；core preset 的部署后验收只跑对应服务自身 `/health/ready` 与 registry validation endpoint

## 7. Invocation Metrics

Core runtime 必须记录 extension invocation metrics。

基础维度：

```text
service
registration_id
descriptor_type
descriptor_id
operation
status
error_category
error_code
retryable
attempt
```

指标：

```text
agentyard_extension_invocation_total
agentyard_extension_invocation_duration_seconds
agentyard_extension_invocation_retry_total
agentyard_extension_circuit_state
agentyard_extension_registry_validation_status
agentyard_channel_outbound_delivery_attempt_total
```

规则：

1. metrics 不包含 credential、payload、externalSecretRef、raw error
2. `error_code` cardinality 需要限制，单个 `descriptor_id` 下 distinct `error_code` 不超过 50；超过部分归入 `OTHER`
3. trace id 写日志 / audit，不作为 metrics label
4. outbound delivery metrics 必须使用独立 operation，例如 `channel.sendOutbound`，并按 `status`、`attempt` 分桶观测，不与 provider job metrics 混用

## 8. Audit Event Schema

Extension 相关审计事件使用统一 schema：

```json
{
  "eventType": "EXTENSION_INVOCATION",
  "occurredAt": "2026-04-25T00:00:00Z",
  "actorType": "SYSTEM",
  "actorId": null,
  "registrationId": "acme-business-connectors",
  "descriptorType": "TOOL_CONNECTOR",
  "descriptorId": "enterprise.acme.crm",
  "operation": "tools.invoke",
  "resourceType": "integration_account",
  "resourceId": "integration-account-xxx",
  "status": "FAILED",
  "errorCategory": "AUTH",
  "errorCode": "REMOTE_AUTH_FAILED",
  "traceId": "trace-xxx",
  "requestId": "request-xxx",
  "metadata": {}
}
```

事件类型：

```text
EXTENSION_MANIFEST_LOADED
EXTENSION_REGISTRY_VALIDATION_FAILED
EXTENSION_INVOCATION
CREDENTIAL_CREATE
CREDENTIAL_ROTATE
CREDENTIAL_REVOKE
CREDENTIAL_VALIDATE
PROVIDER_JOB_RUN
OUTBOUND_DELIVERY_ATTEMPT
```

审计事件禁止记录 credential、token、`externalSecretRef` 原文、完整 raw payload 或 provider 原始错误明文。

## 9. Timeout / Retry / Circuit Breaker

所有 remote invocation 必须有 timeout。

默认 timeout：

```text
manifest fetch: 5s
tool invoke: request execution.timeoutSeconds or 15s
channel sendOutbound: 15s
channel runJob: job timeout or 60s
credential create / rotate / revoke / validate: 30s
health check: 3s
```

Retry：

1. registry manifest fetch 可在启动阶段短重试，最终失败则启动失败并退出进程
2. tool retry 遵循 Tool Connector retry 合并规则
3. provider job 失败后不做立即重试；下一次执行仍由 `channel_profile_job.schedule_config` 计算
4. outbound delivery 不做定时任务重试；失败只记录 delivery 状态和错误信息
5. credential create / rotate / revoke / validate 不引入后台重试任务，只允许调用内瞬时重试；规则见 `credentials-and-persistence.md §9`

Circuit breaker：

```text
key = registrationId + descriptorType + descriptorId + operation
failureThreshold = 5 consecutive failures
openDuration = 60s
halfOpenMaxRequests = 1
```

`operation` 取值固定为以下集合，metrics `operation` label 与审计事件 `operation` 字段共用同一集合：

```text
tools.invoke
channel.sendOutbound
channel.runJob.{jobType}
credentials.create
credentials.rotate
credentials.revoke
credentials.validate
manifest.fetch
```

`channel.runJob.{jobType}` 把 `jobType` 拼进 key，避免不同类 job 共享熔断、互相影响。

规则：

1. circuit open 时直接返回 retryable extension failure
2. AUTH / BAD_REQUEST 默认不计入 circuit breaker
3. REMOTE_TIMEOUT / REMOTE_UNAVAILABLE / REMOTE_RATE_LIMITED 计入 circuit breaker
4. circuit state 必须进入 metrics，不进入业务 DTO
5. `manifest.fetch` 不计入 circuit breaker；启动阶段失败由 registry validation 启动门禁处理

## 10. Deployment Smoke Check

部署后 smoke check 使用 internal auth 调用，不经过 Web 页面。

推荐顺序：

```text
1. agent-runtime runtime owner validation
   GET {agentRuntimeBaseUrl}/internal/extension-registry/tool-connectors/validation

2. channel-gateway runtime owner validation
   GET {channelGatewayBaseUrl}/internal/extension-registry/channel-providers/validation

3. API aggregate validation
   GET {apiUrl}/internal/extension-registry/validation

4. Core preset service readiness
   GET {agentRuntimeBaseUrl}/health/ready
   GET {channelGatewayBaseUrl}/health/ready

5. 如有 enterprise extension，检查 extension health
   遍历已发布 registration config 中 source = OPERATOR_YAML 的 enterprise registration
   GET {extensionBaseUrl}/extension/health

6. 如有 enterprise extension，检查 extension readiness
   GET {extensionBaseUrl}/health/ready
```

规则：

1. smoke check 调用需要 internal auth 的 endpoint 时必须带 `Authorization`；service-level health / manifest 类 endpoint 不要求 `X-AgentYard-Trace-Id` 或 `X-AgentYard-Request-Id`
2. runtime owner validation 先于 API aggregate validation，便于定位是单 runtime 加载失败还是跨服务 drift
3. 部署后 smoke check 必须在 `channel-gateway` / `agent-runtime` 已完成更新且 API 最后更新完成后执行；此时 `REGISTRATION_CONFIG_DIGEST_MISMATCH` 视为真实配置漂移
4. smoke check 失败时不允许把本次 registration 配置发布标记为完成
5. core preset 不跑 `/extension/health`；`core-channel-gateway` / `core-agent-runtime` preset 只通过步骤 1 / 2 的 registry validation 和步骤 4 的服务自身 `/health/ready` 验收。§3 "preset 不享受任何豁免" 只适用于 registration merge、manifest / descriptor validation、runtime owner validation 和 digest 一致性，不扩大到 remote extension health endpoint
6. enterprise `/extension/health` 与 enterprise / core `/health/ready` 失败属于 smoke / diagnostics 失败，不回写 registry validation 状态，也不改变 runtime owner validation 的 `READY` / `NOT_READY` 判定
7. smoke check runbook 必须包含 enterprise extension 缺少 `AGENTYARD_INTERNAL_TOKEN_FILE` 或 token Secret 未挂载时的失败样例；预期结果是 manifest fetch、credential endpoint、remote invoke 或 normalized event internal call 返回 auth failure，而不是 registration yaml 校验失败
8. remote channel provider 上线 runbook 必须包含 test normalized event：创建 Channel Profile 后取得 `channelProfileId`，首选把它写入 provider public webhook URL path；如外部系统只支持全局 webhook，则在 provider 私有配置中建立 `external identity -> channelProfileId` 映射，再发送测试事件验证 `channel-gateway` 能校验 profile、providerType、auth、assistant binding 和 dedup
9. 第一版不要求 Core 保存 inbound verification status，也不提供 Core -> provider mapping API；test normalized event 的成功 / 失败只作为 runbook / smoke check 输出，不回写 `channel_profile`
