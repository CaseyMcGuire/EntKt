plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

base {
    archivesName = "integration-tests-schema"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":schema"))
    // For the @Serializable JSON fixture (ArticleMeta).
    implementation(libs.kotlinx.serialization.core)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}