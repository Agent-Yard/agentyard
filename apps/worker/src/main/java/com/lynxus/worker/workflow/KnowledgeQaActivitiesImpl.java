package com.lynxus.worker.workflow;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeQaActivitiesImpl implements KnowledgeQaActivities {
    @Override
    public List<String> retrieveKnowledge(String scenarioId, String question) {
        return List.of(
            "Lynxus 使用业务场景承载业务入口与交付目标。",
            "复杂问题会进入人工接管节点。",
            "MVP 阶段资源类型包括 Skill、MCP 和知识库。"
        );
    }

    @Override
    public String generateAnswer(String question, List<String> contexts) {
        return "自动回答：" + contexts.getFirst();
    }

    @Override
    public boolean shouldEscalate(String question, String answer) {
        return question.contains("人工") || question.contains("投诉");
    }
}
