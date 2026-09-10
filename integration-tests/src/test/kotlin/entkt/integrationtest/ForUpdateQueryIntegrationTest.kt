package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Group
import entkt.integrationtest.ent.Membership
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreatePrivacyRule
import entkt.integrationtest.ent.UserDeletePrivacyRule
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.ent.UserQueryScope
import entkt.integrationtest.ent.UserUpdatePrivacyRule
import entkt.integrationtest.ent.UserUpdateValidationRule
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.ForUpdateQuery
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.QueryLockMode
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import entkt.runtime.result.RootOperationInsideTransactionException
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.visibleOrNull
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForUpdateQueryIntegrationTest : PostgresTestBase() {
    private val viewer = ViewerContext(Viewer.User(7L))

    @Test
    fun `locking values are inert immutable and retain their original transaction binding`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording)
        val first = user(client, "A")
        user(client, "B")
        recording.reset()

        val rootLock = client.users.query().forUpdate()
        assertTrue(recording.calls.isEmpty())
        assertIs<TransactionRequiredException>(assertIs<ReadResult.Failed>(rootLock.all(testViewerContext)).exception)
        assertTrue(recording.calls.isEmpty())

        var escaped: ForUpdateQuery<User>? = null
        client.withTransaction { tx ->
            assertIs<RootOperationInsideTransactionException>(
                assertIs<ReadResult.Failed>(rootLock.all(testViewerContext)).exception,
            )
            var scope: UserQueryScope? = null
            val ids = mutableListOf(first.id)
            val query = tx.users.query {
                scope = this
                where(User.id `in` ids)
            }
            val lock = query.forUpdate()
            escaped = lock
            ids.clear()
            assertNotNull(scope).limit(0)
            query.limit(0)
            assertEquals(listOf("withTransaction"), recording.calls)
            assertEquals(first.id, lock.firstOrNull(testViewerContext).getOrThrow()?.id)
            assertEquals(listOf(first.id), lock.all(testViewerContext).getOrThrow().map { it.id })
            assertEquals(listOf("withTransaction", "query:users", "query:users"), recording.calls)
        }.getOrThrow()

        recording.reset()
        assertIs<IllegalStateException>(
            assertIs<ReadResult.Failed>(assertNotNull(escaped).all(testViewerContext)).exception,
        )
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun `a concurrent writer stays blocked after the terminal returns until commit or rollback`() {
        for (rollback in listOf(false, true)) {
            val client = EntClient(resetAndDriver())
            val selected = user(client, "Before")
            val pool = Executors.newSingleThreadExecutor()
            val writerPid = CompletableFuture<Int>()
            var writer: Future<Int>? = null
            val stop = IllegalStateException("deliberate rollback")
            try {
                val transaction = client.withTransaction { tx ->
                    val locked = tx.users.query { where(User.id eq selected.id) }
                        .forUpdate().firstOrNull(testViewerContext).getOrThrow()
                    assertEquals(selected.id, locked?.id)
                    writer = pool.submit<Int> {
                        dataSource.connection.use { connection ->
                            connection.createStatement().use { statement ->
                                statement.execute("SET statement_timeout = '10s'")
                                statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                    rows.next()
                                    writerPid.complete(rows.getInt(1))
                                }
                            }
                            connection.prepareStatement("UPDATE users SET name = 'After' WHERE id = ?").use {
                                it.setLong(1, selected.id)
                                it.executeUpdate()
                            }
                        }
                    }
                    awaitBlocked(writerPid.get(5, TimeUnit.SECONDS), assertNotNull(writer))
                    assertFalse(assertNotNull(writer).isDone)
                    if (rollback) throw stop
                }
                if (rollback) {
                    val failed = assertIs<TransactionResult.Failed>(transaction)
                    assertSame(stop, failed.exception)
                    assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
                } else {
                    transaction.getOrThrow()
                }
                assertEquals(1, assertNotNull(writer).get(5, TimeUnit.SECONDS))
                assertEquals("After", client.users.findById(testViewerContext, selected.id).getOrThrow()?.name)
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `firstOrNull preserves native offset locking without preselecting ids or refilling the page`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording)
        val users = listOf("A", "B", "C").map { user(client, it) }
        recording.reset()
        client.withTransaction { tx ->
            val selected = tx.users.query { orderBy(User.id.asc()); offset(1); limit(2) }
                .forUpdate().firstOrNull(testViewerContext).getOrThrow()
            assertEquals(users[1].id, selected?.id)
            assertFalse(canLock(User.TABLE, users[0].id), "PostgreSQL locks the row skipped by OFFSET")
            assertFalse(canLock(User.TABLE, users[1].id))
            assertTrue(canLock(User.TABLE, users[2].id), "firstOrNull tightens the window to one row")
            assertEquals(listOf("withTransaction", "query:users"), recording.calls)
        }.getOrThrow()
        assertTrue(users.all { canLock(User.TABLE, it.id) })
    }

    @Test
    fun `absence and a zero limit take no row locks`() {
        val client = EntClient(resetAndDriver())
        val existing = user(client, "A")
        client.withTransaction { tx ->
            assertEquals(null, tx.users.query { where(User.id eq -1L) }
                .forUpdate().firstOrNull(testViewerContext).getOrThrow())
            assertEquals(emptyList(), tx.users.query { limit(0) }.forUpdate().all(testViewerContext).getOrThrow())
            assertTrue(canLock(User.TABLE, existing.id))
        }.getOrThrow()
    }

    @Test
    fun `interceptor predicates narrow rows before locks are taken and expose read-only lock intent`() {
        val driver = resetAndDriver()
        val setup = EntClient(driver)
        val selected = user(setup, "A")
        val excluded = user(setup, "B")
        val contexts = mutableListOf<QueryContext>()
        val client = EntClient(driver) {
            interceptors {
                users(QueryInterceptor { scope, context ->
                    contexts += context
                    scope.addPredicate(User.id eq selected.id)
                }, name = "selected-only")
            }
        }
        client.withTransaction { tx ->
            assertEquals(listOf(selected.id), tx.users.query().forUpdate().all(testViewerContext).getOrThrow().map { it.id })
            assertFalse(canLock(User.TABLE, selected.id))
            assertTrue(canLock(User.TABLE, excluded.id))
        }.getOrThrow()
        assertEquals(ReadOperation.ALL, contexts.single().operation)
        assertEquals(QueryLockMode.ForUpdate, contexts.single().lockMode)
        assertSame(testViewerContext, contexts.single().viewerContext)
    }

    @Test
    fun `traversal locks only the final roots while its source and eager targets stay unlocked`() {
        val driver = resetAndDriver()
        val setup = EntClient(driver)
        val author = user(setup, "A")
        val otherAuthor = user(setup, "B")
        val selected = article(setup, author)
        val excluded = article(setup, otherAuthor)
        val contexts = mutableListOf<QueryContext>()
        val client = EntClient(driver) {
            interceptors { global(GlobalQueryInterceptor { _, context -> contexts += context }, name = "trace") }
        }
        client.withTransaction { tx ->
            val loaded = tx.users.indexes.email(author.email).query().queryArticles { loadAuthor() }
                .forUpdate().all(testViewerContext).getOrThrow().single()
            assertEquals(selected.id, loaded.id)
            assertEquals(author.id, assertNotNull(loaded.edges.author.requireLoaded()).id)
            assertFalse(canLock(Article.TABLE, selected.id))
            assertTrue(canLock(Article.TABLE, excluded.id))
            assertTrue(canLock(User.TABLE, author.id), "neither source selection nor eager loading locks the author")
        }.getOrThrow()
        assertEquals(listOf(ReadOperation.EDGE_TRAVERSAL, ReadOperation.ALL, ReadOperation.EAGER_LOAD),
            contexts.map { it.operation })
        assertEquals(listOf(QueryLockMode.None, QueryLockMode.ForUpdate, QueryLockMode.None), contexts.map { it.lockMode })
    }

    @Test
    fun `many-to-many traversal and eager loading never lock junction or related rows`() {
        val driver = resetAndDriver()
        val setup = EntClient(driver)
        val member = user(setup, "A")
        val group = setup.groups.create { name = "Team" }.saveAndLoad(testViewerContext).getOrThrow()
        val membership = setup.memberships.create {
            userId = member.id
            groupId = group.id
            role = "member"
        }.saveAndLoad(testViewerContext).getOrThrow()
        val contexts = mutableListOf<QueryContext>()
        val client = EntClient(driver) {
            interceptors { global(GlobalQueryInterceptor { _, context -> contexts += context }, name = "trace") }
        }
        client.withTransaction { tx ->
            val users = tx.groups.query { where(Group.id eq group.id) }.queryUsers().forUpdate()
                .all(testViewerContext).getOrThrow()
            assertEquals(listOf(member.id), users.map { it.id })
            assertFalse(canLock(User.TABLE, member.id))
            assertTrue(canLock(Group.TABLE, group.id))
            assertTrue(canLock(Membership.TABLE, membership.id))
        }.getOrThrow()
        assertTrue(contexts.filter { it.operation != ReadOperation.ALL }.all { it.lockMode == QueryLockMode.None })
        contexts.clear()
        client.withTransaction { tx ->
            val loaded = tx.groups.query { where(Group.id eq group.id); loadUsers() }.forUpdate()
                .firstOrNull(testViewerContext).getOrThrow()
            assertEquals(listOf(member.id), assertNotNull(loaded).edges.users.requireLoaded().map { it.id })
            assertFalse(canLock(Group.TABLE, group.id))
            assertTrue(canLock(User.TABLE, member.id))
            assertTrue(canLock(Membership.TABLE, membership.id))
        }.getOrThrow()
        assertEquals(listOf(ReadOperation.FIRST, ReadOperation.EAGER_JUNCTION, ReadOperation.EAGER_LOAD),
            contexts.map { it.operation })
        assertEquals(listOf(QueryLockMode.ForUpdate, QueryLockMode.None, QueryLockMode.None), contexts.map { it.lockMode })
    }

    @Test
    fun `LOAD denial retains locks without invoking mutations and rollback remains explicit`() {
        val recording = RecordingDriver(resetAndDriver())
        val selected = user(EntClient(recording), "A")
        var loadCalls = 0
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    load(UserLoadPrivacyRule { _, _ -> loadCalls++; PrivacyDecision.Deny("hidden") })
                    create(UserCreatePrivacyRule { _, _ -> error("CREATE must not run") })
                    update(UserUpdatePrivacyRule { _, _ -> error("UPDATE must not run") })
                    delete(UserDeletePrivacyRule { _, _ -> error("DELETE must not run") })
                }
                validation { update(UserUpdateValidationRule { _, _ -> error("validation must not run") }) }
            }
        }
        val client = EntClient(recording) {
            policies { users(policy) }
            hooks {
                users {
                    beforeSave { error("beforeSave must not run") }
                    beforeCreate { error("beforeCreate must not run") }
                    afterCreate { error("afterCreate must not run") }
                    beforeUpdate { error("beforeUpdate must not run") }
                    afterUpdate { error("afterUpdate must not run") }
                    beforeDelete { error("beforeDelete must not run") }
                    afterDelete { error("afterDelete must not run") }
                }
            }
        }
        recording.reset()
        client.withTransaction { tx ->
            val result = tx.users.query { where(User.id eq selected.id) }.forUpdate().firstOrNull(viewer)
            assertIs<EntPrivacyDeniedException>(assertIs<ReadResult.Failed>(result).exception)
            assertFalse(canLock(User.TABLE, selected.id))
            val calls = recording.calls.toList()
            assertEquals(null, result.visibleOrNull().getOrThrow())
            assertEquals(calls, recording.calls)
            assertEquals(listOf("withTransaction", "query:users"), calls)
        }.getOrThrow()
        assertEquals(1, loadCalls)
        assertTrue(canLock(User.TABLE, selected.id))

        val rolledBack = client.withTransaction { tx ->
            tx.users.query { where(User.id eq selected.id) }.forUpdate().firstOrNull(viewer).orRollback()
        }
        val failure = assertIs<TransactionResult.Failed>(rolledBack)
        assertIs<EntPrivacyDeniedException>(failure.exception)
        assertEquals(TransactionFailureState.NotCommitted, failure.transactionState)
        assertTrue(canLock(User.TABLE, selected.id))
    }

    @Test
    fun `a required selected-edge denial remains a failure without locking the denied edge`() {
        val driver = resetAndDriver()
        val setup = EntClient(driver)
        val author = user(setup, "A")
        val root = article(setup, author)
        val client = EntClient(driver) {
            policies {
                articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                    override fun configure(scope: ArticlePolicyScope) = scope.run {
                        privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
                    }
                })
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run {
                        privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("hidden author") }) }
                    }
                })
            }
        }
        client.withTransaction { tx ->
            val result = tx.articles.query { where(Article.id eq root.id); loadAuthor() }.forUpdate().firstOrNull(viewer)
            val denial = assertIs<EntPrivacyDeniedException>(assertIs<ReadResult.Failed>(result).exception)
            assertIs<LoadDenialOrigin.SelectedEdgePath>(denial.origin)
            assertSame(result, result.visibleOrNull())
            assertFalse(canLock(Article.TABLE, root.id))
            assertTrue(canLock(User.TABLE, author.id))
        }.getOrThrow()
    }

    @Test
    fun `unsupported locking fails through the generated terminal before interceptors or SQL`() {
        val recording = RecordingDriver(withoutQueryLocking(resetAndDriver()))
        var interceptorCalls = 0
        val client = EntClient(recording) {
            interceptors { users(QueryInterceptor { _, _ -> interceptorCalls++ }, name = "trace") }
        }
        client.withTransaction { tx ->
            val result = tx.users.query().forUpdate().all(testViewerContext)
            assertIs<UnsupportedDriverCapabilityException>(assertIs<ReadResult.Failed>(result).exception)
        }.getOrThrow()
        assertEquals(listOf("withTransaction"), recording.calls)
        assertEquals(0, interceptorCalls)
    }

    private fun user(client: EntClient, name: String): User = client.users.create {
        this.name = name
        email = "$name@example.com"
    }.saveAndLoad(testViewerContext).getOrThrow()

    private fun article(client: EntClient, author: User): Article = client.articles.create {
        title = "Article by ${author.name}"
        authorId = author.id
    }.saveAndLoad(testViewerContext).getOrThrow()

    /** Probe a competing transaction without waiting or leaving any locks behind. */
    private fun canLock(table: String, id: Long): Boolean = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT id FROM \"$table\" WHERE id = ? FOR UPDATE NOWAIT").use {
                it.setLong(1, id)
                it.executeQuery().use { rows -> check(rows.next()) { "Missing lock probe row $table/$id" } }
            }
            true
        } catch (failure: SQLException) {
            if (failure.sqlState != "55P03") throw failure
            false
        } finally {
            connection.rollback()
        }
    }

    /** Observe server-reported blocking instead of assuming a thread has reached its UPDATE. */
    private fun awaitBlocked(pid: Int, writer: Future<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT cardinality(pg_blocking_pids(?)) > 0").use { statement ->
                statement.setInt(1, pid)
                while (System.nanoTime() < deadline) {
                    statement.executeQuery().use { rows ->
                        rows.next()
                        if (rows.getBoolean(1)) return
                    }
                    check(!writer.isDone) { "Writer finished before the locking transaction ended" }
                    Thread.sleep(10)
                }
            }
        }
        error("Writer was not observed waiting on the row lock")
    }

    private fun withoutQueryLocking(driver: DatabaseDriver): DatabaseDriver = object : DatabaseDriver by driver {
        override val supportsQueryForUpdate = false
        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
            driver.withTransaction { tx -> block(withoutQueryLocking(tx)) }
    }
}
