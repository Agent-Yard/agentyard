package com.agentyard.platform.catalog;

import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import org.junit.jupiter.api.Test;

class JooqPersistenceUpgradeMigrationTest {
    @Test void replacesOldHierarchyAndCreatesIndependentConfigurationTables() throws Exception {
        try (var database = new EmbeddedPostgresTestDatabase()) {
            database.reset();
            for (var obsolete : new String[]{"catalog_domain", "catalog_scenario", "catalog_assistant", "catalog_agent", "catalog_assistant_releases", "catalog_ref_release_resource"}) {
                assertNull(database.dsl().fetchOne("select to_regclass(?)", obsolete).get(0));
            }
            for (var current : new String[]{"catalog_role_template", "catalog_role_template_version", "catalog_entry", "catalog_playbook_version", "agent_session"}) {
                assertNotNull(database.dsl().fetchOne("select to_regclass(?)", current).get(0));
            }
            assertEquals(0, ((Number) database.dsl().fetchOne("select count(*) from information_schema.columns where table_name in ('catalog_resource','knowledge_base') and column_name='domain_id'").get(0)).intValue());
        }
    }
}
