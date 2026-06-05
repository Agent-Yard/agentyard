# Static Registration 与 Registry

## 1. 目标

当前只做静态注册，不做动态插件市场。

静态注册在 core 部署配置里声明 extension service 地址；前端管理员不能填写 extension `baseUrl`。

所有 descriptor 都通过 service-level manifest envelope 暴露；跨服务发现使用 HTTP `GET /extension/manifest`，`channel-gateway` / `agent-runtime` 自加载本服务 preset 时使用同一个 `DescriptorProvider` 产出同一份 envelope，不另建第二套 descriptor 事实源。

## 2. 配置

静态注册分两部分合并：

1. core baked-in preset registration：指向 `channel-gateway` / `agent-runtime`，由 core 自身构建，operator 不需要在 yaml 中声明
2. operator yaml registration：在部署 yaml 中声明 enterprise extension service

合并后形成一份统一的 registration 集合，三服务（API / `agent-runtime` / `channel-gateway`）按同一份集合读取，但按 actor 固定消费范围：

1. API 消费全部 registration，并通过 HTTP 拉取全部 service manifest，用于 definition 聚合、Web 展示、account subject 校验和 API aggregate validation
2. `agent-runtime` 只消费 `exposes.toolConnectorTypes` 非空的 registration；`exposes.toolConnectorTypes` 为空的 channel-only registration 不进入 ToolConnectorRegistry manifest fetch、startup gate 或 runtime validation
3. `channel-gateway` 只消费 `exposes.channelProviderTypes` 非空的 registration；`exposes.channelProviderTypes` 为空的 tool-only registration 不进入 ChannelProviderRegistry manifest fetch、startup gate 或 runtime validation
4. 一个 registration 同时声明 channel provider 和 tool connector 时，两个 runtime owner 都可以拉取同一个 manifest，但各自只校验和加载自己负责的 descriptor 集合
5. API 始终负责 full manifest validation 和 full whitelist validation；runtime owner 的局部加载不替代 API aggregate validation

静态注册配置描述的是 extension service 注册项，不直接等同于业务
`providerType` / `connectorType`。

```yaml
agentyard:
  extensions:
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

字段语义：

1. `registrationId` 是部署层身份，用于日志、health、readiness 和运维定位
2. `baseUrl` 是 extension service 地址，只能由部署配置提供
3. `exposes.channelProviderTypes` / `exposes.toolConnectorTypes` 是部署期允许该 service 暴露的业务 descriptor 白名单
4. manifest 内的 `providerType` / `connectorType` 是业务权威身份，进入 DB、API DTO、Web 下拉和 runtime dispatch
5. `source` 是 core 合并后派生的注册来源：core preset 为 `CORE_PRESET`，operator yaml 为 `OPERATOR_YAML`；operator yaml 不需要填写

一个 extension service 可以同时暴露多个 provider / connector。部署注册项身份与业务 descriptor 必须拆开，避免把运维命名写入业务模型。

### 2.0 Core Preset Registration

core 自动注入两条 preset registration，operator yaml 不需要也不允许重复声明：

```yaml
- registrationId: core-channel-gateway
  baseUrl: ${AGENTYARD_CHANNEL_GATEWAY_BASE_URL}
  source: CORE_PRESET
  exposes:
    channelProviderTypes:
      - feishu
  auth:
    type: INTERNAL_TOKEN

- registrationId: core-agent-runtime
  baseUrl: ${AGENTYARD_AGENT_RUNTIME_BASE_URL}
  source: CORE_PRESET
  exposes:
    toolConnectorTypes:
      - simple-http
      - business-code-secret-http
      - mcp
  auth:
    type: INTERNAL_TOKEN
```

规则：

1. preset registration 的 `registrationId` 保留前缀 `core-`，operator yaml 不允许使用该前缀；违反时按 §6.3 `REGISTRATION_CONFIG_INVALID` 报错并启动失败
2. preset 的 `exposes` 列表由 core 版本决定，随 core 升级变化；当前阶段 `core-channel-gateway` 固定暴露 `feishu`，`core-agent-runtime` 固定暴露 `simple-http` / `business-code-secret-http` / `mcp`
3. preset 的 `baseUrl` 来自 core 部署 env，operator 不在 yaml 中重写
4. 在各 actor 的消费范围内，preset registration 与 enterprise registration 走完全相同的 manifest 拉取和 descriptor 白名单校验流程，不享受任何"内置"豁免
5. preset 与 yaml 合并后，整份集合按统一规则计算 `registrationConfigDigest`
6. preset 的 `auth.type` 仅对其他服务通过 HTTP 拉取本服务 manifest 时生效；本服务自加载 preset 时走内部 `DescriptorProvider`，不校验 token，但 `auth.type` 仍参与 `registrationConfigDigest` 计算以保证三服务一致
7. 当前阶段不提供 reference descriptor disable 开关，也不支持 operator 把 core preset `exposes` 改为空；core preset manifest 必须满足 `extension-protocol.md §2.1` 的非空 descriptor envelope 规则

### 2.1 配置来源约束

API、`agent-runtime` 和 `channel-gateway` 必须读取同一份 extension registration source。registration source = core preset registration ∪ operator yaml registration。

“同一份 yaml”指同一个部署配置对象，而不是三份内容相似的环境变量：

```text
core preset registration  ┐
                          ├─> merged registration set
operator yaml             ┘     -> API ExtensionDefinitionRegistry
                                 -> agent-runtime ToolConnectorRegistry
                                 -> channel-gateway ChannelProviderRegistry
