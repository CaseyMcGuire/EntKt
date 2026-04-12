plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.spring") version "2.0.21"
}

repositories {
    mavenCentral()
}

// Classpath for running the codegen entry point.
val codegenRunner: Configuration by configurations.creating

dependencies {
    implementation(project(":schema"))
    implementation(project(":runtime"))
    implementation(project(":postgres"))
    implementation(project(":migrations"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    codegenRunner(project(":example"))
}

val generatedDir = layout.buildDirectory.dir("generated/entkt")

val generateEntkt = tasks.register<JavaExec>("generateEntkt") {
    group = "entkt"
    description = "Runs entkt codegen against the example schemas"
    classpath = codegenRunner
    mainClass.set("example.MainKt")
    args(generatedDir.get().asFile.absolutePath)
    outputs.dir(generatedDir)
    inputs.files(project(":example").fileTree("src/main/kotlin"))
}

sourceSets {
    main {
        kotlin.srcDir(generateEntkt.map { generatedDir })
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateEntkt)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
