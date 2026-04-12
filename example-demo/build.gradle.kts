plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

// Classpath for the codegen JavaExec task: schema definitions + codegen engine.
val codegenRunner: Configuration by configurations.creating

dependencies {
    implementation(project(":schema"))
    implementation(project(":runtime"))

    codegenRunner(project(":example-spring:schema"))
    codegenRunner(project(":codegen"))
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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass.set("example.demo.DemoKt")
}