package com.lynxus.platform.system;

import com.lynxus.platform.shared.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    @GetMapping("/health")
    public ApiResponse<?> health() {
        return ApiResponse.ok(Map.of(
            "status", "UP",
            "service", "lynxus-api",
            "timestamp", Instant.now()
        ));
    }

    @GetMapping("/dependencies")
    public ApiResponse<?> dependencies() {
        return ApiResponse.ok(Map.of(
            "postgres", "configured",
            "minio", "configured",
            "temporal", "configured"
        ));
    }
}
