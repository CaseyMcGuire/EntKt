@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Compile-time coverage for the transaction-only client surface. */
class TransactionClientCompileTest {

    private fun generatedSources(): List<SourceFile> {
        val car = Car()
        val user = User()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            car::class to car,
            user::class to user,
        )
        car.finalize(registry)
        user.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(car), SchemaInput(user)))
            .toCompileTestSources()
    }

    private fun compile(snippet: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = generatedSources() + snippet
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `transaction blocks receive a contextless transaction client`() {
        val result = compile(
            SourceFile.kotlin(
                "TransactionClientSnippet.kt",
                """
                package com.example.app

                import com.example.ent.EntClient
                import com.example.ent.EntTransactionClient
                import entkt.runtime.privacy.ViewerContext
                import entkt.runtime.privacy.Viewer

                fun useTransactionClient(client: EntClient) {
                    val viewerContext = ViewerContext(Viewer.Anonymous)
                    client.withTransaction { tx ->
                        val typed: EntTransactionClient = tx
                        typed.cars.query { }.all(viewerContext)
                        typed.users.query { }.all(viewerContext)
                    }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected transaction repositories with explicit contexts to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `shared repository helpers accept root and transaction clients`() {
        val result = compile(
            SourceFile.kotlin(
                "SharedClientScopeSnippet.kt",
                """
                package com.example.app

                import com.example.ent.EntClient
                import com.example.ent.EntClientScope
                import entkt.runtime.privacy.ViewerContext

                fun sharedHelper(client: EntClientScope, viewerContext: ViewerContext) {
                    client.cars.query { }.all(viewerContext)
                    client.users.create { name = "helper" }.save(viewerContext)
                }

                fun useBoth(client: EntClient, viewerContext: ViewerContext) {
                    sharedHelper(client, viewerContext)
                    client.withTransaction { tx ->
                        sharedHelper(tx, viewerContext)
                    }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected EntClientScope helpers to accept both client types, got:\n${result.messages}",
        )
    }

    @Test
    fun `hook contexts cannot start transactions`() {
        val result = compile(
            SourceFile.kotlin(
                "HookNestedTransactionSnippet.kt",
                """
                package com.example.app

                import com.example.ent.EntClient
                import entkt.runtime.driver.DatabaseDriver

                fun configuredClient(driver: DatabaseDriver): EntClient = EntClient(driver) {
                    hooks {
                        cars {
                            beforeCreate { ctx ->
                                ctx.client.withTransaction { }
                            }
                            beforeUpdate { ctx ->
                                ctx.client.withTransaction { }
                            }
                        }
                    }
                }
                """.trimIndent(),
            ),
        )

        assertNotEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(
            result.messages.contains("withTransaction") &&
                (result.messages.contains("Unresolved reference") || result.messages.contains("unresolved reference")),
            "Expected withTransaction to be absent from hook client scopes, got:\n${result.messages}",
        )
    }

    @Test
    fun `nested client transactions do not compile`() {
        val result = compile(
            SourceFile.kotlin(
                "NestedTransactionSnippet.kt",
                """
                package com.example.app

                import com.example.ent.EntClient

                fun nestedTransaction(client: EntClient) {
                    client.withTransaction { tx ->
                        tx.withTransaction { }
                    }
                }
                """.trimIndent(),
            ),
        )

        assertNotEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(
            result.messages.contains("withTransaction") &&
                (result.messages.contains("Unresolved reference") || result.messages.contains("unresolved reference")),
            "Expected withTransaction to be absent from EntTransactionClient, got:\n${result.messages}",
        )
    }

    @Test
    fun `repositories cannot expose the hidden transaction-bound EntClient`() {
        val result = compile(
            SourceFile.kotlin(
                "TransactionClientEscapeSnippet.kt",
                """
                package com.example.app

                import com.example.ent.EntClient

                fun escapeTransactionClient(client: EntClient) {
                    client.withTransaction { tx ->
                        tx.cars.client.withTransaction { }
                    }
                }
                """.trimIndent(),
            ),
        )

        assertNotEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(
            result.messages.contains("client") && result.messages.contains("private"),
            "Expected repository client backlink to be private, got:\n${result.messages}",
        )
    }
}
