# Extension Plane 文档入口

> 本目录记录 Extension Plane 的当前目标设计。README 只作为目录入口和使用说明，
> 不承载具体架构决策。
> 临时执行议题只在 `docs/doing/` 中短期存在；完成后清空。架构事实源以本目录主题文档为准。

## 目录

1. [overview.md](./overview.md)
   - 技术总览、目标边界、总体结构和 registry 分域
2. [repository-and-packaging.md](./repository-and-packaging.md)
   - 开源 core、reference extensions、企业私有 repo、制品与发布边界
3. [extension-protocol.md](./extension-protocol.md)
   - `packages/extension-protocol`、`packages/extension-sdk-jvm`、`packages/extension-sdk-python`、manifest、OpenAPI / JSON Schema
4. [static-registration.md](./static-registration.md)
   - 静态注册配置、registry、启动发现、权限边界和一致性校验
5. [channel-provider.md](./channel-provider.md)
   - Channel Provider extension、inbound / outbound、provider job 和 channel 数据模型
6. [tool-connector.md](./tool-connector.md)
   - Tool Connector extension、manifest、runtime invocation 和 remote sidecar
7. [web-configuration.md](./web-configuration.md)
   - Web schema-driven 配置、Channel / Tool 表单和 UI 边界
8. [credentials-and-persistence.md](./credentials-and-persistence.md)
   - extension 私有持久化、extension-managed secret、`externalSecretRef`
9. [deployment-and-governance.md](./deployment-and-governance.md)
   - 部署组合、私有依赖、安全治理、版本策略和观测要求
10. [implementation-roadmap.md](./implementation-roadmap.md)
    - 当前实施任务、影响范围、已定约束和验收标准
11. [glossary.md](./glossary.md)
    - 跨主题高频术语索引；语义争议以对应主题文档为准

## 使用方式

1. 先读 `overview.md` 建立整体技术模型
2. 再按要修改的架构主题进入对应文档
3. **编码前必须核对 `implementation-roadmap.md §6 Impact Checklist`，覆盖 Contracts / DB / channel-gateway / API / agent-runtime / Web / tests / docs 七个面**
4. 新决策确定后，直接更新对应主题文档
5. `docs/doing/` 只记录正在执行的临时事项，完成并自查后清空
