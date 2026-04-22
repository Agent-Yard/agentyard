plugins {
    id("base")
}

import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

extra["temporalVersion"] = "1.34.0"

allprojects {
    group = "com.lynxus"
    version = "0.1.0"
}

subprojects {
    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("java") {
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Netty 4.1 on Java 24+ requires an explicit opt-in to avoid Unsafe warnings.
            jvmArgs("--sun-misc-unsafe-memory-access=allow")
            testLogging {
                events(TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                exceptionFormat = TestExceptionFormat.FULL
            }
            addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {}

                override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                    if (suite.parent == null && result.skippedTestCount > 0) {
                        logger.warn(
                            "WARNING: ${path} skipped ${result.skippedTestCount} test(s). " +
                                "If this is unexpected, inspect the test XML/report for skipped cases " +
                                "(for example, Docker-dependent tests may be skipped when Docker is unavailable)."
                        )
                    }
                }

                override fun beforeTest(testDescriptor: TestDescriptor) {}

                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
            })
        }
    }
}

tasks.register("generateJooq") {
    dependsOn(":packages:persistence-jvm:generateJooq")
}

tasks.register("verifyJooqGenerated") {
    dependsOn(":packages:persistence-jvm:verifyJooqGenerated")
}
