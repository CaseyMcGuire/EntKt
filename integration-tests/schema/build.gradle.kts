plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

base {
    archivesName = "integration-tests-schema"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":schema"))
    // For the @Serializable JSON fixture (ArticleMeta).
    implementation(libs.kotlinx.serialization.core)
}

// A separate fixture client exercises required relationships without imposing
// database-only capabilities on the shared schemas' no-op-driver tests.
val requiredOne by sourceSets.creating
configurations[requiredOne.implementationConfigurationName].extendsFrom(configurations.implementation.get())

val requiredOneSchemas by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val requiredOneSchemasJar = tasks.register<Jar>("requiredOneSchemasJar") {
    archiveClassifier.set("required-one")
    from(requiredOne.output)
}
artifacts.add(requiredOneSchemas.name, requiredOneSchemasJar)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
