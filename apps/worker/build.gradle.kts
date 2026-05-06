plugins {
    id("java")
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

val temporalVersion: String by rootProject.extra

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":packages:contracts-jvm"))
    implementation(project(":packages:persistence-jvm"))
    implementation(project(":packages:shared-redis-jvm"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework:spring-web")
    implementation("io.temporal:temporal-sdk:$temporalVersion")
    implementation("org.postgresql:postgresql:42.7.7")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")
}