```

operator yaml 允许的来源形态：

1. 同一个只读配置文件，例如 `/etc/agentyard/extensions.yaml`
2. 同一个 ConfigMap / Secret 投影出的只读文件
3. 同一个部署配置中心 key / version

不允许的形态：

1. API、`agent-runtime`、`channel-gateway` 分别维护自己的 extension URL env list
2. API 只配置 definition service，runtime 另配 invocation service
3. Web 或管理员页面提交 extension `baseUrl`
4. operator 在 yaml 中声明 `core-` 前缀 registration 或重写 core preset

各服务按运行边界消费同一份合并后的配置：

1. API 通过 HTTP 调每条 registration 的 `/extension/manifest` 加载全部 descriptor，用于 definition endpoint、credential lifecycle routing、account subject 校验和控制面校验
2. `agent-runtime` 只加载 `exposes.toolConnectorTypes` 非空的 registration。其中 `core-agent-runtime` 这条 registration 由 `agent-runtime` 自身满足，加载时直接读内部 `DescriptorProvider`，不走自身 HTTP；其他 tool connector registration 通过 HTTP 拉取
3. `channel-gateway` 只加载 `exposes.channelProviderTypes` 非空的 registration。其中 `core-channel-gateway` 这条 registration 由 `channel-gateway` 自身满足，加载时直接读内部 `DescriptorProvider`，不走自身 HTTP；其他 channel provider registration 通过 HTTP 拉取
4. runtime owner 不拉取、不校验、不等待与自身 descriptor 类型无关的 registration；无关 service 的 manifest fetch / schema / readiness 失败不阻塞该 runtime owner 启动

同一份配置源本身不替代 manifest 校验。服务启动时仍必须按自身消费范围拉取 manifest（preset 走 in-process `DescriptorProvider`，其他走 HTTP），并校验 manifest 返回的相关 descriptor 与 `exposes` 白名单完全一致。API 是唯一要求对每条 registration 做 full manifest / full whitelist 校验的 actor。

`DescriptorProvider` 是每个 runtime owner 内部的单一 descriptor 数据源：

```text
DescriptorProvider
  -> /extension/manifest HTTP endpoint     # 给 API
  -> in-process registry loading           # 给本服务 runtime
```

两条出口共用同一个序列化路径和 canonical JSON 实现，确保本服务计算出的 descriptor digest 与 API 通过 HTTP 拉到再计算的 digest 完全一致。

### 2.2 Manifest 拉取规则

每个 registration（包括 core preset 和 enterprise yaml registration）使用固定 endpoint 暴露 service-level manifest：

```text
GET {normalizedBaseUrlWithoutTrailingSlash}/extension/manifest
```

`baseUrl` 允许 path prefix；manifest endpoint 按 §5 的 normalized `baseUrl` 拼接固定 path。例如 `https://ext.example.com/agentyard` 对应 `GET https://ext.example.com/agentyard/extension/manifest`。

加载形态：

1. API 永远通过 HTTP 调用此 endpoint，对所有 registration 一视同仁，并校验完整 manifest envelope
2. `agent-runtime` 只对 `exposes.toolConnectorTypes` 非空的 registration 调用此 endpoint；加载 `core-agent-runtime` preset registration 时直接读本进程内 `DescriptorProvider`，不发起自身的 HTTP 请求
3. `channel-gateway` 只对 `exposes.channelProviderTypes` 非空的 registration 调用此 endpoint；加载 `core-channel-gateway` preset registration 时直接读本进程内 `DescriptorProvider`，不发起自身的 HTTP 请求
4. runtime owner 拉取同时包含两类 descriptor 的 manifest 时，只把自身负责的 descriptor collection 纳入 startup validation、runtime registry 和 definition digest comparison；另一类 descriptor collection 由 API 和对应 runtime owner 校验
5. 无论加载形态如何，被当前 actor 消费的 descriptor envelope、JSON Schema、canonical JSON 序列化和 digest 计算路径完全一致

响应结构：

```json
{
  "extensionApiVersion": 1,
  "coreMinVersion": "0.8.0",
  "coreMaxVersion": "0.9.x",
  "descriptors": {
    "channelProviders": [
      {
        "providerType": "enterprise.acme.internal-im",
        "title": "Acme Internal IM",
        "accountConfigSchema": {},
        "accountConfigUiSchema": [],
        "credentialSchema": {},
        "credentialUiSchema": [],
        "configSchema": {},
        "configUiSchema": [],
        "defaultConfig": {},
        "jobDefinitions": [],
        "endpoints": {
          "sendOutbound": "/channel/send-outbound",
          "createCredential": "/credentials",
          "rotateCredential": "/credentials/rotate",
          "revokeCredential": "/credentials/revoke",
          "validateCredential": "/credentials/validate"
        }
      }
    ],
    "toolConnectors": [
      {
        "connectorType": "enterprise.acme.crm",
        "title": "Acme CRM",
        "accountConfigSchema": {},
        "accountConfigUiSchema": [],
        "configSchema": {},
        "configUiSchema": [],
        "credentialSchema": {},
        "credentialUiSchema": [],
        "operationMappingSchema": {},
        "operationMappingUiSchema": [],
        "endpoints": {
          "invoke": "/tools/invoke",
          "createCredential": "/credentials",
          "rotateCredential": "/credentials/rotate",
          "revokeCredential": "/credentials/revoke",
          "validateCredential": "/credentials/validate"
        }
      }
    ]
  }
}
```

API full manifest 校验规则：

