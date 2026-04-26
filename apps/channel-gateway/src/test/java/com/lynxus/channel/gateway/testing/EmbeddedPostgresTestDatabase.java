package com.lynxus.channel.gateway.testing;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Path;
import java.util.List;
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

    public DSLContext dsl() {
        return dsl;
    }

    private void clean() {
        flyway(true).clean();
    }

    private void migrate() {
        flyway(false).migrate();
    }

    private Flyway flyway(boolean allowClean) {
        String migrationPath = resolveMigrationPath();
        return Flyway.configure()
            .cleanDisabled(!allowClean)
            .dataSource(dataSource)
            .locations("filesystem:" + migrationPath)
            .table("channel_gateway_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load();
    }

    private String resolveMigrationPath() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> candidates = List.of(
            workingDirectory.resolve("apps/channel-gateway/src/main/resources/db/migration"),
            workingDirectory.resolve("src/main/resources/db/migration"),
            workingDirectory.resolve("../channel-gateway/src/main/resources/db/migration"),
            workingDirectory.resolve("../apps/channel-gateway/src/main/resources/db/migration"),
            workingDirectory.resolve("../../apps/channel-gateway/src/main/resources/db/migration")
        );
        return candidates.stream()
            .map(Path::normalize)
            .filter(java.nio.file.Files::isDirectory)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("unable to locate channel-gateway migration directory from " + workingDirectory))
            .toString();
    }

    @Override
    public void close() throws Exception {
        postgres.close();
    }
}
