package com.lynxus.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JooqPersistenceUpgradeMigrationTest {
    private static EmbeddedPostgresTestDatabase database;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void cleanDatabase() {
        database.clean();
    }

    @Test
    void shouldClearDerivedReferenceTablesWhenUpgradingToTypedCatalogSchema() {
        database.migrateTo("9");
        database.dsl().execute(
            "insert into catalog_ref_resource_binding (source_type, source_id, resource_id, binding_kind) values ('ASSISTANT', 'ast-legacy', 'res-legacy', 'ASSISTANT_DEFAULT_MODEL')"
        );
        database.dsl().execute(
            "insert into catalog_ref_knowledge_binding (source_type, source_id, knowledge_base_id, binding_kind) values ('ASSISTANT', 'ast-legacy', 'kb-legacy', 'ASSISTANT_DEFAULT_KNOWLEDGE_BASE')"
        );
        database.dsl().execute(
            "insert into catalog_ref_release_resource (release_id, assistant_id, resource_id, resource_version_id, resource_version) values ('rel-legacy', 'ast-legacy', 'res-legacy', 'rv-legacy', '1.0.0')"
        );
        database.dsl().execute(
            "insert into catalog_ref_release_knowledge (release_id, assistant_id, knowledge_base_id, knowledge_release_id) values ('rel-legacy', 'ast-legacy', 'kb-legacy', 'kr-legacy')"
        );

        database.migrate();

        assertEquals(0, countRows("catalog_ref_resource_binding"));
        assertEquals(0, countRows("catalog_ref_knowledge_binding"));
        assertEquals(0, countRows("catalog_ref_release_resource"));
        assertEquals(0, countRows("catalog_ref_release_knowledge"));
    }

    private int countRows(String tableName) {
        return ((Number) database.dsl().fetchOne("select count(*) from " + tableName).get(0)).intValue();
    }
}
