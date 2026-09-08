import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

val entktVersion = providers.gradleProperty("entktVersion").get()
val centralPublishingEnabled = providers.gradleProperty("centralPublishing")
    .map(String::toBooleanStrict)
    .getOrElse(false)

if (centralPublishingEnabled) {
    require(!entktVersion.endsWith("-SNAPSHOT")) {
        "Central publishing uses manual releases; choose a non-SNAPSHOT entktVersion."
    }
}

val moduleDescriptions = mapOf(
    "schema" to "Kotlin schema DSL and typed query predicates for EntKt",
    "runtime" to "Entity queries, mutations, privacy, validation, and transactions for EntKt",
    "codegen" to "Schema-specific Kotlin entity and repository generation for EntKt",
    "gradle-plugin" to "Gradle integration for EntKt code generation and Flyway migrations",
    "postgres" to "PostgreSQL database driver and schema inspection for EntKt",
    "jackson" to "Jackson JSON column codec for EntKt",
    "migrations" to "Database schema comparison and migration planning for EntKt",
    "flyway" to "Flyway migration generation for EntKt",
    "ent-viewer-core" to "Read-only entity inspection and HTML rendering for EntKt",
    "ent-viewer-spring" to "Spring Boot integration for the EntKt entity viewer",
)

subprojects {
    // Only modules explicitly applying the publishing plugin participate.
    // Example applications and integration-test projects are never published.
    plugins.withId("com.vanniktech.maven.publish") {
        val moduleName = project.name
        group = "io.entkt"
        version = entktVersion

        extensions.configure<MavenPublishBaseExtension> {
            // Normal builds and local publication need no credentials. Central uploads
            // require an explicit opt-in and remain staged until released in the Portal.
            if (centralPublishingEnabled) {
                publishToMavenCentral(automaticRelease = false)
                signAllPublications()
            }

            pom {
                name.set("EntKt $moduleName")
                description.set(moduleDescriptions.getValue(moduleName))
                url.set("https://github.com/CaseyMcGuire/EntKt")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("CaseyMcGuire")
                        name.set("Casey McGuire")
                        url.set("https://github.com/CaseyMcGuire")
                    }
                }
                scm {
                    url.set("https://github.com/CaseyMcGuire/EntKt")
                    connection.set("scm:git:https://github.com/CaseyMcGuire/EntKt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/CaseyMcGuire/EntKt.git")
                }
            }
        }

        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "localStaging"
                    url = rootProject.layout.buildDirectory.dir("publishing/local").get().asFile.toURI()
                }
            }
        }
    }
}
