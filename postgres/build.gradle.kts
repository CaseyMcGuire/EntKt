plugins {
    alias(libs.plugins.kotlin.jvm)
    // For @Serializable JSON fixtures in the test source set.
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    `maven-publish`
}

group = "io.entkt"
version = "0.1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":runtime"))
    api(project(":migrations"))
    implementation(project(":codegen"))
    implementation(libs.postgresql)
    // `Json` appears in the public PostgresDriver constructor, so a consumer
    // compiling `PostgresDriver(ds, json = Json { ... })` needs it on their
    // compile classpath — hence `api`, not `implementation`.
    api(libs.kotlinx.serialization.json)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
