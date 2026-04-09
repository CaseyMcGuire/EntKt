package entkt.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntktPluginTest {

    @Test
    fun `generateEntkt task produces entity files`() {
        val projectDir = File.createTempFile("entkt-test", "").apply {
            delete()
            mkdirs()
        }

        try {
            // Find the entkt schema jar on the test classpath
            val schemaJar = findClasspathEntry("entkt/schema/EntSchema.class")
                ?: throw IllegalStateException("Cannot find entkt-schema on test classpath")

            projectDir.resolve("settings.gradle.kts").writeText(
                """
                include("schema")
                include("app")
                """.trimIndent()
            )

            // Schema module — compiles user schemas against entkt-schema
            val schemaModuleDir = projectDir.resolve("schema")
            schemaModuleDir.resolve("build.gradle.kts").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    plugins {
                        kotlin("jvm") version "2.0.21"
                    }
                    repositories { mavenCentral() }
                    dependencies {
                        implementation(files("${schemaJar.absolutePath.replace("\\", "\\\\")}"))
                    }
                    """.trimIndent()
                )
            }

            val schemaSrc = schemaModuleDir.resolve("src/main/kotlin/com/example/schema")
            schemaSrc.mkdirs()
            schemaSrc.resolve("Schemas.kt").writeText(
                """
                package com.example.schema

                import entkt.schema.*

                object Pet : EntSchema() {
                    override fun fields() = fields {
                        string("name")
                        int("age").optional()
                    }
                }
                """.trimIndent()
            )

            // App module — applies the entkt plugin
            val appDir = projectDir.resolve("app")
            appDir.resolve("build.gradle.kts").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    plugins {
                        kotlin("jvm") version "2.0.21"
                        id("entkt")
                    }
                    repositories { mavenCentral() }

                    entkt {
                        packageName.set("com.example.ent")
                    }

                    tasks.named<entkt.gradle.GenerateEntktTask>("generateEntkt") {
                        schemaClasspath.from(project(":schema").tasks.named("compileKotlin").map {
                            (it as org.jetbrains.kotlin.gradle.tasks.KotlinCompile).destinationDirectory
                        })
                        schemaClasspath.from(files("${schemaJar.absolutePath.replace("\\", "\\\\")}"))
                    }
                    """.trimIndent()
                )
            }

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(":app:generateEntkt", "--stacktrace")
                .withPluginClasspath()
                .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":app:generateEntkt")?.outcome)

            val generatedDir = appDir.resolve("build/generated/entkt/com/example/ent")
            assertTrue(generatedDir.resolve("Pet.kt").exists(), "Should generate Pet.kt")
            assertTrue(generatedDir.resolve("PetCreate.kt").exists(), "Should generate PetCreate.kt")
            assertTrue(generatedDir.resolve("PetUpdate.kt").exists(), "Should generate PetUpdate.kt")
            assertTrue(generatedDir.resolve("PetQuery.kt").exists(), "Should generate PetQuery.kt")

            val entityContent = generatedDir.resolve("Pet.kt").readText()
            assertTrue(entityContent.contains("data class Pet"), "Should generate data class")
            assertTrue(entityContent.contains("val name: String"), "Should have name field")
            assertTrue(entityContent.contains("val age: Int?"), "Should have nullable age")
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private fun findClasspathEntry(resourceName: String): File? {
        val url = javaClass.classLoader.getResource(resourceName) ?: return null
        val path = url.path
        return when {
            path.contains("!") -> File(path.substringBefore("!").removePrefix("file:"))
            else -> {
                // Class is in a directory — walk up to the classes root
                val classFile = File(url.toURI())
                val depth = resourceName.count { it == '/' }
                var root = classFile
                repeat(depth + 1) { root = root.parentFile }
                root
            }
        }
    }
}