plugins {
    java
}

val temporalVersion: String by rootProject.extra

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.1"))
    implementation("tools.jackson.core:jackson-databind")
    compileOnly("io.temporal:temporal-sdk:$temporalVersion")
}
