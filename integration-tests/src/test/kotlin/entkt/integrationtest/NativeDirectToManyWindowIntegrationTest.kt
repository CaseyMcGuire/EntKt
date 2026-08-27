// The capability-forcing and row-observing wrappers override the
// @EntktInternal queryDirectToMany member; the opt-in is intentional
// test wiring.
@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.RelatedRows
import entkt.runtime.query.EagerWindowStrategy
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireLoaded
import entkt.integrationtest.support.PostgresTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2A of the set-based eager graph loader
 * (`docs/possible-features/query/set-based-eager-graph-loader.md`):
 * PostgreSQL-native per-parent windows for direct to-many eager
 * edges.
 *
 * Pins the RFC's phase-2A test requirements at the executor level:
 * native and phase-1-emulated windows select exactly the same rows;
 * the native path fetches nothing outside a finite window while
 * emulation demonstrably overfetches; interceptors see the complete
 * logical relationship constraint while the driver receives it
 * separately from the remaining frozen predicates; association maps
 * survive empty groups and nested loads. The SQL-level
 * lowering contract (typed-array transport past the scalar bind
 * limit, rank-alias collision safety, Long window arithmetic) is
 * pinned by the driver-level `DirectToManyPostgresDriverTest`.
 */
class NativeDirectToManyWindowIntegrationTest : PostgresTestBase() {

    /**
     * Forces phase-1 emulation over the real PostgreSQL driver. The
     * native read throws rather than forwarding, so a parity test
     * that compares "native vs emulated" provably exercised two
     * different code paths — if the runtime ever ignored the
     * capability and called the native operation anyway, the
     * emulated side would fail loudly instead of silently comparing
     * native against native.
     */
    private class EmulatedWindowsDriver(private val real: DatabaseDriver) : DatabaseDriver by real {
        override fun directToManyWindowCapability() = DirectToManyWindowCapability.EMULATED

        override fun queryDirectToMany(query: DirectToManyQuery): RelatedRows =
            throw AssertionError(
                "queryDirectToMany reached a driver reporting EMULATED — the runtime must " +
                    "fall back to DatabaseDriver.query",
            )

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
            real.withTransaction { tx -> block(EmulatedWindowsDriver(tx)) }
    }

    /** Counts rows crossing each read path, native and emulated. */
    private class RowObservingDriver(
        private val real: DatabaseDriver,
        val nativeQueries: MutableList<DirectToManyQuery> = mutableListOf(),
        val nativeRowCounts: MutableList<Int> = mutableListOf(),
        val emulatedRowCounts: MutableList<Int> = mutableListOf(),
        private val emulated: Boolean = false,
    ) : DatabaseDriver by real {

        override fun directToManyWindowCapability() =
            if (emulated) DirectToManyWindowCapability.EMULATED
            else real.directToManyWindowCapability()

        override fun queryDirectToMany(query: DirectToManyQuery): RelatedRows {
            nativeQueries.add(query)
            return real.queryDirectToMany(query).also { nativeRowCounts.add(it.rows.size) }
        }

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> =
            real.query(table, predicates, orderBy, limit, offset).also {
                if (table == Article.TABLE) emulatedRowCounts.add(it.size)
            }

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
            real.withTransaction { tx ->
                block(RowObservingDriver(tx, nativeQueries, nativeRowCounts, emulatedRowCounts, emulated))
            }
    }

    private fun bypassClient(driver: DatabaseDriver): EntClient =
        EntClient(driver)

    /**
     * Seed [perAuthor] articles for each of three users plus one user
     * with no articles at all. Titles repeat across authors ("t1"..)
     * so caller ordering produces ties the primary key must break.
     */
    private fun seed(client: EntClient, perAuthor: Int): List<User> {
        val users = ('a'..'d').map { name ->
            client.users.create { this.name = "$name"; email = "$name@example.com" }
                .saveAndLoad(testViewerContext).getOrThrow()
        }
        for (user in users.take(3)) {
            for (i in 1..perAuthor) {
                client.articles.create { title = "t$i"; authorId = user.id }.save(testViewerContext).getOrThrow()
            }
        }
        return users
    }

    private fun loadedTitlesByUser(users: List<User>): Map<Long, List<Pair<String, Long>>> =
        users.associate { user ->
            user.id to user.edges.articles.requireLoaded().map { it.title to it.id }
        }

    @Test
    fun `native windows select exactly the rows phase-1 emulation selects`() {
        val real = resetAndDriver()
        val nativeClient = bypassClient(real)
        val emulatedClient = bypassClient(EmulatedWindowsDriver(real))
        seed(nativeClient, perAuthor = 5)

        val block: entkt.integrationtest.ent.UserQuery.() -> Unit = {
            loadArticles {
                orderBy(Article.title.desc())
                offset(1)
                limit(2)
            }
        }
        val native = nativeClient.users.query(block).all(testViewerContext).getOrThrow()
        val emulated = emulatedClient.users.query(block).all(testViewerContext).getOrThrow()

        // Same parents, same per-parent rows, same order — including
        // the empty fourth group and the tie-breaking primary-key
        // term on the repeated titles.
        assertEquals(loadedTitlesByUser(emulated), loadedTitlesByUser(native))
        val perUser = loadedTitlesByUser(native)
        assertEquals(4, perUser.size)
        assertTrue(perUser.values.count { it.isEmpty() } == 1, "the article-less user stays empty")
        for (rows in perUser.values.filter { it.isNotEmpty() }) {
            assertEquals(listOf("t4", "t3"), rows.map { it.first })
        }
    }

