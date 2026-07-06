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
include("migrations")
include("flyway")
include("example-spring")
include("integration-tests:schema")
include("integration-tests")