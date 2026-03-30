package com.lynxus.platform.runtime;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class RuntimeProjectionReconciler implements ApplicationRunner {
    private final RuntimeService runtimeService;

    public RuntimeProjectionReconciler(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        runtimeService.reconcileRunningWorkflows();
    }
}
