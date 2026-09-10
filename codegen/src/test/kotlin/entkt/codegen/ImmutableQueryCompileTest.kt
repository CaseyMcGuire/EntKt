@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImmutableQueryCompileTest {
    private fun compile(body: String, internal: Boolean = false): JvmCompilationResult {
        val schemas = listOf(Car(), User())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent").generate(schemas.map(::SchemaInput))
        val optIn = if (internal) "@file:OptIn(entkt.query.EntktInternal::class)" else ""
        return KotlinCompilation().apply {
            sources = generated.toCompileTestSources() + SourceFile.kotlin(
                "Application.kt",
                """
                $optIn
                package com.example.app

                import com.example.ent.*
                import entkt.query.Op
                import entkt.query.Predicate
                import entkt.query.OrderField
                import entkt.runtime.driver.DatabaseDriver
                import entkt.runtime.driver.NoopDriver
                import entkt.runtime.privacy.ViewerContext
                import entkt.runtime.query.EdgeLoad
                import entkt.runtime.query.EdgeVisibility
                import entkt.runtime.query.QuerySource
                import entkt.runtime.result.EntQueryConfigurationException
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
    fun `root read-only index and traversal entries retain typed configuration blocks`() {
        val result = compile(
            """
            fun use(client: EntClient, rules: ReadOnlyEntClient, context: ViewerContext) {
                val base: UserQuery = client.users.query { where(User.active.eq(true)); limit(10) }
                val refined: UserQuery = base.configure { offset(5); loadCars { limit(2) }.filterVisible() }
                val page: UserQuery = base.limit(3).offset(1)
                val readOnly: UserReadQuery = rules.users.query { loadCars { where(Car.year.gte(2020)) } }
                val indexed: UserQuery = client.users.indexes.email("a@example.com").query { loadCars() }
                val ruleIndexed: UserReadQuery = rules.users.indexes.email("a@example.com").query { limit(1) }
                val traversed: CarQuery = base.queryCars { where(Car.year.gte(2020)); loadUser() }
                val predicate: Predicate<User> = User.cars.has { where(Car.year.gte(2020)); where(Car.year.lte(2026)) }
                val rows: ReadResult<List<User>> = refined.all(context)
                val one: ReadResult<Car?> = traversed.firstOrNull(context)
                client.withTransaction { tx -> tx.users.query { limit(1) }.all(context) }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `configuration scopes do not expose terminals or traversal and queries do not expose loads`() {
        val result = compile(
            """
            fun invalid(client: EntClient, context: ViewerContext) {
                client.users.query { all(context) }
                client.users.query { queryCars() }
                client.users.query { loadCars { firstOrNull(context) } }
                client.users.query { loadCars { queryUser() } }
                client.users.query().loadCars()
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in listOf("all", "queryCars", "firstOrNull", "queryUser", "loadCars")) {
            assertTrue(result.messages.contains("Unresolved reference '$name'"), result.messages)
        }
    }

    @Test
    fun `generated query branches and escaped configuration preserve immutable graphs without IO`() {
        val result = compile(
            """
            fun exercise() {
                var calls = 0
                val driver = object : DatabaseDriver by NoopDriver {
                    override fun query(
                        table: String, predicates: List<Predicate<*>>, orderBy: List<OrderField<*>>,
                        limit: Int?, offset: Int?,
                        lockMode: entkt.runtime.query.QueryLockMode,
                    ): List<Map<String, Any?>> { calls++; error("Construction must not query") }
                }
                val ids = mutableListOf(UUID(0, 1))
                val base = UserQuery(driver).where(Predicate.Leaf("id", Op.IN, ids)).limit(10)
                lateinit var escaped: UserQueryScope
                lateinit var child: CarQueryScope
                lateinit var handle: EdgeLoad<UserQueryScope>
                val selected = base.configure {
                    escaped = this
                    offset(2)
                    handle = loadCars { child = this; limit(3); loadUser().filterVisible() }
                }
                ids.clear()
                escaped.limit(99)
                child.limit(99)
                handle.filterVisible()

                val original = base.captureEntityQuery()
                val graph = selected.captureEntityQuery()
                check(original.limit == 10 && original.offset == null && original.edges.isEmpty())
                check(graph.limit == 10 && graph.offset == 2)
                check(graph.edges.single().visibility == EdgeVisibility.REQUIRE_VISIBLE)
                check(graph.edges.single().target.limit == 3)
                check(graph.edges.single().target.edges.single().visibility == EdgeVisibility.FILTER_INVISIBLE)
                check((original.predicates.single() as Predicate.Leaf).value == listOf(UUID(0, 1)))
                check(base.limit(1).captureEntityQuery().predicates === original.predicates)

                val traversal = base.queryCars { limit(2) }
                val other = base.limit(20)
                val origin = traversal.captureEntityQuery().source as QuerySource.Traversal<*, *>
                check(origin.source === original && origin.source.limit == 10 && other.queryLimit == 20)
                check(traversal.queryLimit == 2)
                try {
                    selected.queryCars()
                    error("Traversal must reject selected source edges")
                } catch (expected: EntQueryConfigurationException) {
                    check(expected.message!!.contains("queryCars()"))
                }
                val has = User.cars.has { where(Car.year.gte(2020)); where(Car.year.lte(2026)) }
                check((has as Predicate.HasEdgeWith<*, *>).inner is Predicate.And)
                check(calls == 0)
            }
            """.trimIndent(),
            internal = true,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        result.classLoader.loadClass("com.example.app.ApplicationKt").getMethod("exercise").invoke(null)
    }
}
