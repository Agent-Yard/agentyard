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
    compileOnly("io.temporal:temporal-sdk:$temporalVersion")
}
