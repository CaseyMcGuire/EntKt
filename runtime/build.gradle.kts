plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
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
    // The DatabaseDriver contract test asserts abstractness of members that
    // decorators must forward (kotlin.reflect full API).
    testImplementation(kotlin("reflect"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
