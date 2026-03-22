package com.lynxus.worker.workflow;

import io.temporal.activity.ActivityInterface;
import java.util.List;

@ActivityInterface
public interface KnowledgeQaActivities {
    List<String> retrieveKnowledge(String scenarioId, String question);

    String generateAnswer(String question, List<String> contexts);

    boolean shouldEscalate(String question, String answer);
}
