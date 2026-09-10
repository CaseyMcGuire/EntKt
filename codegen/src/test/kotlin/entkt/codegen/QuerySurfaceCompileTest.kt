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

/** The full and rule-client query families stay distinct through every composition path. */
class QuerySurfaceCompileTest {
    private class Group : EntSchema("groups", clientName = "groups") {
        override fun id() = EntId.long()
        val members by manyToMany<User>("members")
            .throughEntity<Membership>(Membership::group, Membership::user)
    }

    private class Membership : EntSchema("memberships", clientName = "memberships") {
        override fun id() = EntId.long()
        val group by belongsTo<Group>("group")
        val user by belongsTo<User>("user")
    }

    private fun compile(vararg sources: SourceFile): JvmCompilationResult {
        val schemas = listOf(Car(), User(), Group(), Membership())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        return KotlinCompilation().apply {
            this.sources = EntGenerator("com.example.ent")
                .generate(schemas.map(::SchemaInput)).toCompileTestSources() + sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
    }

    private fun source(name: String, body: String, internal: Boolean = false): SourceFile =
        SourceFile.kotlin(
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
            import entkt.runtime.result.ReadResult
            import entkt.runtime.validation.ValidationDecision
            import java.time.Instant

            $body
            """.trimIndent(),
        )

    @Test
    fun `full and rule clients retain concrete query types without capability generics`() {
        val result = compile(source("Application", """
            fun use(client: EntClient, rules: ReadOnlyEntClient, ctx: ViewerContext) {
                val full: UserQuery = client.users.query().where(User.active.eq(true))
                    .orderBy(User.name.asc()).limit(10).offset(2).configure { loadCars() }
                val read: UserReadQuery = rules.users.query().where(User.active.eq(true))
                    .orderBy(User.name.asc()).limit(10).offset(2).configure { loadCars() }
                val direct: CarReadQuery = rules.users.query().queryCars { loadUser() }
                val inverse: UserReadQuery = rules.cars.query().queryUser { loadCars() }
                val fullManyToMany: UserQuery = client.groups.query().queryMembers()
                val readManyToMany: UserReadQuery = rules.groups.query().queryMembers()
                val fullIndex: UserQuery = client.users.indexes.name("A").email("a@b.c").query()
                val readIndex: UserReadQuery = rules.users.indexes.name("A").email("a@b.c").query()
                val fullRange: UserQuery = client.users.indexes.name("A").email { gte("a") }.query()
                val readRange: UserReadQuery = rules.users.indexes.name("A").email { gte("a") }.query()
                val rootRange: UserReadQuery = rules.users.indexes.createdAt { gte(Instant.EPOCH) }.query()
                val fullIndexedTraversal: CarQuery = fullIndex.queryCars()
                val readIndexedTraversal: CarReadQuery = readIndex.queryCars()
                val locking: ForUpdateQuery<User> = full.forUpdate()
                val indexedLock: ForUpdateQuery<User> = fullRange.forUpdate()
                val traversedLock: ForUpdateQuery<Car> = fullIndexedTraversal.forUpdate()
                val groupLock: ForUpdateQuery<User> = fullManyToMany.forUpdate()
                val lockedRows: ReadResult<List<User>> = locking.all(ctx)
                val lockedOne: ReadResult<User?> = locking.firstOrNull(ctx)
                val found: ReadResult<User?> = rules.users.indexes.email("a@b.c").find(ctx)
                val rows: ReadResult<List<User>> = read.all(ctx)
                val one: ReadResult<User?> = read.firstOrNull(ctx)
                val bypass: ReadResult<List<User>> = read.all(ViewerContext.privacyBypass_DANGEROUS("test"))
                client.withTransaction { tx ->
                    val txQuery: UserQuery = tx.users.query { loadCars() }
                    val txIndex: UserQuery = tx.users.indexes.email("a@b.c").query()
                    val txTraversal: CarQuery = txIndex.queryCars()
                    val txLock: ForUpdateQuery<Car> = txTraversal.forUpdate()
                }
            }

            val privacy = CarLoadPrivacyRule { ctx, _ ->
                val query: UserReadQuery = ctx.client.cars.query().queryUser()
                query.firstOrNull(ctx.viewerContext)
                PrivacyDecision.Continue
            }
            val validation = CarCreateValidationRule { ctx, _ ->
                val query: CarReadQuery = ctx.client.users.indexes.email("a@b.c").query().queryCars()
                query.all(ctx.readViewerContext)
                ValidationDecision.Valid
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `no rule-client composition can return a full-client query`() {
        val expressions = mapOf(
            "Root" to "rules.users.query()",
            "Block" to "rules.users.query { loadCars() }",
            "Fluent" to "rules.users.query().where(User.active.eq(true)).orderBy(User.id.asc()).limit(2).offset(1)",
            "Configured" to "rules.users.query().configure { loadCars() }",
            "Traversed" to "rules.cars.query().queryUser()",
            "RoundTrip" to "rules.users.query().queryCars().queryUser()",
            "ManyToMany" to "rules.groups.query().queryMembers()",
            "UniqueIndex" to "rules.users.indexes.email(\"a@b.c\").query()",
            "PrefixIndex" to "rules.users.indexes.name(\"A\").query()",
            "CompositeIndex" to "rules.users.indexes.name(\"A\").email(\"a@b.c\").query()",
            "RangeIndex" to "rules.users.indexes.name(\"A\").email { gte(\"a\") }.query()",
            "RootRangeIndex" to "rules.users.indexes.createdAt { gte(Instant.EPOCH) }.query()",
            "IndexedTraversal" to "rules.users.indexes.email(\"a@b.c\").query().queryCars().queryUser()",
        )
        val probes = expressions.map { (name, expression) ->
            source(name, "fun misuse$name(rules: ReadOnlyEntClient): UserQuery = $expression")
        } + listOf(
            source("Privacy", """
                val privacy = UserLoadPrivacyRule { ctx, _ ->
                    val forbidden: UserQuery = ctx.client.users.query()
                    PrivacyDecision.Continue
                }
            """.trimIndent()),
            source("Validation", """
                val validation = UserCreateValidationRule { ctx, _ ->
                    val forbidden: UserQuery = ctx.client.users.query()
                    ValidationDecision.Valid
                }
            """.trimIndent()),
        )
        val result = compile(*probes.toTypedArray())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in expressions.keys + listOf("Privacy", "Validation")) {
            assertTrue(
                result.messages.lineSequence().any {
                    it.contains("$name.kt:") && it.contains("UserReadQuery") && it.contains("UserQuery")
                },
                "Expected a read/full query type mismatch in $name, got:\n${result.messages}",
            )
        }
    }

    @Test
    fun `locking cannot be recovered through rule clients or configured inside query scopes`() {
        val expressions = mapOf(
            "RuleRoot" to "rules.users.query().forUpdate()",
            "RuleFluent" to "rules.users.query().where(User.active.eq(true)).limit(2).forUpdate()",
            "RuleConfigured" to "rules.users.query().configure { loadCars() }.forUpdate()",
            "RuleTraversal" to "rules.cars.query().queryUser().forUpdate()",
            "RuleManyToMany" to "rules.groups.query().queryMembers().forUpdate()",
            "RuleIndex" to "rules.users.indexes.email(\"a@b.c\").query().forUpdate()",
            "RulePrefixIndex" to "rules.users.indexes.name(\"A\").query().forUpdate()",
            "RuleCompositeIndex" to "rules.users.indexes.name(\"A\").email(\"a@b.c\").query().forUpdate()",
            "RuleRangeIndex" to "rules.users.indexes.createdAt { gte(Instant.EPOCH) }.query().forUpdate()",
            "RuleIndexedTraversal" to "rules.users.indexes.email(\"a@b.c\").query().queryCars().forUpdate()",
            "RuleBypass" to "rules.users.query().forUpdate().all(ViewerContext.privacyBypass_DANGEROUS(\"test\"))",
            "SourceScope" to "client.users.query { forUpdate() }",
            "EagerScope" to "client.users.query { loadCars { forUpdate() } }",
            "ConfigureScope" to "client.users.query().configure { forUpdate() }",
            "TraversalScope" to "client.users.query().queryCars { forUpdate() }",
            "IndexScope" to "client.users.indexes.email(\"a@b.c\").query { forUpdate() }",
        )
        val probes = expressions.map { (name, expression) ->
            source(name, "fun misuse$name(client: EntClient, rules: ReadOnlyEntClient) { $expression }")
        } + listOf(
            source("PrivacyLock", """
                val privacy = UserLoadPrivacyRule { ctx, _ ->
                    ctx.client.users.query().forUpdate()
                    PrivacyDecision.Continue
                }
            """.trimIndent()),
            source("ValidationLock", """
                val validation = UserCreateValidationRule { ctx, _ ->
                    ctx.client.users.query().forUpdate()
                    ValidationDecision.Valid
                }
            """.trimIndent()),
        )
        val result = compile(*probes.toTypedArray())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in expressions.keys + listOf("PrivacyLock", "ValidationLock")) {
            assertTrue(result.messages.lineSequence().any {
                it.contains("$name.kt:") && it.contains("Unresolved reference") && it.contains("forUpdate")
            }, "Expected forUpdate to be unavailable in $name:\n${result.messages}")
        }
    }

    @Test
    fun `locking wrappers expose no configuration traversal or additional locking methods`() {
        val expressions = mapOf(
            "Where" to "where(User.active.eq(true))",
            "Order" to "orderBy(User.id.asc())",
            "Limit" to "limit(2)",
            "Offset" to "offset(1)",
            "Configure" to "configure { }",
            "Traverse" to "queryCars()",
            "Relock" to "forUpdate()",
        )
        val result = compile(*expressions.map { (name, expression) ->
            source(name, "fun misuse$name(query: ForUpdateQuery<User>) { query.$expression }")
        }.toTypedArray())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for ((name, expression) in expressions) {
            val method = expression.substringBefore('(').substringBefore(' ')
            assertTrue(result.messages.lineSequence().any {
                it.contains("$name.kt:") && it.contains("Unresolved reference") && it.contains(method)
            }, "Expected $method to be unavailable in $name:\n${result.messages}")
        }
    }

    @Test
    fun `interceptors cannot assign lock intent`() {
        val result = compile(source("LockMetadata", """
            fun change(context: QueryContext) {
                context.lockMode = QueryLockMode.ForUpdate
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.lineSequence().any {
            it.contains("LockMetadata.kt:") && it.contains("cannot be reassigned", ignoreCase = true)
        }, result.messages)
    }

    @Test
    fun `read query objects cannot be cast back to full queries and use the shared read pipeline`() {
        val result = compile(source("Exercise", """
            fun exercise() {
                var calls = 0
                val operations = mutableListOf<ReadOperation>()
                val driver = object : DatabaseDriver by NoopDriver {
                    override fun query(
                        table: String, predicates: List<Predicate<*>>, orderBy: List<OrderField<*>>,
                        limit: Int?, offset: Int?,
                        lockMode: entkt.runtime.query.QueryLockMode,
                    ): List<Map<String, Any?>> { calls++; return emptyList() }
                }
                val client = EntClient(driver) {
                    interceptors {
                        users(QueryInterceptor { _, context -> operations += context.operation }, name = "observe")
                    }
                }
                val rules = client.readOnlyClient
                val full = client.users.query { where(User.active.eq(true)) }
                val read = rules.users.query { where(User.active.eq(true)) }
                check(full.captureEntityQuery().predicates == read.captureEntityQuery().predicates)
                check(!UserQuery::class.java.isInstance(read))
                check(!UserQuery::class.java.isInstance(read.configure { limit(1) }))
                check(!CarQuery::class.java.isInstance(read.queryCars()))
                check(!UserIndexes::class.java.isInstance(rules.users.indexes))
                check(!UserQuery::class.java.isInstance(rules.users.indexes.email("a@b.c").query()))
                check(!UserQuery::class.java.isInstance(rules.groups.query().queryMembers()))
                check(calls == 0)

                val context = ViewerContext(Viewer.User(1L))
                check(full.all(context).getOrThrow().isEmpty())
                check(read.all(context).getOrThrow().isEmpty())
                check(read.firstOrNull(context).getOrThrow() == null)
                check(operations == listOf(ReadOperation.ALL, ReadOperation.ALL, ReadOperation.FIRST))
                check(calls == 3)
            }
        """.trimIndent(), internal = true))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        result.classLoader.loadClass("com.example.app.ExerciseKt").getMethod("exercise").invoke(null)
    }
}
