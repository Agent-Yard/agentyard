package com.agentyard.platform.event;

import com.agentyard.platform.auth.RequireGovernanceAccess;
import com.agentyard.platform.shared.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.agentyard.platform.event.PlatformEventDtos.*;

@RestController
@RequestMapping("/api/events")
@RequireGovernanceAccess
public class PlatformEventController {
    private final PlatformEventService platformEventService;

    private final com.agentyard.platform.catalog.CatalogAccess access;

    public PlatformEventController(PlatformEventService platformEventService, com.agentyard.platform.catalog.CatalogAccess access) {
        this.access = access;
        this.platformEventService = platformEventService;
    }

    @GetMapping
    public ApiResponse<?> listEvents(
        @RequestParam(required = false) PlatformAggregateType aggregateType,
        @RequestParam(required = false) String aggregateId,
        @RequestParam(required = false) Instant since,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String cursor
    ) {
        if (!access.isPlatformAdmin()) {
            if (aggregateType == null || aggregateId == null) throw new org.springframework.security.access.AccessDeniedException("global configuration event history requires platform administration");
            access.object(aggregateType.name(), aggregateId, false);
        }
        return ApiResponse.ok(platformEventService.listEvents(aggregateType, aggregateId, since, limit, cursor));
    }
}
