package com.agentyard.platform.event;

import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import com.agentyard.platform.auth.CurrentUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.agentyard.platform.event.PlatformEventDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformEventServiceTest {
    @Test
    void shouldGenerateFullUuidPlatformEventIds() {
        PlatformEventService service = new PlatformEventService(
            new PlatformEventRepository.InMemoryPlatformEventRepository(),
            testCurrentUserResolver()
        );

        service.recordControlEvent("ROLE_TEMPLATE_CREATED", PlatformAggregateType.ROLE_TEMPLATE, "template-1", Map.of("name", "运营域"));
        service.recordControlEvent("ROLE_TEMPLATE_UPDATED", PlatformAggregateType.ROLE_TEMPLATE, "template-1", Map.of("name", "运营域"));

        List<PlatformEventDto> items = service.listEvents(PlatformAggregateType.ROLE_TEMPLATE, "template-1", null, 10, null).items();

        assertEquals(2, items.size());
        assertEquals(2, items.stream().map(PlatformEventDto::id).distinct().count());
        assertTrue(items.stream().allMatch(item -> item.id().matches(
            "platform-event-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )));
    }

    private static CurrentUserResolver testCurrentUserResolver() {
        return () -> new PlatformUser(
            "user-test",
            "tester",
            "测试用户",
            "test@example.com",
            AuthSource.EXTERNAL,
            "https://issuer.example.com",
            "subject-user-test",
            UserStatus.ACTIVE,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"),
            List.of(Role.PLATFORM_ADMIN)
        );
    }
}
