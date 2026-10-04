plugins {
    id("java")
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

val temporalVersion: String by rootProject.extra

val taskE2e by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[taskE2e.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[taskE2e.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":packages:contracts-jvm"))
    implementation(project(":packages:extension-sdk-jvm"))
    implementation(project(":packages:persistence-jvm"))
    implementation(project(":packages:shared-redis-jvm"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-docker-compose")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    implementation("io.temporal:temporal-sdk:$temporalVersion")
    implementation("org.postgresql:postgresql:42.7.7")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    add(taskE2e.implementationConfigurationName, project(":apps:worker"))
    add(taskE2e.implementationConfigurationName, "io.temporal:temporal-testing:$temporalVersion")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.register<Test>("taskE2eTest") {
    description = "Runs Task collaboration over real HTTP, PostgreSQL, Temporal and the Python runtime with a deterministic model."
    group = "verification"
    testClassesDirs = taskE2e.output.classesDirs
    classpath = taskE2e.runtimeClasspath
    useJUnitPlatform { includeTags("task-e2e") }
    systemProperty("agentyard.repository", rootProject.projectDir.absolutePath)
    inputs.files(rootProject.fileTree("apps/agent-runtime/agentyard_agent_runtime") { include("**/*.py") })
    inputs.files(rootProject.fileTree("packages/python-common") { include("**/*.py") })
    inputs.files(rootProject.files("uv.lock", "pyproject.toml", "apps/agent-runtime/pyproject.toml"))
}