1. `extensionApiVersion` / `coreMinVersion` / `coreMaxVersion` 在 service envelope 级校验
2. `descriptors.channelProviders[*].providerType` 必须完全等于注册项 `exposes.channelProviderTypes`
3. `descriptors.toolConnectors[*].connectorType` 必须完全等于注册项 `exposes.toolConnectorTypes`
4. service manifest 不允许返回注册项未声明的 descriptor
5. 注册项声明的 descriptor 未在 service manifest 返回时启动失败
6. 空数组表示该 service 不暴露对应 descriptor 类型
7. 注册项未声明某个 `exposes` 数组时，按空数组处理
8. manifest descriptor endpoints 必须是相对 path，实际调用地址只能由静态注册项 `baseUrl` 拼接得到
9. endpoint key 是协议固定值，path 由 manifest 声明；registry 只校验 path 形态，不要求 path 等于文档示例

Runtime owner 局部校验规则：

1. `agent-runtime` 只校验 `descriptors.toolConnectors[*].connectorType` 与注册项 `exposes.toolConnectorTypes` 完全一致
2. `channel-gateway` 只校验 `descriptors.channelProviders[*].providerType` 与注册项 `exposes.channelProviderTypes` 完全一致
3. 当前 runtime owner 负责的 descriptor type 出现 missing、unexpected、duplicate、相关 descriptor schema invalid，或 service envelope version incompatible 时，该 runtime owner 启动失败
4. 当前 runtime owner 不负责的 descriptor type 不进入该 runtime owner 的 missing / unexpected / duplicate 判定，也不进入该 runtime owner 的 descriptor digest 输出
5. API aggregate validation 负责发现 API full registry 与两个 runtime owner 局部 registry 的漂移

## 3. 启动流程

Extension registry 按运行边界分为两个域：

```text
ToolConnectorRegistry
  owner: agent-runtime
  config/definition consumer: API catalog + Web
  runtime consumer: agent turn / playbook tool task

ChannelProviderRegistry
  owner: channel-gateway
  config/definition consumer: API channel admin + Web
  runtime consumer: inbound webhook / outbound delivery / provider job
  provider modes: gateway-native provider + remote provider service
```

API 不执行 extension runtime 逻辑，只通过 HTTP 拉取每条 registration 的 manifest，聚合 definition 给 Web 和控制面配置使用。

```text
merged registration set
  -> API ExtensionDefinitionRegistry
    -> HTTP fetch every registration's /extension/manifest
    -> aggregate all channel provider + tool connector definitions
    -> expose definition endpoints for Web

merged registration set
  -> channel-gateway ChannelProviderRegistry
    -> filter registrations where exposes.channelProviderTypes is non-empty
    -> in-process DescriptorProvider for core-channel-gateway preset
    -> HTTP fetch /extension/manifest for other channel provider registrations
    -> execute channel provider invocation

merged registration set
  -> agent-runtime ToolConnectorRegistry
    -> filter registrations where exposes.toolConnectorTypes is non-empty
    -> in-process DescriptorProvider for core-agent-runtime preset
    -> HTTP fetch /extension/manifest for other tool connector registrations
    -> execute tool connector invocation
```

当前不做动态同步，也不把所有 runtime invocation 统一中转到 API。API、`channel-gateway`、`agent-runtime` 从同一份合并 registration 集合加载；API 拉取全部 manifest，runtime owner 只拉取自身 descriptor 类型需要的 manifest，并在启动阶段完成对应范围的一致性校验。

## 4. 启动失败策略

Registry 不是后台异步初始化组件。API、`channel-gateway`、`agent-runtime` 必须在进程启动阶段同步完成 registration config load、manifest fetch、manifest schema validation、descriptor whitelist validation、runtime owner validation 和 API aggregate validation。任一当前 actor 负责范围内的失败都视为启动失败，进程直接退出；服务不得以长期 `NOT_READY` 状态继续运行，也不在运行期自动重试恢复。runtime owner 不负责无关 descriptor 类型的 registration manifest fetch，因而不被无关 extension service 的不可用性阻塞。

| # | 启动失败场景 | 典型发现 phase | §6.3 错误码 |
| --- | --- | --- | --- |
| 1 | 当前 actor 负责加载的 extension manifest 拉取失败 | manifest fetch | `MANIFEST_FETCH_FAILED` |
| 2 | 当前 actor 负责范围内的 manifest schema 校验失败 | manifest fetch | `MANIFEST_SCHEMA_INVALID` |
| 3 | manifest 返回的 `providerType` / `connectorType` 不在注册项 `exposes` 白名单内 | registry load | `UNEXPECTED_DESCRIPTOR` |
| 4 | 注册项 `exposes` 声明的 descriptor 没有被对应 service manifest 返回 | registry load | `MISSING_DESCRIPTOR` |
| 5 | 同一个 `providerType` / `connectorType` 被多个注册项重复暴露 | registry load | `DUPLICATE_DESCRIPTOR` |
| 6 | extension API 版本不匹配 | manifest fetch | `EXTENSION_API_VERSION_INCOMPATIBLE` |
| 7 | API registry 与 runtime registry descriptor id 不一致 | aggregate validation | `REGISTRY_DESCRIPTOR_MISMATCH` |
| 8 | API registry 与 runtime registry definition digest 不一致 | aggregate validation | `REGISTRY_DEFINITION_DIGEST_MISMATCH` |
| 9 | registration config 加载失败或缺失 | registration config load | `REGISTRATION_CONFIG_UNAVAILABLE` |
| 10 | registration config 内容非法（如 operator yaml 使用 `core-` 前缀、字段缺失） | registration config load | `REGISTRATION_CONFIG_INVALID` |
| 11 | API 与 runtime 的 `registrationConfigDigest` 不一致 | aggregate validation | `REGISTRATION_CONFIG_DIGEST_MISMATCH` |
| 12 | API aggregate validation 调用 runtime registry 失败 | aggregate validation | `RUNTIME_REGISTRY_UNREACHABLE` |

