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

kotlin {
    compilerOptions {
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE
    }
}

dependencies {
    // Runtime needs Predicate / OrderField from the schema module's
    // entkt.query package. Eventually we may split entkt.query out into
    // its own module so :runtime doesn't pull in the schema DSL — but
    // for now this dependency is fine.
    api(project(":schema"))

    // KSerializer appears in JsonColumnMetadata (public runtime metadata).
    api(libs.kotlinx.serialization.core)

    // The default JsonColumnCodec (KotlinxJsonCodec) takes a configured
    // kotlinx `Json` instance; drivers construct it as their default codec.
    api(libs.kotlinx.serialization.json)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
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
