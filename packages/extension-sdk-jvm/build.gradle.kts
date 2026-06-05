plugins {
    `java-library`
    `maven-publish`
    id("org.openapi.generator") version "7.17.0"
}

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.language.jvm.tasks.ProcessResources
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

@Suppress("UNCHECKED_CAST")
fun jsonObject(value: Any?, label: String): MutableMap<String, Any?> {
    return value as? MutableMap<String, Any?> ?: error("$label must be a JSON object")
}

@Suppress("UNCHECKED_CAST")
fun downgradeBooleanAndNumericConstForOpenApiGenerator(value: Any?) {
    when (value) {
        is MutableMap<*, *> -> {
            val objectValue = value as MutableMap<String, Any?>
            val constValue = objectValue["const"]
            if (constValue is Boolean || constValue is Number) {
                objectValue.remove("const")
                objectValue.putIfAbsent("default", constValue)
            }
            objectValue.values.forEach(::downgradeBooleanAndNumericConstForOpenApiGenerator)
        }
        is MutableList<*> -> value.forEach(::downgradeBooleanAndNumericConstForOpenApiGenerator)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

val extensionProtocolOpenApi = rootProject.layout.projectDirectory.file(
    "packages/extension-protocol/openapi/extension-boundary.openapi.json"
)
val extensionProtocolJsonSchemaDir = rootProject.layout.projectDirectory.dir(
    "packages/extension-protocol/json-schema"
)
val extensionProtocolJsonSchemaResourceRoot = "com/agentyard/extension/sdk/protocol/json-schema"
val extensionProtocolJavaGeneratorOpenApi = layout.buildDirectory.file(
    "generated/extension-protocol/openapi/extension-boundary.openapi.generator.json"
)
val generatedExtensionProtocolJavaModelsDir = layout.buildDirectory.dir("generated/extension-protocol/java")
val generatedExtensionProtocolJavaSmokeSourcesDir = layout.buildDirectory.dir(
    "generated/extension-protocol/java-smoke/src/main/java"
)
val generatedExtensionProtocolJavaClassesDir = layout.buildDirectory.dir("classes/java/generated-extension-protocol")

sourceSets {
    main {
        java.srcDir(generatedExtensionProtocolJavaModelsDir.map { it.dir("src/main/java") })
    }
}

val generatedExtensionProtocolJavaCompileClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val prepareExtensionProtocolJavaGeneratorOpenApi = tasks.register("prepareExtensionProtocolJavaGeneratorOpenApi") {
    group = "code generation"
    description = "Writes a generator-only OpenAPI copy with OpenAPI Generator Java compatibility fixes."

    inputs.file(extensionProtocolOpenApi)
    outputs.file(extensionProtocolJavaGeneratorOpenApi)

    doLast {
        val sourceFile = extensionProtocolOpenApi.asFile
        val targetFile = extensionProtocolJavaGeneratorOpenApi.get().asFile
        val openApi = jsonObject(JsonSlurper().parse(sourceFile), "OpenAPI document")
        val components = jsonObject(openApi["components"], "OpenAPI components")
        val schemas = jsonObject(components["schemas"], "OpenAPI components.schemas")
        val normalizedEventAccepted = jsonObject(
            schemas["NormalizedEventAccepted"],
            "OpenAPI components.schemas.NormalizedEventAccepted"
        )
        val properties = jsonObject(
            normalizedEventAccepted["properties"],
            "OpenAPI components.schemas.NormalizedEventAccepted.properties"
        )
        val accepted = jsonObject(
            properties["accepted"],
            "OpenAPI components.schemas.NormalizedEventAccepted.properties.accepted"
        )

        check(accepted["const"] == true) {
            "Source OpenAPI NormalizedEventAccepted.accepted must keep const: true"
        }

        downgradeBooleanAndNumericConstForOpenApiGenerator(openApi)

        Files.createDirectories(targetFile.toPath().parent)
        Files.writeString(targetFile.toPath(), JsonOutput.prettyPrint(JsonOutput.toJson(openApi)) + "\n")
    }
}

val generateExtensionProtocolJavaModels = tasks.register<GenerateTask>("generateExtensionProtocolJavaModels") {
    group = "code generation"
    description = "Generates extension boundary protocol Java models from packages/extension-protocol OpenAPI."

    dependsOn(prepareExtensionProtocolJavaGeneratorOpenApi)

    generatorName.set("java")
    inputSpec.set(extensionProtocolJavaGeneratorOpenApi.map { it.asFile.toURI().toString() })
    outputDir.set(generatedExtensionProtocolJavaModelsDir.get().asFile.absolutePath)
    modelPackage.set("com.agentyard.extension.sdk.generated.protocol.model")
    apiPackage.set("com.agentyard.extension.sdk.generated.protocol.api")
    invokerPackage.set("com.agentyard.extension.sdk.generated.protocol")

    globalProperties.set(
        mapOf(
            "models" to "",
            "modelDocs" to "false",
            "modelTests" to "false",
            "apis" to "false",
            "apiDocs" to "false",
            "apiTests" to "false",
        )
    )
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "disallowAdditionalPropertiesIfNotPresent" to "true",
            "additionalModelTypeAnnotations" to
                "@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.OBJECT)",
            "hideGenerationTimestamp" to "true",
            "library" to "resttemplate",
            "openApiNullable" to "false",
            "serializationLibrary" to "jackson",
            "useJakartaEe" to "true",
        )
    )

    doFirst {
        delete(generatedExtensionProtocolJavaModelsDir.get().asFile)
    }
}

