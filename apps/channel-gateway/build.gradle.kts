import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

plugins {
    id("java")
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

sourceSets {
    main {
        java.srcDir("src/generated/jooq")
    }
    create("codegen") {
        java.srcDir("src/codegen/java")
        runtimeClasspath += output + compileClasspath
    }
}

val codegenImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    implementation(project(":packages:contracts-jvm"))
    implementation(project(":packages:extension-sdk-jvm"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-docker-compose")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.7")

    codegenImplementation("org.flywaydb:flyway-core")
    codegenImplementation("org.flywaydb:flyway-database-postgresql")
    codegenImplementation("io.zonky.test:embedded-postgres:2.2.2")
    codegenImplementation("org.jooq:jooq-codegen")
    codegenImplementation("org.jooq:jooq-meta")
    codegenImplementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

val generatedJooqDir = layout.projectDirectory.dir("src/generated/jooq")
val verificationJooqDir = layout.buildDirectory.dir("generated/jooq-verify")

tasks.register<Delete>("cleanGeneratedJooq") {
    delete(generatedJooqDir)
}

tasks.register<JavaExec>("generateJooq") {
    group = "code generation"
    description = "Generate channel-gateway jOOQ classes from its Flyway-managed PostgreSQL schema."
    dependsOn("codegenClasses")
    classpath = sourceSets["codegen"].runtimeClasspath
    mainClass.set("com.lynxus.channel.gateway.codegen.ChannelGatewayJooqCodegenMain")
    args(rootProject.projectDir.absolutePath, generatedJooqDir.asFile.absolutePath)
    inputs.dir(rootProject.file("apps/channel-gateway/src/main/resources/db/migration"))
    outputs.dir(generatedJooqDir)
}

tasks.register<JavaExec>("generateJooqVerification") {
    group = "verification"
    description = "Generate channel-gateway jOOQ classes into a temporary directory for verification."
    dependsOn("codegenClasses")
    classpath = sourceSets["codegen"].runtimeClasspath
    mainClass.set("com.lynxus.channel.gateway.codegen.ChannelGatewayJooqCodegenMain")
    args(rootProject.projectDir.absolutePath, verificationJooqDir.get().asFile.absolutePath)
    inputs.dir(rootProject.file("apps/channel-gateway/src/main/resources/db/migration"))
    outputs.dir(verificationJooqDir)
}

tasks.register("verifyJooqGenerated") {
    group = "verification"
    description = "Verify committed channel-gateway jOOQ generated sources are synchronized with the current Flyway schema."
    dependsOn("generateJooqVerification")
    doLast {
        val expectedRoot = verificationJooqDir.get().asFile.toPath()
        val actualRoot = generatedJooqDir.asFile.toPath()
        val expectedFiles = collectFiles(expectedRoot)
        val actualFiles = collectFiles(actualRoot)

        if (expectedFiles.keys != actualFiles.keys) {
            val missing = expectedFiles.keys - actualFiles.keys
            val extra = actualFiles.keys - expectedFiles.keys
            error(
                buildString {
                    append("channel-gateway generated jOOQ sources are out of sync")
                    if (missing.isNotEmpty()) {
                        append("; missing files: ")
                        append(missing.sorted().joinToString(", "))
                    }
                    if (extra.isNotEmpty()) {
                        append("; extra files: ")
                        append(extra.sorted().joinToString(", "))
                    }
                }
            )
        }

        expectedFiles.forEach { (relativePath, expectedHash) ->
            val actualHash = actualFiles[relativePath]
            if (expectedHash != actualHash) {
                error("channel-gateway generated jOOQ source differs from committed version: $relativePath")
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyJooqGenerated")
}

fun collectFiles(root: Path): Map<String, String> {
    if (Files.notExists(root)) {
        return emptyMap()
    }
    return Files.walk(root).use { paths ->
        val files = linkedMapOf<String, String>()
        paths
            .filter(Files::isRegularFile)
            .sorted()
            .forEach { file ->
                files[root.relativize(file).toString()] = sha256(file)
            }
        files
    }
}

fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(Files.readAllBytes(file))
    return digest.digest().joinToString("") { "%02x".format(it) }
}
