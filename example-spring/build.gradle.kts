plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.spring") version "2.0.21"
}

repositories {
    mavenCentral()
}

// Spring Boot's BOM pins Testcontainers core to 1.x; this project uses 2.x.
// Override Spring's managed version so the whole classpath stays on 2.x.
ext["testcontainers.version"] = libs.versions.testcontainers.get()

// Classpath for running codegen and migration planning.
val codegenRunner: Configuration by configurations.creating

dependencies {
    implementation(project(":schema"))
    implementation(project(":runtime"))
    implementation(project(":postgres"))
    implementation(project(":migrations"))
    implementation(project(":example-spring:schema"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    codegenRunner(project(":example-spring:schema"))
    codegenRunner(project(":codegen"))
    codegenRunner(project(":postgres"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.register<JavaExec>("generateMigrationFile") {
    group = "entkt"
    description = "Generates a versioned migration SQL file by diffing schemas against the snapshot"
    classpath = codegenRunner
    mainClass.set("entkt.postgres.PlanMigrationMainKt")
    args(project.projectDir.resolve("db/migrations").absolutePath)
    args(project.findProperty("description")?.toString() ?: "migration")
}

tasks.register<JavaExec>("validateEntSchemas") {
    group = "entkt"
    description = "Validate entkt schema graph"
    classpath = codegenRunner
    mainClass.set("entkt.postgres.InspectMainKt")
    args("validate")
}

tasks.register<JavaExec>("explainEntSchemas") {
    group = "entkt"
    description = "Print the resolved relational shape of all entkt schemas"
    classpath = codegenRunner
    mainClass.set("entkt.postgres.InspectMainKt")
    val format = project.providers.gradleProperty("format").getOrElse("text")
    val cliArgs = mutableListOf("explain", "--format=$format")
    val filter = project.providers.gradleProperty("filter").orNull
    if (filter != null) cliArgs.add("--filter=$filter")
    args(cliArgs)
}

springBoot {
    mainClass.set("example.spring.ApplicationKt")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
