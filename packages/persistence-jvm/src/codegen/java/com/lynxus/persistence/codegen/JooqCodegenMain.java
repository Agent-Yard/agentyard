package com.lynxus.persistence.codegen;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Strategy;
import org.jooq.meta.jaxb.Target;

public final class JooqCodegenMain {
    private JooqCodegenMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected arguments: <repo-root> <output-dir>");
        }

        Path repoRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDir = Path.of(args[1]).toAbsolutePath().normalize();
        List<MigrationOwner> migrationOwners = List.of(
            new MigrationOwner(repoRoot.resolve("apps/api/src/main/resources/db/migration"), "flyway_schema_history"),
            new MigrationOwner(repoRoot.resolve("apps/channel-gateway/src/main/resources/db/migration"), "channel_gateway_schema_history")
        );

        File outputFile = outputDir.toFile();
        if (!outputFile.exists() && !outputFile.mkdirs()) {
            throw new IllegalStateException("failed to create output directory: " + outputDir);
        }

        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            for (MigrationOwner owner : migrationOwners) {
                Flyway.configure()
                    .dataSource(postgres.getPostgresDatabase())
                    .locations("filesystem:" + owner.migrationDir())
                    .table(owner.historyTable())
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate();
            }

            String jdbcUrl = postgres.getJdbcUrl("postgres", "postgres");
            Configuration configuration = new Configuration()
                .withJdbc(new Jdbc()
                    .withDriver("org.postgresql.Driver")
                    .withUrl(jdbcUrl)
                    .withUser("postgres")
                    .withPassword("postgres"))
                .withGenerator(new Generator()
                    .withName("org.jooq.codegen.DefaultGenerator")
                    .withStrategy(new Strategy().withName("org.jooq.codegen.DefaultGeneratorStrategy"))
                    .withDatabase(new Database()
                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                        .withInputSchema("public")
                        .withIncludes(".*")
                        .withExcludes("flyway_schema_history|channel_gateway_schema_history"))
                    .withGenerate(new Generate()
                        .withDeprecated(false)
                        .withRecords(true)
                        .withPojos(false)
                        .withDaos(false)
                        .withFluentSetters(false))
                    .withTarget(new Target()
                        .withPackageName("com.lynxus.persistence.jooq")
                        .withDirectory(outputDir.toString())));

            GenerationTool.generate(configuration);
        }
    }

    private record MigrationOwner(Path migrationDir, String historyTable) {
    }
}
