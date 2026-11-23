plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "entkt"
include("schema")
include("runtime")
include("codegen")
include("gradle-plugin")
include("example-spring:schema")
include("postgres")
include("jackson")
// The ent-explorer family lives under one folder; Gradle paths and published
// coordinates keep the full module names (io.entkt:ent-explorer-core, ...).
include("ent-explorer-core")
project(":ent-explorer-core").projectDir = file("ent-explorer/ent-explorer-core")
include("ent-explorer-spring")
project(":ent-explorer-spring").projectDir = file("ent-explorer/ent-explorer-spring")
include("migrations")
include("flyway")
include("example-spring")
include("integration-tests:schema")
include("integration-tests")