plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":schema"))
    implementation(project(":codegen"))
    implementation(project(":postgres"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

gradlePlugin {
    plugins {
        create("entkt") {
            id = "entkt"
            implementationClass = "entkt.gradle.EntktPlugin"
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
