plugins {
    alias(libs.plugins.kotlin.jvm)
    // For @Serializable JSON fixtures in the test source set.
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":runtime"))
    api(project(":migrations"))
    implementation(project(":codegen"))
    implementation(libs.postgresql)
    // kotlinx-serialization-json reaches consumers transitively via
    // :runtime's `api` (KotlinxJsonCodec's constructor takes a `Json`);
    // PostgresDriver itself no longer references it.

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
