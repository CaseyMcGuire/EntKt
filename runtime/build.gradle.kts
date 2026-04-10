plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // Runtime needs Predicate / OrderField from the schema module's
    // entkt.query package. Eventually we may split entkt.query out into
    // its own module so :runtime doesn't pull in the schema DSL — but
    // for now this dependency is fine.
    api(project(":schema"))

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