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
            pom {
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // JsonColumnCodec / JsonColumnMetadata live in the runtime driver package.
    api(project(":runtime"))
    // ObjectMapper appears in JacksonJsonCodec's public constructor.
    api(libs.jackson.databind)
    // KType.javaType (kotlin.reflect.jvm) for building Jackson JavaTypes.
    implementation(kotlin("reflect"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    // Kotlin data classes need the Jackson Kotlin module; the codec takes the
    // caller's configured ObjectMapper, so this is a test-only dependency.
    testImplementation(libs.jackson.module.kotlin)
    // Container-backed round-trip through the real Postgres driver.
    testImplementation(project(":postgres"))
    testImplementation(libs.postgresql)
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
