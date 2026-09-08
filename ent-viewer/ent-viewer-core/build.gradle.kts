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
    // EntitySchema/ColumnMetadata (schema pages, redaction flags) and
    // ViewerContext (the per-request context contract) are runtime types.
    api(project(":runtime"))
    // Server-side HTML + CSS rendering; internal to the renderer.
    implementation(libs.kotlinx.html)
    implementation(libs.kotlin.css)

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