错误码按发现 phase 归属，不按固定 actor 归属。manifest fetch / schema / version 错误既可能由 runtime owner 在加载自身 registry 时发现，也可能由 API 在聚合 definition registry 时发现；runtime owner 只会针对自身消费范围产生这类错误。启动失败日志、one-shot self-check 和部署 smoke check 必须用 `service`、`registryType`、`registrationId` 和 `details.phase` 标明实际发生位置。具体字段见 §6.3。

Extension health 不属于启动失败场景。第一版只用 manifest loading、schema validation、registration config validation、runtime owner validation 和 API aggregate validation 作为启动门禁。remote enterprise extension 的 `/extension/health` 与 extension service 自身的 `/health/ready` 只用于部署后 smoke check、依赖状态展示和运维诊断；core preset registration 不跑 `/extension/health`，只跑对应 core service 自身 `/health/ready` 与 registry validation。health 失败不新增 registry validation 错误码，也不改变 Core 服务的启动结果。

## 5. 一致性规则

Tool connector 一致性：

```text
API loaded tool connector descriptor ids
  == agent-runtime loaded tool connector ids

API loaded tool connector definition digests
  == agent-runtime loaded tool connector definition digests
```

Channel provider 一致性：

```text
API loaded channel provider descriptor ids
  == channel-gateway loaded channel provider ids

API loaded channel provider definition digests
  == channel-gateway loaded channel provider definition digests
```

一致性比较使用 manifest 中的业务 descriptor id，不使用 `registrationId`。
API loaded descriptor ids 指 API full registry 按 descriptor type 投影后的结果；runtime loaded descriptor ids 指对应 runtime owner 局部加载结果。tool-only registration 不进入 `channel-gateway` 比较，channel-only registration 不进入 `agent-runtime` 比较。

definition digest 使用每类 descriptor 自己的 canonical object 计算，不能由各语言实现自行选择字段。digest 只覆盖会影响运行契约、保存态校验、调用能力或语义身份的字段；不包含 `baseUrl`、auth token、registrationId、`definitionDigest` 自身、runtime state、任何 secret、展示元数据、UI schema 或创建默认值。

Definition digest 的计算顺序固定为：

1. 解析 manifest 并完成 manifest schema validation
2. 按 descriptor type 构造 canonical object，并在此阶段完成字段存在性 normalize：缺失字段必须 materialize 成该 descriptor digest 规则定义的 `null`、空 object 或排序后的数组；进入 digest 的 JSON Schema 使用 validation-only schema view
3. 对 normalize 后的 canonical object 运行 AgentYard canonical JSON 序列化
4. 对 canonical bytes 计算 `sha256:<lowercase-hex>`

canonicalizer 只负责确定性 JSON 序列化，不负责业务语义归一化或默认值推导。manifest 中省略字段与显式填写等价缺省值时，必须先归一成同一个 canonical object，再得到同一个 definition digest。若某字段缺失且该 descriptor digest 规则没有定义缺省值，则 manifest validation failed，而不是由 canonicalizer 猜测。

Definition endpoint 可以继续返回完整 descriptor、UI schema 和默认值给 Web。Definition digest 不使用这些展示 / 创建体验字段：

1. descriptor 和 job 的 `title` / `description`
2. 所有 `*UiSchema`
3. 所有 descriptor-level `default*` 字段
4. UI schema 内部的 `defaultValue`
5. JSON Schema 内部的 `title` / `description` / `default`

进入 digest 的 JSON Schema 字段必须先递归剥离上述 JSON Schema annotation keyword，再作为 JSON value 进入 canonical object。该剥离只作用于 JSON Schema annotation keyword 位置；`properties`、`$defs`、`definitions`、`patternProperties`、`dependentSchemas` 下名为 `title` / `description` / `default` 的 property-map entry name 不应被剥离。`default` keyword 不触发字段注入，也不参与 digest。canonicalizer 仍只负责确定性 JSON 序列化，不负责识别或剥离这些业务字段。

### 5.1 Channel Provider Definition Digest

Channel Provider definition digest 的输入固定为下列 canonical object：

```json
{
  "descriptorType": "CHANNEL_PROVIDER",
  "providerType": "enterprise.acme.internal-im",
  "accountConfigSchema": {},
  "credentialSchema": null,
  "endpoints": {
    "sendOutbound": "/channel/send-outbound",
    "runJob": "/channel/run-job",
    "createCredential": null,
    "rotateCredential": null,
    "revokeCredential": null,
    "validateCredential": null
  },
  "configSchema": {},
  "jobDefinitions": [
    {
      "jobType": "PULL_MESSAGES",
      "jobConfigSchema": {}
    }
  ]
}
```

字段规则：

