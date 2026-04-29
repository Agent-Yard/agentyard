# Repository 与 Packaging

## 1. 开源仓库边界

开源仓库保留 core、extension protocol 和 reference implementation：

```text
lynxus/
  apps/
    api/
    worker/
    web/
    agent-runtime/
    knowledge-service/
    channel-gateway/
  packages/
    contracts/
    contracts-jvm/
    persistence-jvm/
    python-common/
    shared-redis-jvm/
    extension-protocol/
    extension-sdk-jvm/
    extension-sdk-python/
  docs/
    architecture/
    briefing/
    develop_record/
    doing/
    todo/
```

Reference implementation 保留在现有 app 内，通过 manifest / registry 接入 extension plane。

## 2. 企业私有仓库边界

企业定制代码独立放私有仓库：

```text
lynxus-enterprise-acme/
  channel-providers/
    acme-internal-im/
    acme-ticket-channel/
  tool-connectors/
    acme-crm/
    acme-erp/
    acme-approval/
  deployment/
    dev/
    test/
    prod/
  docs/
    acme-runbook.md
```

Java / JVM 私有 extension repo 依赖 core 发布的 `packages/extension-sdk-jvm`，Python 私有 extension repo 依赖 `packages/extension-sdk-python`；两类 SDK 都不得依赖 core app 的内部实现。`packages/extension-protocol` 是协议源，不作为企业 extension 直接依赖的主要开发入口。

## 3. Reference Extensions 归属

Reference extensions 指开源 core 自带的标准示例 / 通用实现，不包含企业私有逻辑。

作用：

1. 让开源项目开箱可用
2. 给企业私有 extension 提供实现范例
3. 验证 extension protocol 是否合理
4. 作为 contract tests 的样板实现

目标 reference channel providers：

```text
feishu                 # 已存在，待重构为 gateway-native adapter
```

目标 reference tool connectors：

```text
simple-http            # 已存在，待 manifest 化
business-code-secret-http   # 已存在，待 manifest 化
mcp                    # 已存在，待 manifest 化
```

当前策略：

1. reference implementation 逻辑上按 extension 接入 registry
2. 物理目录仍保留在现有 app 内
3. 不在当前设计中规划顶层 `extensions/` 目录迁移

保留现有物理目录的原因：

1. 避免同时牵动 Gradle / uv / Docker build / test path
2. 优先稳定协议和运行边界
3. reference implementation 先 in-process，更容易跑通
4. 避免把目录重组和 extension protocol 设计耦合

## 4. 制品边界

开源 core Docker image：

```text
lynxus/api
lynxus/worker
lynxus/web
lynxus/agent-runtime
lynxus/knowledge-service
lynxus/channel-gateway
```

开源 core package（非 Docker，作为协议事实源 / 语言 SDK 发布）：

```text
packages/extension-protocol      # 协议源（OpenAPI / JSON Schema / examples / contract fixtures）
packages/extension-sdk-jvm       # Maven 坐标 com.lynxus:lynxus-extension-sdk-jvm
packages/extension-sdk-python    # PyPI 分发 lynxus-extension-sdk-python
```

发布渠道：当前阶段 SDK 先发布到内部 Maven repository / 内部 Python registry / GitHub Packages，
Maven Central / PyPI 是否发布后续单独决策（与 `extension-protocol.md §2.7` 对齐）。

企业 repo 发布（Docker image）：

```text
acme/lynxus-channel-provider
acme/lynxus-crm-connector
acme/lynxus-approval-connector
```

## 5. Reference Extension 发布策略

当前阶段不为 reference extension 单独发布 Docker image。Reference implementation 继续随对应 core app 发布，并通过 manifest / registry 接入 extension plane：`feishu` 随 `lynxus/channel-gateway` 发布，`simple-http` / `business-code-secret-http` / `mcp` 随 `lynxus/agent-runtime` 发布；不存在 `lynxus/feishu-extension` 或 `lynxus/simple-http-connector` 这类独立 reference image。

当前阶段交付 enterprise sample repo 模板，用于展示 private-style channel provider、tool connector、extension-managed secret、私有依赖、Dockerfile、compose overlay 和 contract tests。sample compose / Helm overlay 必须演示 `LYNXUS_INTERNAL_TOKEN_FILE` secret 投影：tool connector 用于校验 Core 调用，remote channel provider 同时用于校验 Core 调用并携带同一 token 调用 `channel-gateway` internal normalized event endpoint。
