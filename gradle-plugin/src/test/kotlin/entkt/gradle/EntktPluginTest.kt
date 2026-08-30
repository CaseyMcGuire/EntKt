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
        val kotlinVersion = KotlinVersion.CURRENT.let { "${it.major}.${it.minor}.${it.patch}" }
        val projectDir = File.createTempFile("entkt-test", "").apply {
            delete()
            mkdirs()
        }

        try {
            // Find the entkt schema jar on the test classpath
            val schemaJar = findClasspathEntry("entkt/schema/EntSchema.class")
                ?: throw IllegalStateException("Cannot find entkt-schema on test classpath")

            // The plugin uses JavaExec with the entktCodegen configuration.
            // Provide the full test classpath so the JavaExec process can
            // find codegen classes and all transitive dependencies.
            val codegenClasspath = System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .joinToString(",\n                        ") { "\"${it.replace("\\", "\\\\")}\"" }

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
                        kotlin("jvm") version "$kotlinVersion"
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

                data class PetMeta(val tags: List<String>)

                class Owner : EntSchema("owners", clientName = "owners") {
                    override fun id() = EntId.int()
                    val name by string("name")
                }

                class Pet : EntSchema("pets", clientName = "pets") {
                    override fun id() = EntId.int()
                    val name by string("name")
                    val age by int("age").nullable()
                    val meta by json<PetMeta>("meta").nullable()

                    val owner by belongsTo<Owner>("owner").nullable()
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
                        kotlin("jvm") version "$kotlinVersion"
                        id("io.entkt")
                    }
                    repositories { mavenCentral() }

                    entkt {
                        packageName.set("com.example.ent")
                    }

                    dependencies {
                        schemas(project(":schema"))
                        entktCodegen(files(
                            $codegenClasspath
                        ))
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
            assertTrue(generatedDir.resolve("PetCreateDraft.kt").exists(), "Should generate PetCreateDraft.kt")
            assertTrue(
                generatedDir.resolve("PetUpdateDraft.kt").exists(),
                "Should generate PetUpdateDraft.kt",
            )
            assertTrue(generatedDir.resolve("PetQuery.kt").exists(), "Should generate PetQuery.kt")
            assertTrue(generatedDir.resolve("PetRepo.kt").exists(), "Should generate PetRepo.kt")
            assertTrue(generatedDir.resolve("Owner.kt").exists(), "Should generate Owner.kt")
            assertTrue(generatedDir.resolve("OwnerRepo.kt").exists(), "Should generate OwnerRepo.kt")
            assertTrue(generatedDir.resolve("EntClient.kt").exists(), "Should generate EntClient.kt")

            val entityContent = generatedDir.resolve("Pet.kt").readText()
            assertTrue(entityContent.contains("data class Pet"), "Should generate data class")
            // jsonMapper default threads plugin -> GenerateMain arg 3 -> EntGenerator:
            // the JSON column's metadata must target the kotlinx mapper and carry
            // its serializer expression.
            val entityFlat = entityContent.replace("\\s+".toRegex(), " ")
            assertTrue(
                entityFlat.contains("mapper = JsonMapperIds.KOTLINX"),
                "Default jsonMapper should stamp the kotlinx id into SCHEMA metadata",
            )
            assertTrue(
                entityFlat.contains("kotlinxSerializer = PetMeta.serializer()"),
                "Default jsonMapper should emit the kotlinx serializer expression",
            )
            assertTrue(entityContent.contains("val name: String"), "Should have name field")
            assertTrue(entityContent.contains("val age: Int?"), "Should have nullable age")
            assertTrue(entityContent.contains("val ownerId: Int?"), "Should have FK from unique edge")
            // Column refs are emitted on the companion. After the
            // phantom-typed query scopes, every column class carries
            // the owning entity as its first type argument.
            assertTrue(
                entityContent.contains("val name: StringColumn<Pet> = StringColumn<Pet>(\"name\")"),
                "Should emit StringColumn<Pet> for name field",
            )
            assertTrue(
                entityContent.contains("val ownerId: NullableIntegralColumn<Pet, Int>"),
                "Should emit NullableIntegralColumn<Pet, Int> for optional edge FK",
            )
            // I/O entry points live on the repo, not the entity companion
            assertTrue(!entityContent.contains("fun create("), "create() should not live on entity")
            assertTrue(!entityContent.contains("fun query("), "query() should not live on entity")
            assertTrue(!entityContent.contains("fun update("), "update() should not live on entity")

            val createContent = generatedDir.resolve("PetCreateDraft.kt").readText()
            assertTrue(createContent.contains("@EntktDsl"), "Should be annotated @EntktDsl")
            assertTrue(!createContent.contains("var owner: Owner?"), "Must not synthesize owner entity setter")
            assertTrue(createContent.contains("var ownerId: Int?"), "Should have ownerId FK property")

            val queryContent = generatedDir.resolve("PetQuery.kt").readText()
            assertTrue(queryContent.contains("@EntktDsl"), "Query class should be annotated @EntktDsl")
            val queryFlat = queryContent.replace("\\s+".toRegex(), " ")
            assertTrue(
                queryFlat.contains("EntityQueryBuilder<Pet, PetQuery>"),
                "Query class should inherit its common builder API from EntityQueryBuilder",
            )
            assertTrue(
                !queryContent.contains("fun `where`") && !queryContent.contains("fun orderBy"),
                "Query class should not duplicate runtime-owned builder methods",
            )
            // Per-field predicate methods are gone — predicates go through column refs
            assertTrue(!queryContent.contains("whereHasOwner"), "Should not emit old whereHasOwner alias")
            assertTrue(!queryContent.contains("whereOwnerIdEq"), "Should not emit old per-field predicate")

            // Repo is the DI seam — takes a DatabaseDriver, exposes create/query/update/byId
            val repoContent = generatedDir.resolve("PetRepo.kt").readText()
            val repoFlat = repoContent.replace("\\s+".toRegex(), " ")
            assertTrue(repoContent.contains("class PetRepo"), "Should generate PetRepo class")
            assertTrue(repoContent.contains("import entkt.runtime.driver.DatabaseDriver"), "Should import DatabaseDriver")
            assertTrue(repoContent.contains("driver: DatabaseDriver"), "Should take DatabaseDriver in constructor")
            assertTrue(
                repoContent.contains("fun create(block: PetCreateDraft.() -> Unit):") &&
                    repoContent.contains("CreateMutation<PetCreateDraft, Pet>"),
                "Repo should expose create(block)",
            )
            // transaction locking: `update(...)` accepts an optional UpdateConsistency
            // per-save argument that defaults to the client's configured
            // default. The signature wraps across multiple lines under
            // KotlinPoet, so check the constituents rather than the full
            // signature string.
            assertTrue(repoContent.contains("fun update("), "Repo should expose update(...)")
            assertTrue(repoContent.contains("id: Int"), "update should take id")
            assertTrue(
                repoContent.contains("consistency: UpdateConsistency = client.defaultUpdateConsistency"),
                "update should take a per-save UpdateConsistency override defaulting to the client's default",
            )
            assertTrue(
                repoFlat.contains("block: PetUpdateDraft.() -> Unit"),
                "update should take a draft block",
            )
            assertTrue(
                repoFlat.contains("): UpdateMutation<PetUpdateDraft, Pet>"),
                "update should return UpdateMutation<PetUpdateDraft, Pet>",
            )
            assertTrue(
                repoContent.contains("fun query(block: PetQuery.() -> Unit = {}): PetQuery"),
                "Repo should expose query(block)",
            )

            // EntClient wires repos together — this is the DI entry point
            val clientContent = generatedDir.resolve("EntClient.kt").readText()
            assertTrue(clientContent.contains("class EntClient"), "Should generate EntClient class")
            assertTrue(clientContent.contains("driver: DatabaseDriver"), "Client should take DatabaseDriver")
            val clientFlat = clientContent.replace("\\s+".toRegex(), " ")
            assertTrue(
                clientFlat.contains(
                    "override val pets: PetRepo = PetRepo( driver = driver, client = this, " +
                        "configuredHooks = configuration.hooks.pets, " +
                        "configuredPrivacy = configuration.policies.petsPrivacyConfig, " +
                        "configuredValidation = configuration.policies.petsValidationConfig, )",
                ),
                "Client should expose pets: PetRepo",
            )
            assertTrue(
                clientFlat.contains(
                    "override val owners: OwnerRepo = OwnerRepo( driver = driver, client = this, " +
                        "configuredHooks = configuration.hooks.owners, " +
                        "configuredPrivacy = configuration.policies.ownersPrivacyConfig, " +
                        "configuredValidation = configuration.policies.ownersValidationConfig, )",
                ),
                "Client should expose owners: OwnerRepo",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `jsonMapper setting flows through the plugin to generated jackson metadata`() {
        val kotlinVersion = KotlinVersion.CURRENT.let { "${it.major}.${it.minor}.${it.patch}" }
        val projectDir = File.createTempFile("entkt-test-jackson", "").apply {
            delete()
            mkdirs()
        }

        try {
            val schemaJar = findClasspathEntry("entkt/schema/EntSchema.class")
                ?: throw IllegalStateException("Cannot find entkt-schema on test classpath")
            val codegenClasspath = System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .joinToString(",\n                        ") { "\"${it.replace("\\", "\\\\")}\"" }

            projectDir.resolve("settings.gradle.kts").writeText(
                """
                include("schema")
                include("app")
                """.trimIndent()
            )

            val schemaModuleDir = projectDir.resolve("schema")
            schemaModuleDir.resolve("build.gradle.kts").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    plugins {
                        kotlin("jvm") version "$kotlinVersion"
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
            // PetMeta is a plain data class — no @Serializable, no
            // serialization plugin. That's the Jackson mapper's whole point.
            schemaSrc.resolve("Schemas.kt").writeText(
                """
                package com.example.schema

                import entkt.schema.*

                data class PetMeta(val tags: List<String>)

                class Pet : EntSchema("pets", clientName = "pets") {
                    override fun id() = EntId.int()
                    val name by string("name")
                    val meta by json<PetMeta>("meta").nullable()
                }
                """.trimIndent()
            )

            val appDir = projectDir.resolve("app")
            appDir.resolve("build.gradle.kts").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    plugins {
                        kotlin("jvm") version "$kotlinVersion"
                        id("io.entkt")
                    }
                    repositories { mavenCentral() }

                    entkt {
                        packageName.set("com.example.ent")
                        jsonMapper.set("jackson")
                    }

                    dependencies {
                        schemas(project(":schema"))
                        entktCodegen(files(
                            $codegenClasspath
                        ))
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

            val entityContent = projectDir
                .resolve("app/build/generated/entkt/com/example/ent/Pet.kt")
                .readText()
            val entityFlat = entityContent.replace("\\s+".toRegex(), " ")
            assertTrue(
                entityFlat.contains("mapper = JsonMapperIds.JACKSON"),
                "entkt { jsonMapper.set(\"jackson\") } must reach the SCHEMA metadata: $entityFlat",
            )
            assertTrue(
                !entityContent.contains("kotlinxSerializer"),
                "Jackson-mode generated code must not emit a kotlinx serializer",
            )
            assertTrue(
                !entityContent.contains("kotlinx.serialization"),
                "Jackson-mode generated code must reference no kotlinx symbols",
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
