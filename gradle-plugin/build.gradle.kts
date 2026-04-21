plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.entkt"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // The plugin itself only needs the Gradle API (provided by java-gradle-plugin).
    // Codegen/schema/postgres run in a separate JVM via JavaExec, so they must NOT
    // be implementation deps — that would put entkt's Kotlin on Gradle's classloader
    // and cause kotlin-reflect version conflicts.
    testImplementation(project(":schema"))
    testImplementation(project(":codegen"))
    testImplementation(project(":postgres"))
    testImplementation(project(":runtime"))
    testImplementation(project(":migrations"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

gradlePlugin {
    plugins {
        create("entkt") {
            id = "entkt"
            implementationClass = "entkt.gradle.EntktPlugin"
        }
    }
}

// Embed the plugin version so the plugin can auto-add codegen dependencies
// at the matching version without hardcoding.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("entkt-plugin.properties") {
        expand(props)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
