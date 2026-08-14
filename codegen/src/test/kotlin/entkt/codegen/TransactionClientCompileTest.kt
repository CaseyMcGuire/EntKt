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
            .generate(listOf(SchemaInput("Car", car), SchemaInput("User", user)))
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
    fun `transaction blocks receive the transaction client across privacy scopes`() {
        val result = compile(
            SourceFile.kotlin(
                "TransactionClientSnippet.kt",
                """
                package com.example.app

                import com.example.ent.EntClient
                import com.example.ent.EntTransactionClient
                import entkt.runtime.privacy.PrivacyContext
                import entkt.runtime.privacy.Viewer

                fun useTransactionClient(client: EntClient) {
                    client.withTransaction { tx ->
                        val typed: EntTransactionClient = tx
                        typed.cars.query { }.all()
                        tx.withPrivacyContext(PrivacyContext(Viewer.Anonymous)) { scoped ->
                            val scopedTyped: EntTransactionClient = scoped
                            scopedTyped.users.query { }.all()
                        }
                        tx.bypassPrivacy_DANGEROUS("compile test") { scoped ->
                            scoped.cars.query { }.all()
                        }
                    }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected transaction repositories and privacy scopes to compile, got:\n${result.messages}",
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

                fun sharedHelper(client: EntClientScope) {
                    client.cars.query { }.all()
                    client.users.create { name = "helper" }.save()
                    client.currentPrivacyContext()
                }

                fun useBoth(client: EntClient) {
                    sharedHelper(client)
                    client.withTransaction { tx ->
                        sharedHelper(tx)
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
                import entkt.runtime.driver.Driver

                fun configuredClient(driver: Driver): EntClient = EntClient(driver) {
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