1. `descriptorType` 固定为 `CHANNEL_PROVIDER`，用于让 digest 输入自描述，不依赖外层 map key
2. `providerType` 来自 channel provider descriptor；`title` / `description` 是展示元数据，不进入 digest
3. `accountConfigSchema` 属于 Integration Account 级配置；`configSchema` 属于 Channel Profile 级运行配置，二者不得合并
4. `accountConfigSchema`、`credentialSchema`、`configSchema`、`jobDefinitions[*].jobConfigSchema` 使用 validation-only schema view；缺失的可选 schema object 在 digest normalize 阶段 materialize 为 `null`
5. digest canonical object 的 `endpoints` 必须只包含上述固定 key；值为 manifest declared relative path 或 `null`，不得包含 `baseUrl`、registration 信息或 auth 信息
6. `credentialCapability` 不进入 digest；它是 API definition projection，事实由 `credentialSchema` 与 credential endpoint keys 推导
7. manifest validation 必须要求 `endpoints.sendOutbound` 存在且为 declared relative path；第一版不支持 inbound-only channel provider
8. `jobDefinitions` 缺省 normalize 为 `[]`；`jobDefinitions` 非空时 `endpoints.runJob` 必须存在且为 declared relative path，`jobDefinitions` 为空时 manifest 不得声明 `endpoints.runJob`；digest normalize 后仍 materialize 固定 `runJob` key，缺失时值为 `null`
9. `jobDefinitions` 在进入 digest 前必须按 `jobType` 升序排序；同一 provider descriptor 内重复 `jobType` 必须先 validation failed
10. `jobDefinitions[*]` 只包含 `jobType` 与 `jobConfigSchema`；job `title` / `description`、`jobConfigUiSchema`、`defaultSchedule`、`defaultEnabled`、`defaultJobTimeoutSeconds` 都不进入 digest
11. `defaultConfig` 是创建 / 重置 channel profile 时回填 `channel_profile.config` 的默认值，不进入 Channel Provider definition digest
12. JSON Schema 不做字符串化，作为 JSON object 进入 canonical object，并继续受 AgentYard canonical JSON profile 约束
13. `extensionApiVersion`、`coreMinVersion`、`coreMaxVersion`、service envelope、`registrationId`、`source`、`baseUrl`、auth、`accountId`、`externalSecretRef`、credential status、assistant binding、template binding、channel profile 保存态配置和 runtime state 都不进入该 digest

### 5.2 Tool Connector Definition Digest

Tool Connector definition digest 的输入固定为下列 canonical object：

```json
{
  "descriptorType": "TOOL_CONNECTOR",
  "connectorType": "enterprise.acme.crm",
  "accountConfigSchema": {},
  "credentialSchema": null,
  "configSchema": {},
  "operationMappingSchema": {},
  "endpoints": {
    "invoke": "/tools/invoke",
    "createCredential": null,
    "rotateCredential": null,
    "revokeCredential": null,
    "validateCredential": null
  }
}
```

字段规则：

1. `descriptorType` 固定为 `TOOL_CONNECTOR`，用于让 digest 输入自描述，不依赖外层 map key
2. `connectorType` 来自 tool connector descriptor；`title` / `description` 是展示元数据，不进入 digest
3. `accountConfigSchema` 属于 Integration Account 级配置；`configSchema` 属于 Tool Resource connector 级运行配置；`operationMappingSchema` 属于 Tool Operation 映射配置，三者不得合并
4. `accountConfigSchema`、`credentialSchema`、`configSchema`、`operationMappingSchema` 使用 validation-only schema view；缺失的可选 schema object 在 digest normalize 阶段 materialize 为 `null`
5. `endpoints` 必须只包含上述固定 key；`invoke` 是 required declared relative path，credential endpoint key 的值为 declared relative path 或 `null`；不得包含 `baseUrl`、registration 信息或 auth 信息
6. `credentialCapability` 不进入 digest；它是 API definition projection，事实由 `credentialSchema` 与 credential endpoint keys 推导
7. Tool Resource / Tool Operation / operation list 不进入 digest；connector descriptor 不声明 operation 集合
8. Tool Resource 保存态的 `connector.config`、`operationMappings`、retry policy、timeout policy、account snapshot、`accountId`、`externalSecretRef`、credential status 和 runtime state 都不进入该 digest
9. JSON Schema 不做字符串化，作为 JSON object 进入 canonical object，并继续受 AgentYard canonical JSON profile 约束；所有 `*UiSchema` 都不进入 digest

`registrationConfigDigest` 使用合并后的 registration config 规范化 JSON 计算，覆盖：

1. core preset registration 与 operator yaml registration 的完整合并结果
2. `registrationId`
3. `source`
4. 解析 env / placeholder 并规范化后的 `baseUrl`
5. `exposes.channelProviderTypes`
6. `exposes.toolConnectorTypes`
7. `auth.type`

`registrationConfigDigest` 不包含：

1. `AGENTYARD_INTERNAL_TOKEN` 或任何 token / secret 明文
2. secret 文件路径中的内容
3. manifest 拉取结果
4. descriptor definition 内容
5. readiness / health / runtime 状态

`baseUrl` 规范化规则：

1. `baseUrl` 必须是合法 URL，scheme 只允许 `http` 或 `https`
2. `baseUrl` 允许 path prefix，例如 `https://ext.example.com/agentyard/extensions`
3. `baseUrl` 不允许包含 userinfo、query string 或 fragment
4. host 在进入 digest 前统一转为小写
5. 默认端口归一化：`http:80` 与未写端口等价，`https:443` 与未写端口等价；非默认端口必须保留
6. path prefix 末尾的 `/` 在进入 digest 前移除；根路径统一表示为无 trailing slash 的 origin，例如 `https://ext.example.com`
7. path prefix 内部的 percent encoding 不做业务语义重写；operator 必须使用合法 URL 表达同一地址，避免把不同编码形式当作同一部署项
8. digest 输入和运行时 HTTP 调用都必须使用同一份 normalized `baseUrl`

