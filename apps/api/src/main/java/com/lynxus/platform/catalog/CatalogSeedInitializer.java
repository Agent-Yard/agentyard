package com.lynxus.platform.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class CatalogSeedInitializer implements ApplicationRunner {
    private final CatalogService catalogService;
    private final boolean enabled;

    public CatalogSeedInitializer(
        CatalogService catalogService,
        @Value("${lynxus.catalog.seed.enabled:true}") boolean enabled
    ) {
        this.catalogService = catalogService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        catalogService.initializeDemoDataIfEmpty();
    }
}
