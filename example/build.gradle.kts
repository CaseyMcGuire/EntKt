plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":schema"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}