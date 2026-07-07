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
    api(project(":ent-viewer-core"))
    // Provided by the consuming Spring Boot application; this module must not
    // impose a Spring version on non-Spring consumers of the catalog.
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.web)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
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
