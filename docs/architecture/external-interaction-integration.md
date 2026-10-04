# 外部事件与等待恢复

核对日期：2026-10-04。本文区分当前 API 和[目标架构](agent-collaboration.md)中的事件机制。目标尚未实现；当前调用方仍应遵循现有契约。

## 1. 当前可用链路

会话流程的人工与外部等待由 SessionWorkflow / PlaybookWorkflow 承载，控制面入口包括：

| 当前 API | 用途 |
| --- | --- |
| `POST /api/session-runtime/sessions/{sessionId}/human-resume` | 人工回应流程等待 |
| `POST /api/session-runtime/sessions/{sessionId}/external-callback` | 外部结果进入指定会话流程 |
| `POST /api/session-runtime/sessions/{sessionId}/human-reply` | 现有 handoff 模式下人工回复 |
| `POST /api/session-runtime/sessions/{sessionId}/handoff/end` | 结束现有人工接管 |

入口实现见 [SessionRuntimeController](../../apps/api/src/main/java/com/agentyard/platform/session/SessionRuntimeController.java)，字段见 [Control Plane OpenAPI](../../packages/contracts/openapi/control-plane.yaml)。

external-callback 支持 Idempotency-Key；未提供时由服务端基于 Session、Playbook Run 和 payload 派生稳定 key。接收后写运行事件并交给对应工作流恢复。此接口需要现有认证/授权，并非通用的任意第三方 Webhook 接口。

独立 TaskExecution 还有自己的等待与恢复命令，使用执行 ID 和 waitingId，回应先持久保存再派发。经过认证的参与者可以提交回应；不能因此宣称已支持任意外部供应商的签名回调。完整范围见[当前代码](code-framework.md)。

## 2. 目标机制

外部结果、人工回应、Agent 消息、定时和资源变化都进入持久事件/消息链。它们可以唤醒一个真实 Session；流程回调另外关联 ToolRun 和具体等待点。接待实例与专业实例采用同一机制。

```text
来源适配与认证
  → 解析接收实例、工作与等待关联
  → 校验来源、授权、事件重复与当前状态
  → 持久化消息/回执与待投递记录
  → 唤醒 Session 或恢复 ToolRun
  → 结果事件交给 Agent 判断下一步
```

业务 Task、Agent Session、一次 Activation 和工具等待各有生命周期。收到回调不能绕过参与者确认直接宣告 Task 完成；迟到事件不能重新启动已经取消的工作。

## 3. 关联与处理要求

- 来源使用可信的渠道身份或连接器认证，不能仅相信 payload 中自填的参与者 ID。
- 输入保留来源事件 ID、发送者、接收者、关联 Task、ToolRun/等待点和回复关联。所需字段依事件类型定义，不要求每条消息都有所有字段。
- 先持久化再调度，支持重启后接续；重复投递返回已有回执。
- 区分“已接收”“已投递”“已处理/失败”；外部 HTTP 成功响应不等于工作已完成。
- 检查等待点及当前权限；旧 waitingId 不能命中新等待；撤销、取消和终态有可解释结果。
- 人工补充默认是协作消息。只有明确的暂停、取消、接手指令才改变执行安排。
- 回调内容与派生通知按可见范围读取、展示和继续传递。

继续复用 Channel Provider / Tool Connector 的接入能力，协议见[Extension 开发指南](extension-integration.md)。新的参与者和调用关联字段需要同步协议与 SDK；不能通过新增一种“后台无 Session 恢复”形成第三条主链。

具体工作在 [W04、W06 及 E05/E09/E10/E11](../todo/agent-collaboration-refactor.md) 验收。目标契约确定后应同步替换本页当前 API 表。
