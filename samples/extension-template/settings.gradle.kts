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
        // Add your private Maven repository here if com.lynxus:extension-sdk-jvm is not public.
        // maven("https://maven.example.com/releases")
    }
}

rootProject.name = "lynxus-extension-template"
