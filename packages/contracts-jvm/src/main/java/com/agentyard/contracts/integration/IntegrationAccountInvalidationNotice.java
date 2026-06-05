package com.agentyard.contracts.integration;

import java.time.Instant;

public record IntegrationAccountInvalidationNotice(
    String accountId,
    String subjectType,
    String subjectId,
    String reason,
    Instant occurredAt,
    String sourceInstanceId
) {
}
