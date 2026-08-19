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

/**
 * Compile-time proof of the operation-result algebra's construction
 * authority (RFC "Result Construction Authority"):
 *
 * - only EntKt creates the framework-owned `Failed` variants: their
 *   constructors and `copy()` (via `@ConsistentCopyVisibility`) are
 *   `internal` to the runtime module, so ordinary application code can
 *   neither fabricate a failure nor re-state a real one;
 * - the deliberate escape hatch is each companion's public
 *   `failedForInternalUse(...)`, guarded by the error-level
 *   `@EntktInternal` opt-in — generated files opt in, and application
 *   tests may opt in explicitly, without the constructor or `copy()`
 *   ever becoming public;
 * - `Success` stays publicly constructible, and both
 *   `DriverTransactionResult` variants stay publicly constructible for
 *   third-party drivers;
 * - `EntMutationException` is sealed with exactly six framework-owned
 *   subtypes: no application subtype, and an exhaustive `when` needs no
 *   `else` branch.
 *
 * The snippets compile against the real runtime jar on the inherited
 * classpath — a different module than the snippet, which is exactly the
 * application posture. Diagnostic matching is loose (shared substrings
 * across K1/K2 spellings), following [ReadClientPostureCompileTest].
 */
class ResultConstructionAuthorityCompileTest {

    private fun compile(vararg sources: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun snippet(name: String, code: String) = SourceFile.kotlin(name, code.trimIndent())

    private fun assertFailsMentioning(result: JvmCompilationResult, vararg fragments: String) {
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the snippet to fail compilation but it succeeded",
        )
        for (fragment in fragments) {
            assertTrue(
                result.messages.contains(fragment),
                "Expected a diagnostic mentioning '$fragment', got:\n${result.messages}",
            )
        }
    }

    // ---- (a) no opt-in: constructors, copy(), and factories are closed ----

    @Test
    fun `application code cannot construct ReadResult Failed without opt-in`() {
        assertFailsMentioning(
            compile(
                snippet(
                    "Fabricate.kt",
                    """
                    package com.example.app

                    import entkt.runtime.result.ReadResult

                    val fabricated = ReadResult.Failed(RuntimeException("not mine to make"))

                    fun restate(real: ReadResult.Failed) = real.copy(exception = RuntimeException("x"))
                    """,
                ),
            ),
            "internal",
        )
    }

    @Test
    fun `application code cannot construct MutationResult Failed without opt-in`() {
        assertFailsMentioning(
            compile(
                snippet(
                    "Fabricate.kt",
                    """
                    package com.example.app

                    import entkt.runtime.result.EntTargetAbsentException
                    import entkt.runtime.result.EntityKey
                    import entkt.runtime.result.MutationResult

                    val exception = EntTargetAbsentException("User", EntityKey("id", 1L))
                    val fabricated = MutationResult.Failed(exception)

                    fun restate(real: MutationResult.Failed) = real.copy(exception = exception)
                    """,
                ),
            ),
            "internal",
        )
    }

    @Test
    fun `application code cannot construct TransactionResult Failed without opt-in`() {
        assertFailsMentioning(
            compile(
                snippet(
                    "Fabricate.kt",
                    """
                    package com.example.app

                    import entkt.runtime.result.TransactionFailureState
                    import entkt.runtime.result.TransactionResult

                    val fabricated = TransactionResult.Failed(
                        RuntimeException("x"),
                        TransactionFailureState.NotCommitted,
                    )

                    fun restate(real: TransactionResult.Failed) =
                        real.copy(transactionState = TransactionFailureState.OutcomeUnknown)
                    """,
                ),
            ),
            "internal",
        )
    }

    @Test
    fun `the guarded factories require the EntktInternal opt-in`() {
        // One factory per result type; each use must fail on the opt-in
        // diagnostic (the factories themselves are public, so this cannot
        // be a visibility or resolution failure).
        val result = compile(
            snippet(
                "Factories.kt",
                """
                package com.example.app

                import entkt.runtime.result.EntTargetAbsentException
                import entkt.runtime.result.EntityKey
                import entkt.runtime.result.MutationResult
                import entkt.runtime.result.ReadResult
                import entkt.runtime.result.TransactionFailureState
                import entkt.runtime.result.TransactionResult

                val read = ReadResult.failedForInternalUse(RuntimeException("x"))
                val mutation = MutationResult.failedForInternalUse(
                    EntTargetAbsentException("User", EntityKey("id", 1L)),
                )
                val transaction = TransactionResult.failedForInternalUse(
                    RuntimeException("x"),
                    TransactionFailureState.NotCommitted,
                )
                """,
            ),
        )
        assertFailsMentioning(result, "EntktInternal")
    }

    // ---- (b) generated code and opted-in tests can construct failures ----