计算规则：

1. 三服务必须先用同一套 core preset 注入规则和同一份 operator yaml 生成 merged registration set
2. env placeholder 必须在计算前解析；无法解析视为 `REGISTRATION_CONFIG_INVALID`
3. 解析后的 `baseUrl` 必须先按本节规则规范化；规范化失败视为 `REGISTRATION_CONFIG_INVALID`
4. registration 按 `registrationId` 升序排序，`exposes` 列表按字符串升序排序
5. 使用本节 canonical JSON 算法序列化后计算 `sha256:<lowercase-hex>`
6. `auth.type` 参与计算；token 值不参与计算
7. `registrationConfigDigest` 与 definition digest 分离：前者证明部署注册项一致，后者证明 descriptor 定义一致

canonical JSON 序列化算法是协议事实，定义在 `packages/extension-protocol`。当前阶段不引入第三方
JCS 库作为信任根；Java SDK 与 Python SDK 各自实现同一个小型 `AgentYardCanonicalJson` helper，
并由同一份 fixtures 约束为 byte-for-byte 一致。

该 helper 只服务 descriptor definition digest 与 `registrationConfigDigest`，不是通用 JSON 序列化器。
算法采用 RFC 8785 JCS 的确定性 object key 排序、字符串转义和 UTF-8 bytes 输出思路，但把输入收窄为
AgentYard canonical JSON profile，避免实现完整 ECMAScript number serialization。

Digest 规则：

1. descriptor definition digest 与 `registrationConfigDigest` 都使用 `AgentYardCanonicalJson` canonical bytes
2. digest 计算为 `sha256` over UTF-8 canonical bytes，输出形态固定为 `sha256:<lowercase-hex>`
3. digest 前不在 canonicalizer 中做业务语义归一化；URL、env placeholder、default 注入等必须在进入 canonical object 前完成
4. descriptor digest 输入字段和 registration config digest 输入字段必须由 fixtures 锁定，不能由各语言实现自行选择

AgentYard canonical JSON profile 输入约束：

1. object 不允许 duplicate keys；解析阶段发现重复 key 必须失败
2. string 必须是合法 Unicode
3. object key 排序按 RFC 8785/JCS 的 UTF-16 code unit 字典序执行；fixtures 必须覆盖非 ASCII key
4. number 只允许 JSON integer，且必须在 `[-9007199254740991, 9007199254740991]` 安全整数范围内
5. float、decimal、exponent、`NaN`、`Infinity`、负零、大整数、高精度 decimal、版本号、金额、ID 等必须建模为 string
6. boolean / null / array / object 按 JSON 语义递归处理
7. JSON Schema 内嵌对象递归适用同一 profile

helper 实现责任：

1. Java SDK 在 `packages/extension-sdk-jvm` 内提供 helper，可以复用 Jackson 3 做 tokenization / tree 读取，
   但 canonical bytes emitter 必须手写；parser 必须启用 duplicate key 拒绝
2. Python SDK 在 `packages/extension-sdk-python` 内提供 helper，可以复用 Python 标准库 `json` 做 tokenization，
   但必须通过 `object_pairs_hook` 拒绝 duplicate key，canonical bytes emitter 必须手写
3. helper 必须同时暴露 `canonicalBytes(value)`、`canonicalize(rawJson)` 和 `sha256Digest(value/rawJson)`
   这类统一入口，调用方不得直接使用普通 `ObjectMapper.writeValueAsString` 或 `json.dumps` 计算 digest
4. helper 不承诺支持任意业务 payload；不满足本 profile 的输入直接失败

Java SDK、Python SDK 与 `agent-runtime` 对 Python SDK 的使用共用同一份 canonical JSON 测试 fixtures，跨语言实现必须对同一份输入产出 byte-for-byte 相同的 canonical bytes 和 digest。

不一致时，发现问题的服务必须启动失败并退出。可以通过启动阶段同步校验或显式 one-shot registry validation 命令完成校验，不要求引入 registry snapshot 持久化。

## 6. Registry Validation API

Registry validation API 是内部运维 / diagnostics API，不是 Web 业务配置 API。

Validation 分两层：

1. runtime owner validation：证明某个 runtime 自己的本地 registration config 与实际 manifest registry 一致
2. API aggregate validation：证明 API definition registry 与 runtime registry 之间没有漂移

### 6.1 Runtime Owner Validation

Runtime owner 暴露自己的 registry validation endpoint：

```text
agent-runtime
  GET /internal/extension-registry/tool-connectors/validation

channel-gateway
  GET /internal/extension-registry/channel-providers/validation
```

runtime validation 不依赖 API。它只比较本服务本地看到的合并 registration 集合中与自身 descriptor type 相关的 registration，以及本服务实际加载出来的局部 registry：

```text
merged registration set
  -> filter by runtime owner descriptor type
  -> expected descriptor ids (per relevant exposes whitelist)

manifest loading result (in-process for matching core- preset, HTTP for matching enterprise registrations)
  -> loaded descriptor ids

expected descriptor ids == loaded descriptor ids
```

`agent-runtime` 校验所有 tool connector registration 的 `exposes.toolConnectorTypes` 与实际加载的 tool connector registry。
`channel-gateway` 校验所有 channel provider registration 的 `exposes.channelProviderTypes` 与实际加载的 channel provider registry。

