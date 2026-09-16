@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NamedIndexHelperCompileTest {
    private class Problem : EntSchema("problems", clientName = "problems") {
        override fun id() = EntId.long()
    }

    private class TestCase : EntSchema("test_cases", clientName = "testCases") {
        override fun id() = EntId.long()
        val problem by belongsTo<Problem>("problem_id")
        val position by int("sort_position")
        val status by string("status")
        val nickname by string("nickname").nullable()
        val driver by long("driver_col")
        val driverValue by long("driver_value_col")
        val byProblemAndPosition by index("uq_problem_position", problem.fk, position).unique()
        val byStatus by index("idx_status", status)
        val byNickname by index("uq_nickname", nickname).unique()
        val byDrivers by index("idx_drivers", driver, driverValue)
        val byPosition = index("idx_position", position)
    }

    private fun source(name: String, body: String, internal: Boolean = false) = SourceFile.kotlin(
        "$name.kt",
        """
        ${if (internal) "@file:OptIn(entkt.query.EntktInternal::class)" else ""}
        package com.example.app

        import com.example.ent.*
        import entkt.query.OrderField
        import entkt.query.Predicate
        import entkt.runtime.driver.DatabaseDriver
        import entkt.runtime.driver.NoopDriver
        import entkt.runtime.privacy.*
        import entkt.runtime.query.*
        import entkt.runtime.result.EntPrivacyDeniedException
        import entkt.runtime.result.ReadResult
        import kotlin.test.assertEquals
        import kotlin.test.assertIs
        import kotlin.test.assertTrue

        $body
        """.trimIndent(),
    )

    private fun compile(vararg application: SourceFile): JvmCompilationResult {
        val schemas = listOf(Problem(), TestCase())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent")
            .generate(schemas.map(::SchemaInput)).toCompileTestSources()
        return compileSources(generated + application)
    }

    // Compile once for this suite; each runtime probe constructs its own mutable fixture.
    private val validCompilation by lazy {
        compile(
            source("Application", """
                fun use(client: EntClient, rules: ReadOnlyEntClient, viewer: ViewerContext) {
                    val full: TestCaseIndexes.ProblemIdPosition =
                        client.testCases.indexes.byProblemAndPosition(problemId = 7L, position = 3)
                    val read: TestCaseReadIndexes.ProblemIdPosition =
                        rules.testCases.indexes.byProblemAndPosition(position = 3, problemId = 7L)
                    val found: ReadResult<TestCase?> = full.find(viewer)
                    val readFound: ReadResult<TestCase?> = read.find(viewer)
                    val query: TestCaseQuery = full.query { where(TestCase.status.eq("ready")) }
                    val readQuery: TestCaseReadQuery = read.query()
                    val rows: ReadResult<List<TestCase>> = client.testCases.indexes.byStatus("ready").query().all(viewer)
                    val nullable: TestCaseReadQuery = rules.testCases.indexes.byNickname("name").query()
                    val drivers: TestCaseQuery = client.testCases.indexes.byDrivers(driver = 1L, driverValue = 2L).query()
                    client.withTransaction { tx ->
                        val txFound: ReadResult<TestCase?> =
                            tx.testCases.indexes.byProblemAndPosition(7L, 3).find(viewer)
                    }
                }
            """.trimIndent()),
            source("Probe", """
                private class Fixture {
                    var calls = 0
                    var privacyCalls = 0
                    var allow = true
                    var lastPredicates: List<Predicate<*>> = emptyList()
                    val operations = mutableListOf<ReadOperation>()
                    val driver = object : DatabaseDriver by NoopDriver {
                        override fun query(
                            table: String, predicates: List<Predicate<*>>, orderBy: List<OrderField<*>>,
                            limit: Int?, offset: Int?, lockMode: QueryLockMode,
                        ): List<Map<String, Any?>> {
                            calls++
                            lastPredicates = predicates
                            assertEquals("test_cases", table)
                            return listOf(mapOf(
                                "id" to 42L, "problem_id" to 7L, "sort_position" to 3,
                                "status" to "ready", "nickname" to null,
                                "driver_col" to 1L, "driver_value_col" to 2L,
                            ))
                        }
                    }
                    val policy = object : EntityPolicy<TestCase, TestCasePolicyScope> {
                        override fun configure(scope: TestCasePolicyScope) = scope.run {
                            privacy {
                                load(TestCaseLoadPrivacyRule { _, entity ->
                                    privacyCalls++
                                    assertEquals(42L, entity.id)
                                    if (allow) PrivacyDecision.Allow else PrivacyDecision.Deny("hidden")
                                })
                            }
                        }
                    }
                    val client = EntClient(driver) {
                        policies { testCases(policy) }
                        interceptors {
                            testCases(QueryInterceptor { _, context -> operations += context.operation }, name = "observe")
                        }
                    }
                    val named = client.testCases.indexes.byProblemAndPosition(problemId = 7L, position = 3)
                    val chained = client.testCases.indexes.problemId(7L).position(3)
                    val read = client.readOnlyClient.testCases.indexes.byProblemAndPosition(7L, 3)
                    val expected = listOf(TestCase.problemId.eq(7L), TestCase.position.eq(3))
                    val viewer = ViewerContext(Viewer.User(1L))
                }

                fun buildsPredicatesWithoutExecuting() = with(Fixture()) {
                    assertEquals(expected, named.query().captureEntityQuery().predicates)
                    assertEquals(expected, chained.query().captureEntityQuery().predicates)
                    assertEquals(expected, read.query().captureEntityQuery().predicates)
                    assertEquals(0, calls)
                    assertEquals(0, privacyCalls)
                    assertTrue(operations.isEmpty())
                }

                fun returnsEquivalentRows() = with(Fixture()) {
                    val namedResult = named.find(viewer)
                    assertEquals(42L, namedResult.getOrThrow()?.id)
                    assertEquals(namedResult, chained.find(viewer))
                    assertEquals(namedResult, read.find(viewer))
                    assertEquals(expected, lastPredicates)
                    assertEquals(3, calls)
                }

                fun evaluatesPrivacyOnEveryRead() = with(Fixture()) {
                    named.find(viewer).getOrThrow()
                    chained.find(viewer).getOrThrow()
                    read.find(viewer).getOrThrow()
                    assertEquals(3, privacyCalls)

                    allow = false
                    for (denied in listOf(named.find(viewer), read.find(viewer))) {
                        val failure = assertIs<ReadResult.Failed>(denied)
                        assertIs<EntPrivacyDeniedException>(failure.exception)
                    }
                    assertEquals(5, calls)
                    assertEquals(5, privacyCalls)
                }

                fun invokesReadInterceptors() = with(Fixture()) {
                    named.find(viewer).getOrThrow()
                    chained.find(viewer).getOrThrow()
                    read.find(viewer).getOrThrow()
                    assertEquals(List(3) { ReadOperation.FIRST }, operations)
                }

                fun preservesOriginalQuery() = with(Fixture()) {
                    named.find(viewer).getOrThrow()
                    val original = named.query()
                    val filtered = original.where(TestCase.status.eq("ready"))
                    assertEquals(expected, original.captureEntityQuery().predicates)
                    assertEquals(expected + TestCase.status.eq("ready"), filtered.captureEntityQuery().predicates)
                    assertEquals(1, calls)
                }
            """.trimIndent(), internal = true),
        )
    }

    @Test
    fun `named shortcuts compile on root transaction and read-only clients`() {
        val result = validCompilation
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `named shortcuts build the same predicates as chained keys without executing reads`() {
        runProbe("buildsPredicatesWithoutExecuting")
    }

    @Test
    fun `named shortcuts return the same rows as chained and read-only lookups`() {
        runProbe("returnsEquivalentRows")
    }

    @Test
    fun `named shortcuts apply load privacy on every read`() {
        runProbe("evaluatesPrivacyOnEveryRead")
    }

    @Test
    fun `named shortcuts invoke normal read interceptors`() {
        runProbe("invokesReadInterceptors")
    }

    @Test
    fun `named query refinements leave the original query unchanged`() {
        runProbe("preservesOriginalQuery")
    }

    private fun runProbe(name: String) {
        val result = validCompilation
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        try {
            result.classLoader.loadClass("com.example.app.ProbeKt").getMethod(name).invoke(null)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    @Test
    fun `named shortcuts require complete correctly typed keys and preserve capability restrictions`() {
        val invalidCalls = mapOf(
            "MissingKey" to "client.testCases.indexes.byProblemAndPosition(problemId = 7L)",
            "WrongType" to "client.testCases.indexes.byProblemAndPosition(problemId = \"seven\", position = 3)",
            "NullKey" to "client.testCases.indexes.byNickname(null)",
            "NonUnique" to "client.testCases.indexes.byStatus(\"ready\").find(viewer)",
            "NullableUnique" to "client.testCases.indexes.byNickname(\"name\").find(viewer)",
            "Unnamed" to "client.testCases.indexes.byPosition(3)",
            "ReadOnly" to "rules.testCases.indexes.byProblemAndPosition(7L, 3).query().forUpdate()",
        )
        val result = compile(*invalidCalls.map { (name, call) ->
            source(name, """
                fun invalid$name(client: EntClient, rules: ReadOnlyEntClient, viewer: ViewerContext) {
                    $call
                }
            """.trimIndent())
        }.toTypedArray())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in invalidCalls.keys) {
            assertTrue(result.messages.lineSequence().any { it.startsWith("e:") && "$name.kt:" in it }, result.messages)
        }
    }
}
