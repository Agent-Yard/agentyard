package com.lynxus.platform.testing;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class EmbeddedPostgresTestDatabase implements AutoCloseable {
    private final EmbeddedPostgres postgres;
    private final DriverManagerDataSource dataSource;
    private final DSLContext dsl;

    public EmbeddedPostgresTestDatabase() throws Exception {
        this.postgres = EmbeddedPostgres.builder().start();
        this.dataSource = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    public void reset() {
        clean();
        migrate();
    }

    public void clean() {
        flyway(true, null)
            .clean();
    }

    public void migrate() {
        flyway(false, null)
            .migrate();
    }

    public void migrateTo(String targetVersion) {
        flyway(false, targetVersion)
            .migrate();
    }

    public DriverManagerDataSource dataSource() {
        return dataSource;
    }

    public DSLContext dsl() {
        return dsl;
    }

    private Flyway flyway(boolean allowClean, String targetVersion) {
        var configuration = Flyway.configure()
            .cleanDisabled(!allowClean)
            .dataSource(dataSource)
            .locations("classpath:db/migration");
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        return configuration.load();
    }

    @Override
    public void close() throws Exception {
        postgres.close();
    }
}
