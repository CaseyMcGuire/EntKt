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