val writeGeneratedExtensionProtocolJavaCompileSmoke = tasks.register("writeGeneratedExtensionProtocolJavaCompileSmoke") {
    group = "verification"
    description = "Writes a Java source file that imports selected generated extension protocol DTOs."

    val smokeSource = generatedExtensionProtocolJavaSmokeSourcesDir.map {
        it.file("com/agentyard/extension/sdk/generated/protocol/smoke/GeneratedProtocolModelCompileSmoke.java")
    }
    val smokeSourceText = """
        package com.agentyard.extension.sdk.generated.protocol.smoke;

        import com.agentyard.extension.sdk.generated.protocol.model.ChannelProviderDescriptor;
        import com.agentyard.extension.sdk.generated.protocol.model.ChannelOutboundFrame;
        import com.agentyard.extension.sdk.generated.protocol.model.ChannelOutboundFrameAck;
        import com.agentyard.extension.sdk.generated.protocol.model.ExtensionError;
        import com.agentyard.extension.sdk.generated.protocol.model.ServiceManifestEnvelope;
        import com.agentyard.extension.sdk.generated.protocol.model.ToolConnectorDescriptor;

        final class GeneratedProtocolModelCompileSmoke {
            private static final Class<?>[] REFERENCED_MODEL_TYPES = {
                ServiceManifestEnvelope.class,
                ExtensionError.class,
                ChannelProviderDescriptor.class,
                ChannelOutboundFrame.class,
                ChannelOutboundFrameAck.class,
                ToolConnectorDescriptor.class,
            };

            private GeneratedProtocolModelCompileSmoke() {
            }

            static Class<?>[] referencedModelTypes() {
                return REFERENCED_MODEL_TYPES.clone();
            }
        }
    """.trimIndent()

    inputs.property("smokeSourceText", smokeSourceText)
    outputs.file(smokeSource)

    doLast {
        val sourceFile = smokeSource.get().asFile.toPath()
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, smokeSourceText)
    }
}

val compileGeneratedExtensionProtocolJavaModels = tasks.register<JavaCompile>(
    "compileGeneratedExtensionProtocolJavaModels"
) {
    group = "verification"
    description = "Compiles generated extension protocol Java DTOs and a source-level import smoke."

    dependsOn(generateExtensionProtocolJavaModels)
    dependsOn(writeGeneratedExtensionProtocolJavaCompileSmoke)

    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    )
    classpath = generatedExtensionProtocolJavaCompileClasspath
    source(generatedExtensionProtocolJavaModelsDir.map { it.dir("src/main/java") })
    source(generatedExtensionProtocolJavaSmokeSourcesDir)
    destinationDirectory.set(generatedExtensionProtocolJavaClassesDir)
    options.encoding = "UTF-8"
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:2.20")
    api("jakarta.annotation:jakarta.annotation-api:3.0.0")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.1")
    implementation("com.networknt:json-schema-validator:2.0.1") {
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }

    generatedExtensionProtocolJavaCompileClasspath("com.fasterxml.jackson.core:jackson-annotations:2.20")
    generatedExtensionProtocolJavaCompileClasspath("jakarta.annotation:jakarta.annotation-api:3.0.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateExtensionProtocolJavaModels)
}

