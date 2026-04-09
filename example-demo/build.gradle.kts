plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

// This configuration pulls in everything the :example module needs at
// runtime — schema, codegen, kotlinpoet, example schemas, etc. We use
// it as the classpath for the codegen JavaExec task so the generator
// can load example.User / example.Post / example.Tag reflectively.
val codegenRunner: Configuration by configurations.creating

dependencies {
    // Demo.kt imports the generated code which in turn imports entkt.query.*
    implementation(project(":schema"))

    // Used only by the generateEntkt JavaExec task below.
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
    // Re-run if the example schemas change.
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

application {
    mainClass.set("example.demo.DemoKt")
}