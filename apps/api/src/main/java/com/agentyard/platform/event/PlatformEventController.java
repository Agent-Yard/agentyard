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

    public PlatformEventController(PlatformEventService platformEventService) {
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
        return ApiResponse.ok(platformEventService.listEvents(aggregateType, aggregateId, since, limit, cursor));
    }
}
