plugins {
    id("base")
}

allprojects {
    group = "com.lynxus"
    version = "0.1.0"
}

subprojects {
    repositories {
        mavenCentral()
    }
}

tasks.register("generateJooq") {
    dependsOn(":packages:persistence-jvm:generateJooq")
}

tasks.register("verifyJooqGenerated") {
    dependsOn(":packages:persistence-jvm:verifyJooqGenerated")
}
