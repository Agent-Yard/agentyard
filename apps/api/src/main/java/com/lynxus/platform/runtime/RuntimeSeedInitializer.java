package com.lynxus.platform.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class RuntimeSeedInitializer implements ApplicationRunner {
    private final RuntimeService runtimeService;
    private final boolean enabled;
    private final boolean executeOpeningMessages;

    public RuntimeSeedInitializer(
        RuntimeService runtimeService,
        @Value("${lynxus.runtime.seed.enabled:true}") boolean enabled,
        @Value("${lynxus.runtime.seed.execute-opening-messages:false}") boolean executeOpeningMessages
    ) {
        this.runtimeService = runtimeService;
        this.enabled = enabled;
        this.executeOpeningMessages = executeOpeningMessages;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        runtimeService.seedDemoData(executeOpeningMessages);
    }
}
