plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly("io.temporal:temporal-sdk:1.32.1")
}
