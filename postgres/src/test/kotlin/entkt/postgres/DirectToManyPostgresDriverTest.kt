// DatabaseDriver-level coverage for the phase-2A native direct to-many
// window lowering. Constructs the relationship plan directly (it is
// `@EntktInternal` framework wiring); the opt-in is intentional and
// scoped to driver-internal coverage.
@file:OptIn(EntktInternal::class)

package entkt.postgres

import entkt.query.EntktInternal
import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.PerParentWindow
import entkt.runtime.query.EagerWindowStrategy
import entkt.schema.FieldType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val D2M_AUTHOR_SCHEMA = EntitySchema(
    table = "d2m_authors",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("name", FieldType.STRING, nullable = false),
    ),
    edges = emptyMap(),
)

private val D2M_POST_SCHEMA = EntitySchema(
    table = "d2m_posts",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("title", FieldType.STRING, nullable = false),
        ColumnMetadata("published", FieldType.BOOL, nullable = false),
        ColumnMetadata("author_id", FieldType.LONG, nullable = true),
    ),
    edges = emptyMap(),
)

/**
 * A table whose registered columns include the preferred ranking
 * alias, so the allocator must probe past it. DSL storage names can
 * never start with an underscore, but hand-built schemas can.
 */
private val D2M_COLLIDER_SCHEMA = EntitySchema(
    table = "d2m_colliders",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("__entkt_rank", FieldType.INT, nullable = false),
        ColumnMetadata("owner_id", FieldType.LONG, nullable = true),
    ),
    edges = emptyMap(),
)

/**
 * One table per non-bigint typed-array element type: the FK column's
 * [FieldType] selects the `createArrayOf` element type name, so each
 * mapping needs a round trip of its own. The FK values reference no
 * parent table — `= ANY(array)` only matches column values.
 */
private val D2M_KEYED_SCHEMAS = listOf(
    EntitySchema(
        table = "d2m_int_keyed",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("owner_int", FieldType.INT, nullable = true),
        ),
        edges = emptyMap(),
    ),
    EntitySchema(
        table = "d2m_text_keyed",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("owner_key", FieldType.STRING, nullable = true),
        ),
        edges = emptyMap(),
    ),
    EntitySchema(
        table = "d2m_uuid_keyed",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("owner_uuid", FieldType.UUID, nullable = true),
        ),
        edges = emptyMap(),
    ),
)

class DirectToManyPostgresDriverTest {

