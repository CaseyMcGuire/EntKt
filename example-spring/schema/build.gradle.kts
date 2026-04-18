plugins {
    alias(libs.plugins.kotlin.jvm)
}

base {
    archivesName = "example-spring-schema"
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
