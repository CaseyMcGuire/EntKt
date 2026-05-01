plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":migrations"))
    api(project(":postgres"))
    implementation(project(":codegen"))
    implementation(libs.postgresql)
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")

    implementation(libs.testcontainers.postgresql)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
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