tasks.named<ProcessResources>("processResources") {
    from(extensionProtocolJsonSchemaDir) {
        include("*.schema.json")
        into(extensionProtocolJsonSchemaResourceRoot)
    }
}

tasks.named("sourcesJar") {
    dependsOn(generateExtensionProtocolJavaModels)
}

val extensionSdkJvmGroupId = providers.gradleProperty("agentyardExtensionSdkJvmGroupId")
    .orElse(project.group.toString())
val extensionSdkJvmArtifactId = providers.gradleProperty("agentyardExtensionSdkJvmArtifactId")
    .orElse(project.name)
val extensionSdkJvmVersion = providers.gradleProperty("agentyardExtensionSdkJvmVersion")
    .orElse(project.version.toString())
val agentyardMavenRepositoryUrl = providers.gradleProperty("agentyardMavenRepositoryUrl")
    .orElse(providers.environmentVariable("AGENTYARD_MAVEN_REPOSITORY_URL"))
val agentyardMavenRepositoryName = providers.gradleProperty("agentyardMavenRepositoryName")
    .orElse(providers.environmentVariable("AGENTYARD_MAVEN_REPOSITORY_NAME"))
    .orElse("agentyard")
val agentyardMavenRepositoryUsername = providers.gradleProperty("agentyardMavenRepositoryUsername")
    .orElse(providers.environmentVariable("AGENTYARD_MAVEN_REPOSITORY_USERNAME"))
val agentyardMavenRepositoryPassword = providers.gradleProperty("agentyardMavenRepositoryPassword")
    .orElse(providers.environmentVariable("AGENTYARD_MAVEN_REPOSITORY_PASSWORD"))
val agentyardMavenRepositoryAllowInsecureProtocol = providers
    .gradleProperty("agentyardMavenRepositoryAllowInsecureProtocol")
    .orElse(providers.environmentVariable("AGENTYARD_MAVEN_REPOSITORY_ALLOW_INSECURE_PROTOCOL"))
    .map(String::toBoolean)
    .orElse(false)

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId = extensionSdkJvmGroupId.get()
            artifactId = extensionSdkJvmArtifactId.get()
            version = extensionSdkJvmVersion.get()

            pom {
                name.set("AgentYard Extension SDK for JVM")
                description.set("JVM SDK for AgentYard Extension Plane implementations.")
            }
        }
    }

    repositories {
        agentyardMavenRepositoryUrl.orNull?.let { repositoryUrl ->
            maven {
                name = agentyardMavenRepositoryName.get()
                url = uri(repositoryUrl)
                isAllowInsecureProtocol = agentyardMavenRepositoryAllowInsecureProtocol.get()
                val repositoryUsername = agentyardMavenRepositoryUsername.orNull
                val repositoryPassword = agentyardMavenRepositoryPassword.orNull
                if (repositoryUsername != null || repositoryPassword != null) {
                    credentials(PasswordCredentials::class) {
                        username = repositoryUsername.orEmpty()
                        password = repositoryPassword.orEmpty()
                    }
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(compileGeneratedExtensionProtocolJavaModels)
    systemProperty("agentyard.repo.root", rootProject.projectDir.absolutePath)
    systemProperty("agentyard.extension.generated.java.dir", generatedExtensionProtocolJavaModelsDir.get().asFile.absolutePath)
    systemProperty(
        "agentyard.extension.generated.java.classes.dir",
        generatedExtensionProtocolJavaClassesDir.get().asFile.absolutePath
    )
    systemProperty(
        "agentyard.extension.generated.java.openapi.path",
        extensionProtocolJavaGeneratorOpenApi.get().asFile.absolutePath
    )
}
