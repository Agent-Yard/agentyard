package com.lynxus.platform.adapters;

import java.util.List;
import org.springframework.stereotype.Component;

public final class ResourceAdapters {
    private ResourceAdapters() {
    }

    public interface KnowledgeBaseProvider {
        List<String> retrieve(String scenarioId, String question);
    }

    public interface SkillExecutor {
        String execute(String skillName, String question, List<String> contexts);
    }

    public interface McpClient {
        String invoke(String capabilityName, String payload);
    }

    @Component
    public static class MockKnowledgeBaseProvider implements KnowledgeBaseProvider {
        @Override
        public List<String> retrieve(String scenarioId, String question) {
            return List.of(
                "Lynxus MVP 支持按业务场景配置知识问答流程。",
                "当问题超出知识库置信范围时，流程会进入人工介入节点。",
                "资源支持知识库、Skill 和 MCP 三类最小模型。"
            );
        }
    }

    @Component
    public static class MockSkillExecutor implements SkillExecutor {
        @Override
        public String execute(String skillName, String question, List<String> contexts) {
            return "基于知识库结果的回答：%s。若仍未解决，请转人工。".formatted(contexts.getFirst());
        }
    }

    @Component
    public static class MockMcpClient implements McpClient {
        @Override
        public String invoke(String capabilityName, String payload) {
            return "mock-mcp:" + capabilityName + ":" + payload;
        }
    }
}
