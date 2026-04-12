plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "entkt"
include("schema")
include("runtime")
include("codegen")
include("gradle-plugin")
include("example")
include("example-demo")
include("postgres")
include("migrations")