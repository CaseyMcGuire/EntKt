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

                object Owner : EntSchema() {
                    override fun fields() = fields {
                        string("name")
                    }
                }

                object Pet : EntSchema() {
                    override fun fields() = fields {
                        string("name")
                        int("age").optional()
                    }

                    override fun edges() = edges {
                        from("owner", Owner).unique()
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
            assertTrue(generatedDir.resolve("PetRepo.kt").exists(), "Should generate PetRepo.kt")
            assertTrue(generatedDir.resolve("Owner.kt").exists(), "Should generate Owner.kt")
            assertTrue(generatedDir.resolve("OwnerRepo.kt").exists(), "Should generate OwnerRepo.kt")
            assertTrue(generatedDir.resolve("EntClient.kt").exists(), "Should generate EntClient.kt")

            val entityContent = generatedDir.resolve("Pet.kt").readText()
            assertTrue(entityContent.contains("data class Pet"), "Should generate data class")
            assertTrue(entityContent.contains("val name: String"), "Should have name field")
            assertTrue(entityContent.contains("val age: Int?"), "Should have nullable age")
            assertTrue(entityContent.contains("val ownerId: Int?"), "Should have FK from unique edge")
            // Column refs are emitted on the companion
            assertTrue(
                entityContent.contains("val name: StringColumn = StringColumn(\"name\")"),
                "Should emit StringColumn for name field",
            )
            assertTrue(
                entityContent.contains("val ownerId: NullableComparableColumn<Int>"),
                "Should emit NullableComparableColumn for optional edge FK",
            )
            // I/O entry points live on the repo, not the entity companion
            assertTrue(!entityContent.contains("fun create("), "create() should not live on entity")
            assertTrue(!entityContent.contains("fun query("), "query() should not live on entity")
            assertTrue(!entityContent.contains("fun update("), "update() should not live on entity")

            val createContent = generatedDir.resolve("PetCreate.kt").readText()
            assertTrue(createContent.contains("@EntktDsl"), "Should be annotated @EntktDsl")
            assertTrue(createContent.contains("var owner: Owner?"), "Should have owner convenience property")
            assertTrue(createContent.contains("var ownerId: Int?"), "Should have ownerId FK property")

            val queryContent = generatedDir.resolve("PetQuery.kt").readText()
            assertTrue(queryContent.contains("@EntktDsl"), "Query class should be annotated @EntktDsl")
            assertTrue(
                queryContent.contains("`where`(predicate: Predicate)"),
                "Query class should have where(Predicate)",
            )
            assertTrue(
                queryContent.contains("fun orderBy(`field`: OrderField)"),
                "Query class should have orderBy(OrderField)",
            )
            // Per-field predicate methods are gone — predicates go through column refs
            assertTrue(!queryContent.contains("whereHasOwner"), "Should not emit old whereHasOwner alias")
            assertTrue(!queryContent.contains("whereOwnerIdEq"), "Should not emit old per-field predicate")

            // Repo is the DI seam — takes a Driver, exposes create/query/update/byId
            val repoContent = generatedDir.resolve("PetRepo.kt").readText()
            assertTrue(repoContent.contains("class PetRepo"), "Should generate PetRepo class")
            assertTrue(repoContent.contains("import entkt.runtime.Driver"), "Should import Driver")
            assertTrue(repoContent.contains("driver: Driver"), "Should take Driver in constructor")
            assertTrue(
                repoContent.contains("fun create(block: PetCreate.() -> Unit): PetCreate"),
                "Repo should expose create(block)",
            )
            assertTrue(
                repoContent.contains("fun update(entity: Pet, block: PetUpdate.() -> Unit): PetUpdate"),
                "Repo should expose update(entity, block)",
            )
            assertTrue(
                repoContent.contains("fun query(block: PetQuery.() -> Unit = {}): PetQuery"),
                "Repo should expose query(block)",
            )

            // EntClient wires repos together — this is the DI entry point
            val clientContent = generatedDir.resolve("EntClient.kt").readText()
            assertTrue(clientContent.contains("class EntClient"), "Should generate EntClient class")
            assertTrue(clientContent.contains("driver: Driver"), "Client should take Driver")
            assertTrue(
                clientContent.contains("val pets: PetRepo = PetRepo(driver)"),
                "Client should expose pets: PetRepo",
            )
            assertTrue(
                clientContent.contains("val owners: OwnerRepo = OwnerRepo(driver)"),
                "Client should expose owners: OwnerRepo",
            )
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