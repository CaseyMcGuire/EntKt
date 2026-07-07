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
// The ent-viewer family lives under one folder; Gradle paths and published
// coordinates keep the full module names (io.entkt:ent-viewer-core, ...).
include("ent-viewer-core")
project(":ent-viewer-core").projectDir = file("ent-viewer/ent-viewer-core")
include("ent-viewer-spring")
project(":ent-viewer-spring").projectDir = file("ent-viewer/ent-viewer-spring")
include("migrations")
include("flyway")
include("example-spring")
include("integration-tests:schema")
include("integration-tests")