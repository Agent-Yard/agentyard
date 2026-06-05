rootProject.name = "agentyard"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include("apps:api")
include("apps:channel-gateway")
include("apps:worker")
include("packages:contracts-jvm")
include("packages:extension-sdk-jvm")
include("packages:persistence-jvm")
include("packages:shared-redis-jvm")
