@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequiredCreateCompileTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
    }

    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.long()
        val title by string("display_title").immutable()
        val notes by string("notes").nullable()
        val published by bool("published").default(false)
        val owner by belongsTo<Owner>("owner_id")
        val reviewer by belongsTo<Owner>("reviewer_id").nullable()
    }

    private class Defaults : EntSchema("defaults", clientName = "defaults") {
        override fun id() = EntId.uuid()
        val enabled by bool("enabled").default(true)
        val createdAt by instant("created_at").defaultNow()
        val label by string("label").nullable()
    }

    private class ExplicitDefaults : EntSchema("explicit_defaults", clientName = "explicitDefaults") {
        override fun id() = EntId.string()
        val enabled by bool("enabled").default(true)
    }

    private class TypedFields : EntSchema("typed_fields", clientName = "typedFields") {
        override fun id() = EntId.int()
        val day by enum<DayOfWeek>("day")
        val date by date("date")
        val payload by bytes("payload")
        val labels by json<List<String>>("labels")
    }

    private class Backed : EntSchema("backed", clientName = "backed") {
        override fun id() = EntId.long()
        val writer by long("writer_id").immutable()
        val author by belongsTo<Owner>("writer_id").field(writer)
        val defaultOwnerId by long("default_owner_id").default(42L)
        val defaultOwner by belongsTo<Owner>("default_owner_id").field(defaultOwnerId)
    }

    private class Names : EntSchema("names", clientName = "names") {
        override fun id() = EntId.long()
        val block by string("block_value")
        val create by string("create_value")
    }

    private fun compile(
        body: String,
        schemas: List<EntSchema> = listOf(Owner(), Record()),
    ): JvmCompilationResult {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent").generate(schemas.map(::SchemaInput))
        val application = SourceFile.kotlin(
            "Application.kt",
            """
            package com.example.app

            import com.example.ent.*
            import entkt.runtime.driver.DatabaseDriver
            import entkt.runtime.driver.NoopDriver
            import entkt.runtime.mutation.PendingCreateMutation
            import entkt.runtime.privacy.Viewer
            import entkt.runtime.privacy.ViewerContext
            import entkt.types.Bytes
            import java.time.DayOfWeek
            import java.time.LocalDate

            $body
            """.trimIndent(),
        )
        return compileSources(generated.toCompileTestSources() + application)
    }

    @Test
    fun `required parameters and the original DSL coexist on root and transaction clients`() {
        val result = compile(
            """
            fun use(client: EntClientScope, viewer: ViewerContext) {
                val pending: PendingCreateMutation<RecordCreateDraft, Record> =
                    client.records.create(title = "Typed", ownerId = 7L)
                pending.saveAndLoad(viewer)
                client.records.create(ownerId = 7L, title = "Configured") {
                    notes = "Optional"
                    published = true
                    reviewerId = null
                }
                client.records.create {
                    title = "DSL"
                    ownerId = 7L
                }
                client.records.create(block = { title = null })
            }

            fun useBoth(client: EntClient, viewer: ViewerContext) {
                use(client, viewer)
                client.withTransaction { tx -> use(tx, viewer) }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `empty defaulted and explicit ID schemas expose unambiguous conveniences`() {
        val result = compile(
            """
            fun use(client: EntClientScope) {
                client.owners.create()
                client.owners.create {}
                client.defaults.create()
                client.defaults.create { enabled = false }
                client.sessions.create(id = "typed", token = "token")
                client.sessions.create(id = "configured", token = "token") { token = "override" }
                client.sessions.create(id = "dsl") { token = "token" }
                client.explicitDefaults.create(id = "typed")
                client.explicitDefaults.create(id = "dsl") { enabled = false }
            }
            """.trimIndent(),
            schemas = listOf(Owner(), Defaults(), Session(), ExplicitDefaults()),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `generated overloads preserve draft values assignment tracking and explicit IDs when executed`() {
        val result = compile(
            """
            fun verifyCreation() {
                val writes = mutableListOf<Map<String, Any?>>()
                val driver = object : DatabaseDriver by NoopDriver {
                    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
                        writes += values
                        return values
                    }
                }
                val client = EntClient(driver)
                val first = client.records.create(title = "first", ownerId = 7L) {
                    check(title == "first" && ownerId == 7L)
                    check(isSet(Record.title) && isSet(Record.ownerId))
                    check(!isSet(Record.published))
                    title = "configured"
                }
                val second = client.records.create(title = "second", ownerId = 8L)
                first.configure { check(title == "configured" && ownerId == 7L) }
                second.configure { check(title == "second" && ownerId == 8L) }
                client.defaults.create().configure {
                    check(enabled == null && createdAt == null)
                    check(!isSet(Defaults.enabled) && !isSet(Defaults.createdAt))
                }
                check(writes.isEmpty())

                val viewer = ViewerContext(Viewer.PrivacyBypass("create overload test"))
                val session = client.sessions.create(id = "required", token = "token")
                    .saveAndLoad(viewer).getOrThrow()
                val defaulted = client.explicitDefaults.create(id = "defaulted")
                    .saveAndLoad(viewer).getOrThrow()
                check(session.id == "required" && session.token == "token")
                check(defaulted.id == "defaulted" && defaulted.enabled)
                check(writes == listOf(
                    mapOf("id" to "required", "token" to "token"),
                    mapOf("id" to "defaulted", "enabled" to true),
                ))
            }
            """.trimIndent(),
            schemas = listOf(Owner(), Record(), Defaults(), Session(), ExplicitDefaults()),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        result.classLoader.loadClass("com.example.app.ApplicationKt").getMethod("verifyCreation").invoke(null)
    }

    @Test
    fun `parameters preserve field types and backing names without duplicating or requiring defaulted FKs`() {
        val result = compile(
            """
            fun use(client: EntClient) {
                client.typedFields.create(
                    day = DayOfWeek.MONDAY,
                    date = LocalDate.of(2026, 9, 26),
                    payload = Bytes.of(byteArrayOf(1, 2)),
                    labels = listOf("one", "two"),
                )
                client.backed.create(writer = 7L)
                client.backed.create(writer = 7L) { defaultOwnerId = 8L }
            }
            """.trimIndent(),
            schemas = listOf(Owner(), TypedFields(), Backed()),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `schema field names cannot shadow the configuration lambda or superclass delegation`() {
        val result = compile(
            """
            fun use(client: EntClient) {
                client.names.create(block = "first", create = "second") {
                    block = "overridden"
                }
            }
            """.trimIndent(),
            schemas = listOf(Names()),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `missing and nullable required arguments fail at compilation`() {
        val result = compile(
            """
            fun missingTitle(client: EntClient) = client.records.create(ownerId = 7L)
            fun missingOwner(client: EntClient) = client.records.create(title = "Missing owner")
            fun nullTitle(client: EntClient) = client.records.create(title = null, ownerId = 7L)
            fun nullOwner(client: EntClient) = client.records.create(title = "Null owner", ownerId = null)
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val errors = result.messages.lineSequence().filter { it.startsWith("e:") }.toList()
        assertEquals(4, errors.size, result.messages)
        assertEquals(2, errors.count { it.contains("None of the following candidates is applicable") }, result.messages)
        assertEquals(2, errors.count { it.contains("Null cannot be a value") }, result.messages)
        assertTrue(result.messages.contains("create(title: String, ownerId: Long,"), result.messages)
    }

    @Test
    fun `required overloads preserve generated and explicit ID contracts`() {
        val result = compile(
            """
            fun generatedId(client: EntClient) = client.records.create(id = 1L, title = "Invalid", ownerId = 7L)
            fun missingId(client: EntClient) = client.sessions.create(token = "Invalid")
            fun wrongId(client: EntClient) = client.sessions.create(id = 1L, token = "Invalid")
            fun wrongOwner(client: EntClient) = client.records.create(title = "Invalid", ownerId = "wrong")
            """.trimIndent(),
            schemas = listOf(Owner(), Record(), Session()),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val errors = result.messages.lineSequence().filter { it.startsWith("e:") }.toList()
        assertEquals(4, errors.size, result.messages)
        assertEquals(2, errors.count { it.contains("None of the following candidates is applicable") }, result.messages)
        assertEquals(2, errors.count { it.contains("Argument type mismatch") }, result.messages)
        assertTrue(result.messages.contains("create(id: String, token: String,"), result.messages)
    }
}
