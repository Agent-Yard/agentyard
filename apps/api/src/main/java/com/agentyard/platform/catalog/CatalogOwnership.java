package com.agentyard.platform.catalog;

import java.util.Set;

/** Ownership records resource governance; execution grants are checked separately at tool calls. */
public final class CatalogOwnership {
    private static final Set<String> TYPES = Set.of("PERSON", "ORGANIZATION", "PLATFORM");
    private CatalogOwnership() {}

    public static void validate(String ownerType, String ownerId) {
        if (ownerType == null || !TYPES.contains(ownerType)) {
            throw new IllegalArgumentException("ownerType must be PERSON, ORGANIZATION or PLATFORM");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
    }
}
