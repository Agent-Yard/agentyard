# 文档入口

更新：2026-10-04。已对齐的目标方案记录如下；运行代码尚未完成相应重构。

## 推荐阅读顺序

1. [目标架构：Agent 持续上下文与人机协作](architecture/agent-collaboration.md)：产品交互、D01—D21 决策、对象边界、授权、消息、运行和旧模型替换关系。
2. [实施与验收计划](todo/agent-collaboration-refactor.md)：W01—W08 工作包、模块影响、契约建议、E01—E16 场景及完成标准。
3. [当前代码现状](architecture/code-framework.md)：当前实际链路、可复用能力和与目标的差异，不作为延续旧架构的约束。
4. [技术路线](technical_route.md)、[仓库结构](project_structure.md)、[项目待办](project_todos.md)：技术分工、目录导航和优先级。

## 运行与接入

- [根 README](../README.md)：项目定位、当前状态、本地启动与验证命令。
- [部署布局](../deploy/README.md)、[测试部署](../deploy/test/README-cn.md)：现有部署材料。
- [Agent Runtime](../apps/agent-runtime/README.md)：现有运行接口与环境变量。
- [Extension 接入](architecture/extension-integration.md)：外部扩展协议与接入。
- [Tool Connector 开发](architecture/tool-connector-development.md)：当前工具协议适配实现。
- [外部事件与恢复](architecture/external-interaction-integration.md)：当前回调链路及目标调整。
- [Extension 协议](../packages/extension-protocol/README.md)：协议 schema、样例与 fixtures。

## 维护规则

“目标方案”“当前实现”“待实施”必须明确区分。目标设计优先服从已对齐决策；现有运行行为以代码和测试为证据。某个目标完成后，同时更新现状文档和计划。

`docs/doing/` 只记录当前执行事项，完成自查后清理。`docs/develop_record/` 保持历史记录，不作为当前架构约束，本轮未修改。第三方资料、历史演示和旧测试报告不能作为目标架构已实现的证明。
