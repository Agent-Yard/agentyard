package com.lynxus.platform.adapters;

import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

public final class ResourceAdapters {
    private ResourceAdapters() {
    }

    public interface KnowledgeBaseProvider {
        List<String> retrieve(String scenarioId, String question);
    }

    public interface ToolExecutor {
        String execute(String toolName, String question, List<String> contexts);
    }

    public interface ToolProviderClient {
        ToolOutcomeSummary invoke(String toolName, String operation, String payload);
    }

    @Component
    @Primary
    public static class LocalScenarioKnowledgeBaseProvider implements KnowledgeBaseProvider {
        @Override
        public List<String> retrieve(String scenarioId, String question) {
            if (question.contains("密码")) {
                return List.of(
                    "密码重置可通过登录页的“忘记密码”完成，系统会发送重置链接到注册邮箱。",
                    "若账号被锁定，需要先完成邮箱验证再重置密码。",
                    "连续 5 次失败会触发账号保护，建议人工协助排查异常登录。"
                );
            }

            if (question.contains("退款")) {
                return List.of(
                    "退款申请需要校验订单状态、支付时间和售后政策。",
                    "涉及投诉或争议订单时，建议创建人工协同工单。",
                    "退款处理完成后要同步工单和客户沟通记录。"
                );
            }

            return List.of(
                "Lynxus MVP 支持按业务场景配置知识问答流程。",
                "当问题超出知识库置信范围时，流程会进入人工介入节点。",
                "资源支持知识库、Tool、LLM 和 Skill 等可复用能力。"
            );
        }
    }

    @Component
    public static class MockKnowledgeBaseProvider implements KnowledgeBaseProvider {
        @Override
        public List<String> retrieve(String scenarioId, String question) {
            return List.of(
                "Lynxus MVP 支持按业务场景配置知识问答流程。",
                "当问题超出知识库置信范围时，流程会进入人工介入节点。",
                "资源支持知识库、Tool、LLM 和 Skill 等可复用能力。"
            );
        }
    }

    @Component
    public static class MockToolExecutor implements ToolExecutor {
        @Override
        public String execute(String toolName, String question, List<String> contexts) {
            return "基于知识库结果的回答：%s。若仍未解决，请转人工。".formatted(contexts.getFirst());
        }
    }

    @Component
    @Primary
    public static class LocalToolProviderClient implements ToolProviderClient {
        @Override
        public ToolOutcomeSummary invoke(String toolName, String operation, String payload) {
            boolean humanHandoff = payload.contains("投诉") || payload.contains("人工");
            String ticketId = "TICKET-" + Math.abs(payload.hashCode() % 100000);
            return new ToolOutcomeSummary(
                toolName,
                toolName,
                operation,
                "MCP",
                humanHandoff ? "ACCEPTED" : "RECORDED",
                ticketId,
                humanHandoff ? "HUMAN_HANDOFF" : "AUTO_CLOSE",
                humanHandoff
                    ? "本地工具 stub 已受理协同请求，建议人工坐席接管。"
                    : "本地工具 stub 已记录本次处理结果，无需人工介入。"
            );
        }
    }

    @Component
    public static class MockToolProviderClient implements ToolProviderClient {
        @Override
        public ToolOutcomeSummary invoke(String toolName, String operation, String payload) {
            return new ToolOutcomeSummary(
                toolName,
                toolName,
                operation,
                "MCP",
                "RECORDED",
                "mock-ticket",
                "AUTO_CLOSE",
                "mock-tool:" + toolName + ":" + payload
            );
        }
    }
}
