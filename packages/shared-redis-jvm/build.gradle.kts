plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.1"))
    implementation(project(":packages:contracts-jvm"))

    implementation("org.springframework:spring-context")
    implementation("org.springframework.data:spring-data-redis")
    implementation("io.micrometer:micrometer-core")
    implementation("tools.jackson.core:jackson-databind")
}