当前 runtime owner 负责的 core preset registration（`core-agent-runtime` / `core-channel-gateway`）和 enterprise registration 走完全相同的校验路径，preset 不享受任何豁免。另一个 runtime owner 的 core preset 不进入本服务 validation。

runtime validation 至少发现当前 runtime owner 负责范围内的以下问题：

1. configured descriptor 未被 manifest 返回
2. manifest 返回了未声明 descriptor
3. 多个 registration 返回重复 descriptor
4. manifest 拉取失败
5. service envelope 或相关 descriptor schema 不合法
6. extension API version 不兼容

响应示例：

```json
{
  "status": "READY",
  "service": "agent-runtime",
  "registryType": "TOOL_CONNECTOR",
  "registrationConfigDigest": "sha256:...",
  "expectedDescriptorIds": ["enterprise.acme.crm"],
  "loadedDescriptorIds": ["enterprise.acme.crm"],
  "descriptorDefinitionDigests": {
    "enterprise.acme.crm": "sha256:..."
  },
  "missingDescriptorIds": [],
  "unexpectedDescriptorIds": [],
  "duplicateDescriptorIds": [],
  "manifestErrors": []
}
```

### 6.2 API Aggregate Validation

API 暴露聚合 validation endpoint：

```text
GET /internal/extension-registry/validation
```

API aggregate validation 用于比较 definition registry 与 runtime registry：

```text
API loaded tool connector descriptor ids
  == agent-runtime loaded tool connector descriptor ids

API loaded channel provider descriptor ids
  == channel-gateway loaded channel provider descriptor ids

API loaded descriptor definition digests
  == runtime loaded descriptor definition digests
```

同时比较三者的 `registrationConfigDigest`：

```text
API registrationConfigDigest
  == agent-runtime registrationConfigDigest
  == channel-gateway registrationConfigDigest
```

如果 registration config digest 或 descriptor definition digest 不一致，即使 descriptor ids 暂时一致，也视为 registry drift，aggregate validation failed。

API 通过调用 §6.1 定义的 runtime owner validation endpoint 获取 runtime 的 `registrationConfigDigest` 与 `descriptorDefinitionDigests`，不引入额外内部 endpoint。调用必须复用 `deployment-and-governance.md §5` 的 internal auth header。

调用语义：

1. API 并行调用所有 runtime owner validation endpoint，不串行
2. 单次调用 timeout 上限 5s（与 `deployment-and-governance.md §9` `manifest fetch` 同档）
3. 任一 runtime endpoint 不可达、超时或返回非 200，aggregate validation 立即记 `RUNTIME_REGISTRY_UNREACHABLE`，并在 validation 输出中标记 `NOT_READY`，不做部分降级
4. 不在 aggregate 层引入额外 retry；启动失败后由部署系统按进程退出策略重启或回滚
5. 所有失败必须在 `errors[*]` 中按 runtime 服务标记 `service` 与 `details.phase = AGGREGATE_VALIDATION`

API aggregate validation 的使用位置：

1. API 自身启动门禁
2. 部署后 smoke check
3. 运维排障 endpoint
4. 可选的管理后台只读状态页

API aggregate validation 不承担 runtime invocation，也不替代 runtime owner validation。

### 6.3 Registry Validation Error Structure

registry validation 失败结构必须同时满足：

1. 机器可判定
2. 人可排障
3. 不泄露 credential、auth header、`externalSecretRef` 或 manifest 原始敏感内容

启动失败日志、one-shot registry self-check、部署后 smoke check、registry validation endpoint 和运维状态页共用同一套 error item schema。部署后 smoke check 可以复用该 schema 展示 health 诊断失败，但 health 诊断失败不属于 registry validation 错误码集合，不回写 registry validation 结果。core preset 的 health 诊断只来自对应 core service `/health/ready`，不得要求 core preset 暴露 `/extension/health`。

正常服务进程只应在 registry validation 已通过后对外提供业务能力。启动阶段 registry validation 失败时进程退出，因此不会长期暴露 `NOT_READY` 响应；`NOT_READY` 响应只用于显式 validation endpoint / self-check / smoke check 的结构化输出。

响应示例：

```json
{
  "status": "NOT_READY",
  "service": "agent-runtime",
  "component": "EXTENSION_REGISTRY",
  "registryType": "TOOL_CONNECTOR",
  "registrationConfigDigest": "sha256:...",
  "summary": "Tool connector registry is not ready",
  "errors": [
    {
      "code": "MISSING_DESCRIPTOR",
      "severity": "ERROR",
      "registrationId": "acme-business-connectors",
      "descriptorType": "TOOL_CONNECTOR",
      "descriptorId": "enterprise.acme.crm",
      "message": "Descriptor declared in registration config was not loaded from manifest",
      "retryable": false,
      "details": {
        "phase": "REGISTRY_LOAD"
      }
    }
  ]
}
```

顶层字段：

1. `status`: `READY` / `NOT_READY`
2. `service`: `api` / `agent-runtime` / `channel-gateway`
3. `component`: 当前固定为 `EXTENSION_REGISTRY`
4. `registryType`: `TOOL_CONNECTOR` / `CHANNEL_PROVIDER` / `AGGREGATE`
5. `registrationConfigDigest`: 当前服务读取到的 registration config digest
6. `summary`: 面向日志和运维页面的短描述
7. `errors`: 结构化错误列表

错误项字段：