    @Test
    fun `an offset without a limit is also identical across strategies`() {
        val real = resetAndDriver()
        val nativeClient = bypassClient(real)
        val emulatedClient = bypassClient(EmulatedWindowsDriver(real))
        seed(nativeClient, perAuthor = 4)

        val block: entkt.integrationtest.ent.UserQuery.() -> Unit = {
            loadArticles { offset(2) }
        }
        val native = nativeClient.users.query(block).all(testViewerContext).getOrThrow()
        val emulated = emulatedClient.users.query(block).all(testViewerContext).getOrThrow()

        assertEquals(loadedTitlesByUser(emulated), loadedTitlesByUser(native))
        for (rows in loadedTitlesByUser(native).values.filter { it.isNotEmpty() }) {
            assertEquals(listOf("t3", "t4"), rows.map { it.first })
        }
    }

    @Test
    fun `the native path fetches only in-window rows while emulation overfetches`() {
        val real = resetAndDriver()
        val nativeObserver = RowObservingDriver(real)
        val emulatedObserver = RowObservingDriver(real, emulated = true)
        val nativeClient = bypassClient(nativeObserver)
        val emulatedClient = bypassClient(emulatedObserver)
        seed(nativeClient, perAuthor = 10)
        nativeObserver.nativeRowCounts.clear()
        emulatedObserver.emulatedRowCounts.clear()

        val block: entkt.integrationtest.ent.UserQuery.() -> Unit = {
            loadArticles { limit(2) }
        }
        nativeClient.users.query(block).all(testViewerContext).getOrThrow()
        emulatedClient.users.query(block).all(testViewerContext).getOrThrow()

        // 3 populated parents × limit 2: the native statement returns
        // 6 rows; the emulated fetch transfers all 30 and discards 24
        // in memory. That asymmetry IS the phase-2A overfetch fix.
        assertEquals(listOf(6), nativeObserver.nativeRowCounts)
        assertEquals(listOf(30), emulatedObserver.emulatedRowCounts)
    }

    @Test
    fun `interceptors see the complete relationship constraint while the driver receives it separately`() {
        val real = resetAndDriver()
        val observer = RowObservingDriver(real)
        val structuralValues = mutableListOf<List<Any?>>()
        val client = EntClient(observer) {

            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            val leaf = scope.shape.predicates
                                .filterIsInstance<Predicate.Leaf<*>>()
                                .single { it.field == "author_id" && it.op == Op.IN }
                            structuralValues.add((leaf.value as List<*>).toList())
                            // Contribute a predicate so the driver-side
                            // remainder is observably non-structural.
                            scope.addPredicate(Article.title.hasPrefix("t"))
                        }
                    },
                    name = "structural-shape-observer",
                )
            }
        }
        val users = seed(client, perAuthor = 3)
        observer.nativeQueries.clear()

        client.users.query { loadArticles { limit(1) } }.all(testViewerContext).getOrThrow()

        // One EAGER_LOAD pass saw the full logical constraint —
        // author_id IN (every current parent id).
        assertEquals(1, structuralValues.size)
        assertEquals(users.map { it.id }, structuralValues.single())

        // The driver received the SAME parent keys through the
        // relationship plan, and the remaining target predicates no
        // longer contain the structural IN — the driver lowers the
        // relationship constraint itself through the typed array.
        val plan = observer.nativeQueries.single()
        assertEquals(users.map { it.id }, plan.sourceKeys)
        assertEquals("author_id", plan.targetForeignKey)
        assertTrue(
            plan.targetPredicates.filterIsInstance<Predicate.Leaf<*>>()
                .none { it.field == "author_id" && it.op == Op.IN },
            "the structural relationship IN must not ride targetPredicates",
        )
        // The interceptor's contribution DOES ride the remainder.
        assertTrue(
            plan.targetPredicates.filterIsInstance<Predicate.Leaf<*>>()
                .any { it.field == "title" },
            "interceptor predicates travel as ordinary target predicates",
        )
        assertEquals(1, plan.window.limit)
        assertEquals(0, plan.window.offset)
    }

    @Test
    fun `nested eager loads work identically on top of a native window`() {
        val real = resetAndDriver()
        val nativeClient = bypassClient(real)
        val emulatedClient = bypassClient(EmulatedWindowsDriver(real))
        seed(nativeClient, perAuthor = 4)

        val block: entkt.integrationtest.ent.UserQuery.() -> Unit = {
            loadArticles {
                limit(2)
                loadAuthor()
            }
        }
        val native = nativeClient.users.query(block).all(testViewerContext).getOrThrow()
        val emulated = emulatedClient.users.query(block).all(testViewerContext).getOrThrow()

        for ((nativeUser, emulatedUser) in native.zip(emulated)) {
            val nativeArticles = nativeUser.edges.articles.requireLoaded()
            val emulatedArticles = emulatedUser.edges.articles.requireLoaded()
            assertEquals(emulatedArticles.map { it.id }, nativeArticles.map { it.id })
            for (article in nativeArticles) {
                assertEquals(nativeUser.id, article.edges.author.requireLoaded()?.id)
            }
        }
    }

    @Test
    fun `a limit-zero window and an empty parent set stay data-gated on the native path`() {
        val real = resetAndDriver()
        val observer = RowObservingDriver(real)
        val client = bypassClient(observer)
        seed(client, perAuthor = 2)
        observer.nativeQueries.clear()

        val users = client.users.query { loadArticles { limit(0) } }.all(testViewerContext).getOrThrow()
        assertTrue(observer.nativeQueries.isEmpty(), "limit(0) must not reach the driver")
        assertTrue(users.all { it.edges.articles.requireLoaded().isEmpty() })

        val none = client.users.query { where(User.name.eq("nobody")); loadArticles() }
            .all(testViewerContext).getOrThrow()
        assertTrue(none.isEmpty())
        assertTrue(observer.nativeQueries.isEmpty(), "an empty parent set must not reach the driver")
    }
}
