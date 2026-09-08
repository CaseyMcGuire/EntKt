plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":schema"))
    implementation(project(":runtime"))
    implementation(libs.kotlinpoet)
    implementation(kotlin("reflect"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    // Compile-fail harness for generated-code contracts (e.g. typed JSON's
    // "non-@Serializable fails at consumer compile time" promise).
    testImplementation(libs.kotlin.compile.testing)
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