    @Test
    fun `generated code constructs framework failures through the guarded factories`() {
        // The generated output opts in per-file and reaches every
        // failedForInternalUse call site (capture boundaries, privacy and
        // validation rejections, driver classification); compiling it
        // against the real runtime proves generated construction works.
        val car = Car()
        val user = User()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            car::class to car,
            user::class to user,
        )
        car.finalize(registry)
        user.finalize(registry)
        val sources = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(car), SchemaInput(user)))
            .toCompileTestSources()
        val result = KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the generated output to compile against the runtime, got:\n${result.messages}",
        )
    }

    @Test
    fun `an opted-in application test can construct every Failed via the guarded factory`() {
        val result = compile(
            snippet(
                "OptedInFixtures.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)

                package com.example.app

                import entkt.runtime.result.EntTargetAbsentException
                import entkt.runtime.result.EntityKey
                import entkt.runtime.result.MutationResult
                import entkt.runtime.result.ReadResult
                import entkt.runtime.result.TransactionFailureState
                import entkt.runtime.result.TransactionResult

                val read: ReadResult<Nothing> = ReadResult.failedForInternalUse(RuntimeException("x"))
                val mutation: MutationResult<Nothing> = MutationResult.failedForInternalUse(
                    EntTargetAbsentException("User", EntityKey("id", 1L)),
                )
                val transaction: TransactionResult<Nothing> = TransactionResult.failedForInternalUse(
                    RuntimeException("x"),
                    TransactionFailureState.NotCommitted,
                )
                """,
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the opted-in factory snippet to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `opting in does not expose the internal constructor or copy`() {
        // The opt-in unlocks only the factory: visibility is a separate,
        // stricter gate, so the constructor and copy() stay closed.
        assertFailsMentioning(
            compile(
                snippet(
                    "OptedInFabricate.kt",
                    """
                    @file:OptIn(entkt.query.EntktInternal::class)

                    package com.example.app

                    import entkt.runtime.result.ReadResult

                    val fabricated = ReadResult.Failed(RuntimeException("x"))

                    fun restate(real: ReadResult.Failed) = real.copy(exception = RuntimeException("x"))
                    """,
                ),
            ),
            "internal",
        )
    }

    // ---- (c) the sealed mutation-failure algebra ----

    @Test
    fun `EntMutationException cannot be subclassed outside the runtime module`() {
        assertFailsMentioning(
            compile(
                snippet(
                    "Subtype.kt",
                    """
                    package com.example.app

                    import entkt.runtime.result.EntMutationException
                    import entkt.runtime.result.MutationWriteState

                    class HomegrownFailure : EntMutationException(
                        MutationWriteState.NotPersisted,
                        "applications do not extend the algebra",
                    )
                    """,
                ),
            ),
            "sealed",
        )
    }

    @Test
    fun `a when over the six mutation failure subtypes is exhaustive without an else`() {
        val result = compile(
            snippet(
                "Exhaustive.kt",
                """
                package com.example.app

                import entkt.runtime.result.EntConflictException
                import entkt.runtime.result.EntConstraintViolationException
                import entkt.runtime.result.EntMutationException
                import entkt.runtime.result.EntMutationPrivacyDeniedException
                import entkt.runtime.result.EntTargetAbsentException
                import entkt.runtime.result.EntUnexpectedMutationException
                import entkt.runtime.result.EntValidationException

                fun describe(e: EntMutationException): String = when (e) {
                    is EntTargetAbsentException -> "target absent"
                    is EntMutationPrivacyDeniedException -> "privacy denied"
                    is EntValidationException -> "validation"
                    is EntConstraintViolationException -> "constraint"
                    is EntConflictException -> "conflict"
                    is EntUnexpectedMutationException -> "unexpected"
                }
                """,
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the else-free when over the six subtypes to compile, got:\n${result.messages}",
        )
    }

    // ---- (d) the public construction surface ----

    @Test
    fun `Success variants and both DriverTransactionResult variants are publicly constructible`() {
        val result = compile(
            snippet(
                "PublicConstruction.kt",
                """
                package com.example.app

                import entkt.runtime.driver.DriverTransactionResult
                import entkt.runtime.result.MutationResult
                import entkt.runtime.result.ReadResult
                import entkt.runtime.result.TransactionFailureState
                import entkt.runtime.result.TransactionResult

                val read: ReadResult<String?> = ReadResult.Success(null)
                val mutation: MutationResult<Unit> = MutationResult.Success(Unit)
                val transaction: TransactionResult<Int> = TransactionResult.Success(1)

                // Third-party drivers report authoritative outcomes, so both
                // driver variants keep public constructors.
                val committed: DriverTransactionResult<Int> = DriverTransactionResult.Success(1)
                val failed: DriverTransactionResult<Nothing> = DriverTransactionResult.Failed(
                    RuntimeException("x"),
                    TransactionFailureState.OutcomeUnknown,
                )
                """,
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the public-construction snippet to compile, got:\n${result.messages}",
        )
    }
}
