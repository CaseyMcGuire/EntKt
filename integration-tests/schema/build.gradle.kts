plugins {
    alias(libs.plugins.kotlin.jvm)
}

base {
    archivesName = "integration-tests-schema"
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