1. `code`: 稳定错误码
2. `severity`: `ERROR` / `WARNING`
3. `registrationId`: 相关部署注册项，可为空
4. `descriptorType`: `TOOL_CONNECTOR` / `CHANNEL_PROVIDER`，aggregate-only 错误可为空
5. `descriptorId`: 相关业务 descriptor id，可为空
6. `message`: 短错误说明，不包含敏感值
7. `retryable`: 当前错误是否可能通过重试恢复
8. `details`: 结构化排障信息，只允许非敏感字段；`phase` 必填，取值见下方错误归属

错误码集合：

```text
REGISTRATION_CONFIG_UNAVAILABLE
REGISTRATION_CONFIG_INVALID
MANIFEST_FETCH_FAILED
MANIFEST_SCHEMA_INVALID
EXTENSION_API_VERSION_INCOMPATIBLE
MISSING_DESCRIPTOR
UNEXPECTED_DESCRIPTOR
DUPLICATE_DESCRIPTOR
REGISTRATION_CONFIG_DIGEST_MISMATCH
RUNTIME_REGISTRY_UNREACHABLE
REGISTRY_DESCRIPTOR_MISMATCH
REGISTRY_DEFINITION_DIGEST_MISMATCH
```

错误归属：

1. `details.phase = REGISTRATION_CONFIG_LOAD`：registration config 不可读、字段缺失、operator yaml 使用保留 `core-` 前缀、env placeholder 无法解析
2. `details.phase = MANIFEST_FETCH`：manifest 拉取失败、当前 actor 负责范围内的 manifest schema 不合法、extension API version 不兼容；API 与 runtime owner 都可能发现该类错误，但 runtime owner 只针对自身消费范围报错
3. `details.phase = REGISTRY_LOAD`：当前 actor 负责范围内的 descriptor 白名单不匹配、missing descriptor、unexpected descriptor、duplicate descriptor；API 与 runtime owner 都可能发现该类错误，但 runtime owner 只针对自身 descriptor type 报错
4. `details.phase = AGGREGATE_VALIDATION`：API 与 runtime owner 之间的 `registrationConfigDigest`、descriptor id 或 definition digest 漂移，或 runtime validation endpoint 不可达

同一个错误码可以在不同 actor 上出现。排障入口必须先看 `service` 和 `details.phase`，再看 `code`；不能仅凭错误码判断错误属于 runtime owner 还是 API aggregate validation。

错误码追加字段（在 `details.phase` 基础上叠加，按错误码分类必填）：

1. `EXTENSION_API_VERSION_INCOMPATIBLE` 的 `details` 必须包含 `extension-protocol.md §2.6.5` 列出的字段：
   `coreVersion` / `supportedExtensionApiVersion` / `manifestExtensionApiVersion` / `coreMinVersion` / `coreMaxVersion`；任一字段
   因 manifest schema 损坏无法解析时，字段仍必须出现，值使用 `null`，由同一 error item 的 `message` 说明解析失败
2. `MANIFEST_FETCH_FAILED` 的 `details` 应包含 `httpStatus`（可为 `null`）和脱敏后的 `failureReason`
3. `MANIFEST_SCHEMA_INVALID` 的 `details` 应包含 `schemaPath` 与 `violation`，不允许包含 manifest 原始内容
4. `MISSING_DESCRIPTOR` / `UNEXPECTED_DESCRIPTOR` / `DUPLICATE_DESCRIPTOR` 已通过顶层 `descriptorId` 表达，
   `details` 仅追加 `phase`
5. 其他错误码 `details` 至少包含 `phase`，按需追加非敏感排障字段

HTTP status：

1. validation ready / passed: `200`
2. validation not ready / failed: `503`
3. endpoint 自身异常: `500`

### 6.4 Registration Config Change Deployment Order

registration config 变更不引入额外宽限窗口、告警抑制窗口或基于本地启动时间的判断。部署顺序固定为：

```text
enterprise extension service
  -> channel-gateway / agent-runtime
  -> API
```

处理规则：

1. liveness probe 不读 aggregate validation，只看进程存活
2. runtime owner validation 只校验本 runtime 的 registration config 与 manifest registry，不判断跨服务 digest 是否一致
3. API aggregate validation 是唯一比较 API / `agent-runtime` / `channel-gateway` `registrationConfigDigest` 的位置
4. API 必须最后更新；API 启动 / 部署后 smoke check 看到 `REGISTRATION_CONFIG_DIGEST_MISMATCH` 时，一律视为真实部署错误并启动失败或 smoke check 失败
5. 部署系统不得并行更新 API 与 runtime owner；如果无法保证顺序，应暂停 API aggregate 启动门禁或先补独立 rollout 编排，而不是在协议内引入宽限判断

配置规则：

1. 当前阶段不支持 registration hot reload；服务读取 extension registration config 后不做运行期重载
2. 修改 registration config 后必须滚动重启 API、`agent-runtime` 和 `channel-gateway`
3. `channel-gateway` / `agent-runtime` 更新完成并启动成功后，才能更新 API
4. API 更新完成后运行 aggregate validation 和部署后 smoke check
5. 如果未来引入 hot reload，必须重新定义 digest 传播、跨服务一致性检查和部署编排规则

## 7. 权限边界

1. Core 静态配置决定系统允许加载哪些 extension，属于运维 / 部署权限
2. 业务配置决定某个 Channel Profile / Tool Resource 是否使用已注册 extension，属于平台管理员 / 业务配置权限

前端管理员不能填写 extension `baseUrl`，只能选择 API definition endpoint 暴露的已注册 `providerType` / `connectorType`。

当前不引入 registry snapshot 持久化。
