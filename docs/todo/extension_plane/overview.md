# Extension Plane 技术总览

## 1. 背景

Lynxus 需要同时支持两个场景：

1. 作为开源项目，提供清晰、可运行、可学习的 reference implementation
2. 作为企业实际投产系统，接入大量私有 channel、tool、账号、签名、权限、内网系统和定时任务逻辑

最容易产生企业定制化的区域是：

1. `channel-gateway`
2. Tool connector
3. 凭证、密钥、企业内网访问和私有运行状态

## 2. 核心目标

1. 开源 core 保持干净，只承载平台语义、稳定协议和 reference extension
2. 企业定制代码与开源 core 分开管理
3. 企业 extension 通过标准 manifest 和 invocation protocol 接入 core
4. Web 不硬编码企业逻辑，只根据 extension definition 渲染配置
5. Channel 与 Tool 的定制逻辑默认支持 remote sidecar
6. 企业敏感密钥默认由企业 extension 私有配置 / 私有存储管理；如果 extension 选择暴露 credential endpoints，core 只保存 opaque `externalSecretRef`
7. Channel 和 Tool 共用统一 Integration Account 作为可选的 core-side 账号 / 凭证引用；凭证不得分散写入 channel profile / tool config

## 3. 总体结构

```text
open-source lynxus core
  apps/api
  apps/worker
  apps/web
  apps/agent-runtime
  apps/knowledge-service
  apps/channel-gateway
  packages/extension-protocol
  packages/extension-sdk-jvm
  packages/extension-sdk-python
  reference extensions        # 逻辑归属 extension plane，物理仍在 apps/channel-gateway / apps/agent-runtime 内

private enterprise repo
  channel providers
  tool connectors
  private persistence / vault integration
  deployment overlays
```

部署时组合：

```text
Lynxus Core
  -> static extension registration
    -> enterprise channel provider services
    -> enterprise tool connector services
```

Registry 按运行边界分域：

```text
ToolConnectorRegistry
  owner: agent-runtime
  definition: API 聚合后给 Web / catalog 使用
  runtime: agent-runtime 执行

ChannelProviderRegistry
  owner: channel-gateway
  definition: API 聚合后给 Web / channel admin 使用
  runtime: channel-gateway 执行
```

"core runtime service" 在本套文档中固定指 `channel-gateway` / `agent-runtime` 两个服务，不包括 `worker` / `knowledge-service` 等 core app。后续主题文档复用同一术语。

API 是 definition 聚合层和可选 credential invocation owner，不是 extension runtime 中转层。API 在发布 assistant release 或更新 channel runtime profile 时校验已选择的 Integration Account，并把 Core 内部 `accountId` 与可用的 `externalSecretRef` 固化到对应 runtime snapshot；remote extension invocation 只接收 `externalSecretRef`，`accountId` 只供 Core internal resolver / runtime owner 内部使用。未选择账号或未通过 Core 配置 credential 时，不做凭证可用性预校验。

credential create / rotate / revoke / validate 属于可选 control-plane operation；只有 descriptor 声明 credential endpoints 时，API 才根据 Web / admin action 调用这些 endpoints。Tool invoke、Channel outbound 和
Provider job 属于 runtime invocation，分别由 `agent-runtime` / `channel-gateway` 直接调用
对应 extension endpoint，不经 API 中转。

所有 descriptor 都通过 `/extension/manifest` envelope 暴露。core runtime 服务（`channel-gateway` / `agent-runtime`）和企业 extension service 走同一套协议；runtime 自加载本服务 preset 只是用同一个 `DescriptorProvider` 跳过自身 HTTP，不建立第二套 descriptor 事实源。

`channel-gateway` 内置的 gateway-native channel provider、`agent-runtime` 内置的 reference tool connector 都通过本服务自己的 `/extension/manifest` 暴露给 API。本服务加载自己的 runtime registry 时直接读内部 `DescriptorProvider`，不走自身 HTTP。`/extension/manifest` HTTP endpoint 和 in-process registry 共用同一个 `DescriptorProvider`，保证两条出口的 descriptor 内容和 digest 完全一致。

extension registration 由两部分合并：core baked-in preset registration（指向 `channel-gateway` / `agent-runtime`）+ 部署 yaml 中 operator 声明的 enterprise extension。三服务读取同一份合并后的 registration 集合，但按运行边界消费不同 descriptor：API 拉取全部 registration 并聚合全部 definition，`agent-runtime` 只拉取 / 校验 / 执行 `exposes.toolConnectorTypes` 非空的 registration，`channel-gateway` 只拉取 / 校验 / 执行 `exposes.channelProviderTypes` 非空的 registration。tool-only extension 不成为 `channel-gateway` 的启动依赖，channel-only extension 不成为 `agent-runtime` 的启动依赖。

## 4. 技术边界

Extension Plane 的边界按 control plane、runtime owner 和 enterprise extension 三层划分。

1. API 负责聚合 definition、驱动 Web 配置、管理控制面对象，并发起 credential lifecycle invocation
2. `agent-runtime` 是 Tool Connector runtime owner
3. `channel-gateway` 是 Channel Provider runtime owner
4. 企业 extension service 负责私有 connector / provider 逻辑、私有密钥和私有依赖
5. Web 只消费 API 聚合后的 definition，不直接理解企业私有实现

`worker` / `knowledge-service` 是 core app，但不属于本期 Extension Plane 的 core runtime service，不 host extension manifest，也不拥有 extension registry。它们只通过既有业务链路消费 API / runtime owner 已固化的结果。

runtime invocation 不经 API 中转。credential invocation 只有在 descriptor 声明 credential endpoints 且管理员选择通过 Core 配置 credential 时才经 API 发起，因为它创建或变更
`integration_account` 的 `externalSecretRef` 和 `credential_status`。
API 聚合 descriptor 和 runtime 执行 descriptor 必须通过 registration、descriptor id、
definition digest 与 readiness validation 保持一致。

## 5. 设计原则

1. manifest 和 invocation protocol 是 core 与 extension 的唯一稳定契约
2. core baked-in preset 与企业 extension 使用同一套 manifest envelope
3. runtime registry 按服务 owner 分域加载，不共享一个全局执行 registry
4. Core 管理的凭证配置收敛到统一 Integration Account；runtime 只透传 snapshot 中已有的 opaque `externalSecretRef`，不要求每个 invocation 都必须有该字段
5. 企业私有密钥和私有运行状态默认留在 extension 边界内
6. schema-driven UI 的数据权威来自 JSON Schema，UI schema 只表达展示行为