    private val driver: PostgresDriver by lazy {
        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS d2m_posts CASCADE")
                stmt.execute("DROP TABLE IF EXISTS d2m_authors CASCADE")
                stmt.execute("DROP TABLE IF EXISTS d2m_colliders CASCADE")
                for (schema in D2M_KEYED_SCHEMAS) {
                    stmt.execute("DROP TABLE IF EXISTS ${quote(schema.table)} CASCADE")
                }
            }
        }
        PostgresDriver(SharedPostgres.dataSource, autoDdl = true).also {
            it.registerAll(
                listOf(D2M_AUTHOR_SCHEMA, D2M_POST_SCHEMA, D2M_COLLIDER_SCHEMA) + D2M_KEYED_SCHEMAS,
            )
        }
    }

    private val effectiveOrder = listOf(
        OrderField<Any>("title", OrderDirection.DESC),
        OrderField<Any>("id", OrderDirection.ASC),
    )

    @BeforeTest
    fun truncate() {
        driver.deleteMany("d2m_posts", emptyList())
        driver.deleteMany("d2m_authors", emptyList())
        driver.deleteMany("d2m_colliders", emptyList())
        for (schema in D2M_KEYED_SCHEMAS) {
            driver.deleteMany(schema.table, emptyList())
        }
    }

    /** Seed [perAuthor] posts per author id, titled `p1..pN`, returning author ids. */
    private fun seed(authors: Int, perAuthor: Int): List<Long> {
        val ids = (1..authors).map { i ->
            driver.insert("d2m_authors", mapOf("name" to "author-$i"))["id"] as Long
        }
        for (author in ids) {
            for (p in 1..perAuthor) {
                driver.insert(
                    "d2m_posts",
                    mapOf("title" to "p$p", "published" to true, "author_id" to author),
                )
            }
        }
        return ids
    }

    private fun query(
        sourceKeys: List<Any>,
        window: PerParentWindow,
        targetPredicates: List<Predicate<*>> = emptyList(),
        order: List<OrderField<*>> = effectiveOrder,
    ) = DirectToManyQuery(
        targetTable = "d2m_posts",
        sourceKeys = sourceKeys,
        targetForeignKey = "author_id",
        targetPredicates = targetPredicates,
        effectiveOrder = order,
        window = window,
    )

    // ---------- statement-shape pins (no database needed) ----------
    //
    // Row-count assertions alone cannot distinguish a storage-applied
    // window from a driver that overfetches and windows internally
    // while claiming STORAGE_NATIVE. These pin the rendered statement
    // itself, [PostgresDriverTest]-style: the window and the
    // typed-array transport must live in the SQL text.

    private val renderOps = PostgresOperations(
        registry = mapOf(
            D2M_AUTHOR_SCHEMA.table to D2M_AUTHOR_SCHEMA,
            D2M_POST_SCHEMA.table to D2M_POST_SCHEMA,
        ),
        codec = PostgresValueCodec(entkt.runtime.driver.KotlinxJsonCodec()),
    )

    @Test
    fun `a finite window renders storage-side ranking with the typed-array transport`() {
        val prepared = renderOps.buildDirectToManySql(
            query(listOf(1L, 2L), PerParentWindow(offset = 1, limit = 2)),
        )

        // Both select lists enumerate registered columns — `t0.*`
        // could drag an unregistered physical column into the derived
        // table and make the rank reference ambiguous.
        assertEquals(
            "SELECT t1.\"id\", t1.\"title\", t1.\"published\", t1.\"author_id\" FROM (" +
                "SELECT t0.\"id\", t0.\"title\", t0.\"published\", t0.\"author_id\", " +
                "ROW_NUMBER() OVER (PARTITION BY t0.\"author_id\" " +
                "ORDER BY t0.\"title\" DESC, t0.\"id\" ASC) AS \"__entkt_rank\" " +
                "FROM \"d2m_posts\" AS t0 WHERE t0.\"author_id\" = ANY(?)" +
                ") AS t1 WHERE t1.\"__entkt_rank\" > ? AND t1.\"__entkt_rank\" <= ? " +
                "ORDER BY t1.\"title\" DESC, t1.\"id\" ASC",
            prepared.sql,
        )
        // Params in SQL-text order: the array, then the Long bounds
        // (offset, offset + limit — computed in Long).
        assertEquals(3, prepared.params.size)
        val array = prepared.params[0].value
        assertTrue(array is PgTypedArray && array.typeName == "bigint")
        assertEquals(listOf<Any?>(1L, 3L), prepared.params.drop(1).map { it.value })
    }

    @Test
    fun `a window-less statement keeps the typed array and skips ranking`() {
        val prepared = renderOps.buildDirectToManySql(
            query(listOf(1L), PerParentWindow(offset = 0, limit = null)),
        )

        assertEquals(
            "SELECT t0.* FROM \"d2m_posts\" AS t0 WHERE t0.\"author_id\" = ANY(?) " +
                "ORDER BY t0.\"title\" DESC, t0.\"id\" ASC",
            prepared.sql,
        )
        assertEquals(1, prepared.params.size)
    }

    @Test
    fun `frozen target predicates render inside the ranked input, before ROW_NUMBER`() {
        val prepared = renderOps.buildDirectToManySql(
            query(
                listOf(1L),
                PerParentWindow(offset = 0, limit = 2),
                targetPredicates = listOf(Predicate.Leaf<Any>("published", Op.EQ, true)),
            ),
        )

        // The predicate must sit in the derived table's WHERE (with
        // the relationship constraint), not outside the ranking —
        // filtering after ranking could return fewer than `limit`
        // rows while later matches exist.
        assertTrue(
            "= ANY(?) AND (t0.\"published\" = ?))" in prepared.sql,
            prepared.sql,
        )
        // Param order follows SQL text: array, predicate, bounds.
        assertEquals(
            listOf("PgTypedArray", "true", "0", "2"),
            prepared.params.map { it.value }.map { if (it is PgTypedArray) "PgTypedArray" else it.toString() },
        )
    }

    @Test
    fun `the postgres driver reports the native capability on both facades`() {
        assertEquals(DirectToManyWindowCapability.NATIVE, driver.directToManyWindowCapability())
        driver.withTransaction { tx ->
            assertEquals(DirectToManyWindowCapability.NATIVE, tx.directToManyWindowCapability())
        }
    }

    @Test
    fun `a finite limit returns at most limit rows per parent in the global effective order`() {
        val authors = seed(authors = 3, perAuthor = 5)

        val result = driver.queryDirectToMany(
            query(authors, PerParentWindow(offset = 0, limit = 2)),
        )

        assertEquals(EagerWindowStrategy.STORAGE_NATIVE, result.strategy)
        // Storage applied the window: nothing outside it was fetched,
        // so the result carries exactly limit × parents rows.
        assertEquals(6, result.rows.size)
        val byParent = result.rows.groupBy { it.sourceKey }
        assertEquals(authors.toSet(), byParent.keys.map { it as Long }.toSet())
        for ((parent, rows) in byParent) {
            // title DESC ⇒ p5, p4 lead each parent's window.
            assertEquals(listOf("p5", "p4"), rows.map { it.targetRow["title"] }, "parent=$parent")
        }
        // Global order is the effective order across ALL rows, not
        // per-parent grouping order: every p5 (id ASC) then every p4.
        assertEquals(
            List(3) { "p5" } + List(3) { "p4" },
            result.rows.map { it.targetRow["title"] },
        )
    }

    @Test
    fun `an offset without a limit skips each parent's prefix natively`() {
        val authors = seed(authors = 2, perAuthor = 4)

        val result = driver.queryDirectToMany(
            query(authors, PerParentWindow(offset = 3, limit = null)),
        )

        // title DESC: p4, p3, p2 skipped per parent; p1 remains.
        assertEquals(2, result.rows.size)
        assertEquals(listOf("p1", "p1"), result.rows.map { it.targetRow["title"] })
        assertEquals(authors.toSet(), result.rows.map { it.sourceKey as Long }.toSet())
    }

    @Test
    fun `offset and limit combine into one storage window`() {
        val authors = seed(authors = 2, perAuthor = 5)

        val result = driver.queryDirectToMany(
            query(authors, PerParentWindow(offset = 1, limit = 2)),
        )

        // Per parent: skip p5, take p4 and p3.
        val byParent = result.rows.groupBy { it.sourceKey }
        for ((parent, rows) in byParent) {
            assertEquals(listOf("p4", "p3"), rows.map { it.targetRow["title"] }, "parent=$parent")
        }
        assertEquals(4, result.rows.size)
    }

    @Test
    fun `a window-less read keeps the typed-array transport and the effective order`() {
        val authors = seed(authors = 2, perAuthor = 3)

        val result = driver.queryDirectToMany(
            query(authors, PerParentWindow(offset = 0, limit = null)),
        )

        assertEquals(6, result.rows.size)
        assertEquals(
            List(2) { "p3" } + List(2) { "p2" } + List(2) { "p1" },
            result.rows.map { it.targetRow["title"] },
        )
        // The source key is the decoded FK value.
        for (row in result.rows) {
            assertEquals(row.targetRow["author_id"], row.sourceKey)
        }
    }

    @Test
    fun `target predicates apply before ranking so a window fills from later matches`() {
        val authors = seed(authors = 1, perAuthor = 4)
        // Unpublish the two title-DESC leaders; the window must fill
        // from the remaining matches rather than returning fewer rows.
        driver.updateMany(
            "d2m_posts",
            mapOf("published" to false),
            listOf(Predicate.Leaf<Any>("title", Op.IN, listOf("p4", "p3"))),
        )

        val result = driver.queryDirectToMany(
            query(
                authors,
                PerParentWindow(offset = 0, limit = 2),
                targetPredicates = listOf(Predicate.Leaf<Any>("published", Op.EQ, true)),
            ),
        )

        assertEquals(listOf("p2", "p1"), result.rows.map { it.targetRow["title"] })
    }

    @Test
    fun `parent sets beyond the scalar bind limit execute through one typed array`() {
        val authors = seed(authors = 2, perAuthor = 3)
        // 70,000 parent keys — far past the 65,535 scalar bind limit.
        // Only the two seeded authors match; the rest are absent keys.
        val hugeParentSet = (1L..70_000L).toList()
        assertTrue(authors.all { it in hugeParentSet })

        val result = driver.queryDirectToMany(
            query(hugeParentSet, PerParentWindow(offset = 0, limit = 1)),
        )

        assertEquals(2, result.rows.size)
        assertEquals(authors.toSet(), result.rows.map { it.sourceKey as Long }.toSet())
        assertEquals(listOf("p3", "p3"), result.rows.map { it.targetRow["title"] })
    }

    @Test
    fun `fixed non-relationship binds over the budget reject before any IO`() {
        val authors = seed(authors = 1, perAuthor = 1)
        val oversizedIn = Predicate.Leaf<Any>("title", Op.IN, (1..66_000).map { "t$it" })

        assertFailsWith<PostgresBindLimitException> {
            driver.queryDirectToMany(
                query(
                    authors,
                    PerParentWindow(offset = 0, limit = 1),
                    targetPredicates = listOf(oversizedIn),
                ),
            )
        }
    }

    @Test
    fun `extreme window bounds use long arithmetic and cannot overflow`() {
        val authors = seed(authors = 1, perAuthor = 3)

        // THE discriminating case: offset=1 + limit=Int.MAX_VALUE.
        // Int arithmetic wraps 1 + Int.MAX_VALUE to Int.MIN_VALUE,
        // turning the window into `rank > 1 AND rank <= -2147483648`
        // — zero rows. Long arithmetic returns everything after the
        // skipped prefix: rows 2 and 3.
        val pastPrefix = driver.queryDirectToMany(
            query(authors, PerParentWindow(offset = 1, limit = Int.MAX_VALUE)),
        )
        assertEquals(listOf("p2", "p1"), pastPrefix.rows.map { it.targetRow["title"] })

        // offset + limit = 2 * Int.MAX_VALUE — both bounds past every
        // rank; must select nothing, not wrap.
        val result = driver.queryDirectToMany(
            query(authors, PerParentWindow(offset = Int.MAX_VALUE, limit = Int.MAX_VALUE)),
        )
        assertTrue(result.rows.isEmpty())

        // A maximal limit with no offset admits everything.
        val unbounded = driver.queryDirectToMany(
            query(authors, PerParentWindow(offset = 0, limit = Int.MAX_VALUE)),
        )
        assertEquals(3, unbounded.rows.size)
    }

    @Test
    fun `duplicate source keys cannot duplicate rows or shift a window`() {
        // DirectToManyQuery documents set-semantics matching: a
        // duplicated parent key must not duplicate that parent's rows
        // (a bag-semantics transport — e.g. a naive unnest join —
        // would) and must not consume its window twice.
        val authors = seed(authors = 2, perAuthor = 3)
        val duplicated = listOf(authors[0], authors[1], authors[0], authors[0])

        val windowed = driver.queryDirectToMany(
            query(duplicated, PerParentWindow(offset = 0, limit = 2)),
        )
        assertEquals(4, windowed.rows.size)
        for ((parent, rows) in windowed.rows.groupBy { it.sourceKey }) {
            assertEquals(listOf("p3", "p2"), rows.map { it.targetRow["title"] }, "parent=$parent")
        }

        val unwindowed = driver.queryDirectToMany(
            query(duplicated, PerParentWindow(offset = 0, limit = null)),
        )
        assertEquals(6, unwindowed.rows.size)
    }

    @Test
    fun `empty parent sets and limit-zero windows perform no read`() {
        seed(authors = 1, perAuthor = 2)

        val emptyParents = driver.queryDirectToMany(
            query(emptyList(), PerParentWindow(offset = 0, limit = 5)),
        )
        assertTrue(emptyParents.rows.isEmpty())
        assertEquals(EagerWindowStrategy.STORAGE_NATIVE, emptyParents.strategy)

        val zeroWindow = driver.queryDirectToMany(
            query(listOf(1L), PerParentWindow(offset = 0, limit = 0)),
        )
        assertTrue(zeroWindow.rows.isEmpty())
    }

    @Test
    fun `integer text and uuid parent keys each transport through their typed array`() {
        val order = listOf(OrderField<Any>("id", OrderDirection.ASC))

        driver.insert("d2m_int_keyed", mapOf("owner_int" to 7))
        driver.insert("d2m_int_keyed", mapOf("owner_int" to 8))
        val ints = driver.queryDirectToMany(
            DirectToManyQuery(
                targetTable = "d2m_int_keyed",
                sourceKeys = listOf(7, 9),
                targetForeignKey = "owner_int",
                targetPredicates = emptyList(),
                effectiveOrder = order,
                window = PerParentWindow(offset = 0, limit = 1),
            ),
        )
        assertEquals(listOf<Any?>(7), ints.rows.map { it.sourceKey })

        driver.insert("d2m_text_keyed", mapOf("owner_key" to "alpha"))
        driver.insert("d2m_text_keyed", mapOf("owner_key" to "beta"))
        val texts = driver.queryDirectToMany(
            DirectToManyQuery(
                targetTable = "d2m_text_keyed",
                sourceKeys = listOf("alpha", "gamma"),
                targetForeignKey = "owner_key",
                targetPredicates = emptyList(),
                effectiveOrder = order,
                window = PerParentWindow(offset = 0, limit = 1),
            ),
        )
        assertEquals(listOf<Any?>("alpha"), texts.rows.map { it.sourceKey })

        val matched = java.util.UUID.randomUUID()
        driver.insert("d2m_uuid_keyed", mapOf("owner_uuid" to matched))
        driver.insert("d2m_uuid_keyed", mapOf("owner_uuid" to java.util.UUID.randomUUID()))
        val uuids = driver.queryDirectToMany(
            DirectToManyQuery(
                targetTable = "d2m_uuid_keyed",
                sourceKeys = listOf(matched, java.util.UUID.randomUUID()),
                targetForeignKey = "owner_uuid",
                targetPredicates = emptyList(),
                effectiveOrder = order,
                window = PerParentWindow(offset = 0, limit = 1),
            ),
        )
        assertEquals(listOf<Any?>(matched), uuids.rows.map { it.sourceKey })
    }

    @Test
    fun `the ranking alias dodges a registered column with the preferred name`() {
        driver.insert("d2m_colliders", mapOf("__entkt_rank" to 42, "owner_id" to 7L))
        driver.insert("d2m_colliders", mapOf("__entkt_rank" to 43, "owner_id" to 7L))

        val result = driver.queryDirectToMany(
            DirectToManyQuery(
                targetTable = "d2m_colliders",
                sourceKeys = listOf(7L),
                targetForeignKey = "owner_id",
                targetPredicates = emptyList(),
                effectiveOrder = listOf(OrderField<Any>("id", OrderDirection.ASC)),
                window = PerParentWindow(offset = 0, limit = 1),
            ),
        )

        // The registered `__entkt_rank` column decodes to its stored
        // value — the synthetic ranking column got a different alias
        // and never reached the row map.
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows.single().targetRow["__entkt_rank"])
    }

    @Test
    fun `the transactional facade sees the transaction's own uncommitted writes`() {
        // Proves the native read really runs on the PINNED
        // connection: a facade that borrowed a pooled autocommit
        // connection instead would return committed state only, and
        // every other test in this file would still pass.
        val authors = seed(authors = 1, perAuthor = 1)

        val outcome = driver.withTransaction { tx ->
            tx.insert(
                "d2m_posts",
                mapOf("title" to "p2", "published" to true, "author_id" to authors.single()),
            )
            tx.queryDirectToMany(query(authors, PerParentWindow(offset = 0, limit = 5)))
        }

        val result = when (outcome) {
            is entkt.runtime.driver.DriverTransactionResult.Success -> outcome.value
            is entkt.runtime.driver.DriverTransactionResult.Failed -> throw outcome.exception
        }
        // title DESC: the uncommitted p2 must lead its parent's window.
        assertEquals(listOf("p2", "p1"), result.rows.map { it.targetRow["title"] })
    }

    @Test
    fun `the transactional facade runs the native read on the pinned connection`() {
        val authors = seed(authors = 1, perAuthor = 3)

        val outcome = driver.withTransaction { tx ->
            tx.queryDirectToMany(query(authors, PerParentWindow(offset = 0, limit = 2)))
        }

        val result = when (outcome) {
            is entkt.runtime.driver.DriverTransactionResult.Success -> outcome.value
            is entkt.runtime.driver.DriverTransactionResult.Failed -> throw outcome.exception
        }
        assertEquals(listOf("p3", "p2"), result.rows.map { it.targetRow["title"] })
    }
}
