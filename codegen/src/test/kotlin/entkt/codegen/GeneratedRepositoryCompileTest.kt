@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise actual generated subclasses from application code, without internal opt-ins. */
class GeneratedRepositoryCompileTest {
    private fun compile(
        body: String,
        schemas: List<EntSchema> = listOf(Car(), User(), Session()),
    ): JvmCompilationResult {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent").generate(schemas.map(::SchemaInput))

        return KotlinCompilation().apply {
            sources = generated.toCompileTestSources() + SourceFile.kotlin(
                "Application.kt",
                """
                package com.example.app

                import com.example.ent.*
                import entkt.query.Predicate
                import entkt.runtime.mutation.PendingCreateMutation
                import entkt.runtime.mutation.PendingUpdateMutation
                import entkt.runtime.mutation.RelationshipLocking
                import entkt.runtime.mutation.UpdateConsistency
                import entkt.runtime.privacy.ViewerContext
                import entkt.runtime.result.MutationResult
                import entkt.runtime.result.ReadResult
                import java.util.UUID

                $body
                """.trimIndent(),
            )
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
    }

    @Test
    fun `generated repositories preserve concrete inherited APIs on root and transaction clients`() {
        val result = compile(
            """
            fun useRepositories(client: EntClientScope, viewer: ViewerContext, car: Car, predicate: Predicate<Car>) {
                val query: CarQuery = client.cars.query { where(predicate) }
                val create: PendingCreateMutation<CarCreateDraft, Car> = client.cars.create { model = "new" }
                val update: PendingUpdateMutation<CarUpdateDraft, Car> = client.cars.update(id = 1) { model = "changed" }
                client.cars.update(
                    id = 1,
                    consistency = UpdateConsistency.Pessimistic,
                    relationshipLocking = RelationshipLocking.Canonical,
                ) { year = 2026 }
                val found: ReadResult<Car?> = client.cars.findById(viewer, 1)
                val deleted: MutationResult<Boolean> = client.cars.deleteById(viewer, 1)
                val removed: MutationResult<Unit> = client.cars.delete(viewer, car)
                val created: MutationResult<List<Car>> = client.cars.createMany(viewer, { model = "one" }, { model = "two" })
                val count: MutationResult<Int> = client.cars.deleteMany(viewer, predicate)

                val uuidLookup: ReadResult<User?> = client.users.findById(viewer, UUID.randomUUID())
                val explicit: PendingCreateMutation<SessionCreateDraft, Session> = client.sessions.create(id = "session") { token = "new" }
                val explicitUpdate: PendingUpdateMutation<SessionUpdateDraft, Session> = client.sessions.update(id = "session") { token = "changed" }
                val explicitLookup: ReadResult<Session?> = client.sessions.findById(viewer, "session")
                val explicitDelete: MutationResult<Boolean> = client.sessions.deleteById(viewer, "session")
                val explicitBulkDelete: MutationResult<Int> = client.sessions.deleteMany(viewer)
            }

            fun useBoth(client: EntClient, viewer: ViewerContext, car: Car, predicate: Predicate<Car>) {
                useRepositories(client, viewer, car, predicate)
                client.withTransaction { tx -> useRepositories(tx, viewer, car, predicate) }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `explicit ID generated repositories cannot omit the ID or create a batch`() {
        val result = compile(
            """
            fun invalid(client: EntClient, viewer: ViewerContext) {
                client.sessions.create { token = "missing ID" }
                client.sessions.createMany(viewer, { token = "unsupported" })
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("No value passed for parameter 'id'"), result.messages)
        assertTrue(result.messages.contains("Unresolved reference 'createMany'"), result.messages)
    }

    @Test
    fun `generated repositories do not expose inherited execution dependencies`() {
        val members = listOf(
            "ruleClient", "mutationExecutor", "createOperations", "updateOperation",
            "deleteOperation", "deleteManyOperation", "loadPrivacyEvaluator",
        )
        val accesses = members.joinToString("\n") { "client.cars.$it" }
        val result = compile(
            """
            fun invalid(client: EntClient) {
                $accesses
                client.cars.withTransaction { Unit }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (member in members + "withTransaction") {
            assertTrue(
                result.messages.lineSequence().any { it.contains("Cannot access") && it.contains(member) },
                result.messages,
            )
        }
    }

    @Test
    fun `transaction binding type parameter does not shadow an entity named Result`() {
        class Result : EntSchema("results", clientName = "results") {
            override fun id() = EntId.long()
        }

        val result = compile(
            """
            fun useResult(client: EntClient, viewer: ViewerContext) {
                val created: MutationResult<List<com.example.ent.Result>> = client.results.createMany(viewer, {})
            }
            """.trimIndent(),
            schemas = listOf(Result()),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
