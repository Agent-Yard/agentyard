package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KnowledgeQaActivitiesImplTest {
    private final KnowledgeQaActivitiesImpl activities = new KnowledgeQaActivitiesImpl();

    @Test
    void shouldDetectEscalationKeywords() {
        assertTrue(activities.shouldEscalate("这是投诉，需要人工处理", "answer"));
        assertFalse(activities.shouldEscalate("怎么重置密码", "answer"));
    }
}
