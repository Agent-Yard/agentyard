# 外部事件与等待恢复

核对日期：2026-10-05。接待和专业 Agent 都由真实 Session 保存持续上下文；一次模型执行是 Activation，长流程是独立 ToolRun。等待不占用模型运行，也不接管 Session。

## 真实运行链路

```text
供应商事件 → 可信渠道或连接器适配器验证来源
  → 控制面核对 ToolRun、waitingId、发布版本中的来源绑定与账号状态
  → 同一事务保存来源审计 Message 和待投递 signal
  → AgentDispatcher 投递 ToolRunWorkflow.resume
  → Worker 核对等待点和来源，处理后 ACK
  → 流程继续或完成 → 结果事件进入原 Session 的持久消息队列
```

人工回应通过真实参与者的消息表达。执行 Agent 可以调用 `tool_run_resume`，但控制面会核对人类决定者、原始消息发送/接收关系和任务决定权限；Agent 不能把普通工具调用标记为外部回调。

## 外部等待的配置

发布的 Playbook 在 `EXTERNAL_INTERACTION` 节点设置 `config.externalSource`：

```json
{
  "externalSource": {
    "subjectType": "TOOL_CONNECTOR",
    "descriptorId": "verification-provider",
    "accountId": "integration-account-id"
  }
}
```

`subjectType` 只允许 `TOOL_CONNECTOR` 或 `CHANNEL_PROVIDER`。`descriptorId` 是对应连接器或渠道类型；`accountId` 是已配置的集成账号。节点配置随 Playbook 版本固定，不能由模型在等待期间改变。发布校验要求这三个字段齐全。

等待建立后，ToolRun 的 `waiting` 包含 `waitingId`、`nodeKey`、`source: EXTERNAL_SYSTEM` 和说明。适配器必须保存或读取这一准确关联，再将供应商事件映射回来。供应商如何签名、验签、关联其业务单据由对应适配器实现，不把第三方回调直接暴露为平台内网接口。

## 可信内部入口

`POST /api/internal/tool-runs/{runId}/external-callback` 使用平台内部 Bearer 认证。请求：

```json
{
  "waitingId": "run-id:verify:2",
  "eventId": "provider-event-id",
  "source": {
    "subjectType": "TOOL_CONNECTOR",
    "descriptorId": "verification-provider",
    "accountId": "integration-account-id"
  },
  "payload": {"verified": true, "receiptId": "provider-receipt-id"}
}
```

只有已完成供应商来源校验的可信适配器可调用。平台不把内网令牌交给外部用户、Agent 提示词或脚本节点。入口核对真实集成账号的类型、描述符归属、启用和撤销状态，并与等待节点的来源配置逐项比较。

返回持久 `ToolRunResume`，包含 `signalId`、`waitingId`、记录者 `participantId`、来源审计 `sourceMessageId`、固定的 `EXTERNAL_SYSTEM` 来源及 payload。记录者是持有 ToolRun 的真实 Session 参与者；外部事件不伪造成人类决定。审计 Message 保留供应商事件 ID、账号/描述符关联和回调内容，不含凭证。HTTP 成功表示结果已经可靠接收，不代表流程或业务 Task 已完成。

实现见 [回调控制器](../../apps/api/src/main/java/com/agentyard/platform/agent/InternalToolRunCallbackController.java)、[来源校验与审计](../../apps/api/src/main/java/com/agentyard/platform/agent/ExternalToolRunCallbackService.java)、[共享信号持久化](../../apps/api/src/main/java/com/agentyard/platform/agent/ToolRunService.java)和[回调契约](../../packages/contracts-jvm/src/main/java/com/agentyard/contracts/agent/ToolRunCallbacks.java)。

## 重复、暂停与终态

- `runId + waitingId` 只接收一个确定回应。相同来源、eventId、payload 返回原 signal；冲突内容或另一事件被拒绝。
- 已处理回调可重复确认，仍返回原回执；未知的旧等待点不能恢复新等待点。
- 已取消 ToolRun、已关闭 Session，或已取消/完成的关联 Task 拒绝回调，迟到结果不会复活工作。
- Session 或 Task 暂停时，真实外部结果可以先持久保存；Worker 保留信号，恢复后再继续节点。取消无需先恢复。
- Signal 与来源审计先提交再调度。调度重复投递由 Worker 的信号 ID 去重，消费后通过 `/api/internal/tool-runs/{runId}/signals/{signalId}/ack` 确认。
- 这保证平台接收与流程恢复可重放。供应商执行外部副作用仍须遵守其幂等契约；不能据此声称所有外部操作只执行一次。

[PostgreSQL 集成测试](../../apps/api/src/test/java/com/agentyard/platform/agent/ExternalToolRunCallbackTest.java)覆盖来源绑定、真实账号状态、重复与冲突、取消、暂停和审计。Worker 流程测试另外覆盖等待点、信号来源、暂停恢复和重复信号消费。渠道身份与公开结果继续复用 Channel Provider 协议，见[Extension 开发指南](extension-integration.md)。
