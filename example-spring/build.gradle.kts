plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.spring") version "2.0.21"
}

repositories {
    mavenCentral()
}

// Classpath for running codegen and migration planning.
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

    codegenRunner(project(":example-spring:schema"))
    codegenRunner(project(":codegen"))
    codegenRunner(project(":postgres"))
}

val generatedDir = layout.buildDirectory.dir("generated/entkt")

val generateEntkt = tasks.register<JavaExec>("generateEntkt") {
    group = "entkt"
    description = "Runs entkt codegen against the example schemas"
    classpath = codegenRunner
    mainClass.set("entkt.codegen.GenerateMainKt")
    args("example.ent", generatedDir.get().asFile.absolutePath)
    outputs.dir(generatedDir)
    inputs.files(project(":example-spring:schema").fileTree("src/main/kotlin"))
}

sourceSets {
    main {
        kotlin.srcDir(generateEntkt.map { generatedDir })
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateEntkt)
}

tasks.register<JavaExec>("planMigration") {
    group = "entkt"
    description = "Generates a versioned migration SQL file by diffing schemas against the snapshot"
    classpath = codegenRunner
    mainClass.set("entkt.postgres.PlanMigrationMainKt")
    args(project.projectDir.absolutePath)
    args(project.findProperty("description")?.toString() ?: "migration")
}

springBoot {
    mainClass.set("example.spring.ApplicationKt")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
