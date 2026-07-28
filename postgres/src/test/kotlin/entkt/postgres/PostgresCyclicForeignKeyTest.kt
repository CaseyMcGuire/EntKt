package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import entkt.schema.OnDelete
import org.postgresql.util.PSQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Mutually referencing tables: a user has an optional pinned post, a
// post has a required author. No creation order satisfies both if the
// FK is inline in CREATE TABLE.
private val CYC_USERS = EntitySchema(
    table = "cyc_users",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata(
            "pinned_post_id",
            FieldType.LONG,
            nullable = true,
            references = ForeignKeyRef("cyc_posts", "id", OnDelete.SET_NULL),
        ),
    ),
    edges = emptyMap(),
)

private val CYC_POSTS = EntitySchema(
    table = "cyc_posts",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata(
            "author_id",
            FieldType.LONG,
            nullable = false,
            references = ForeignKeyRef("cyc_users", "id", OnDelete.CASCADE),
        ),
    ),
    edges = emptyMap(),
)

/**
 * Auto-DDL over a foreign key cycle.
 *
 * Codegen's topological sort has to break FK cycles somewhere, so one of
 * these tables is always created before the table it points at. With FKs
 * inline in `CREATE TABLE`, that first statement failed with
 * `42P01 undefined_table` and auto-DDL was unusable for the schema.
 *
 * `registerAll` creates every table before adding any constraint, so
 * ordering within the batch stops mattering — tested in both directions.
 */
class PostgresCyclicForeignKeyTest {

    private val dataSource: DataSource = SharedPostgres.dataSource

    private fun dropAll() {
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("DROP TABLE IF EXISTS cyc_posts, cyc_users CASCADE")
            }
        }
    }

    /**
     * Constraint names actually present on [table], per the catalog.
     * Empty when the table doesn't exist — `to_regclass` returns null
     * rather than raising, so "never created" reads as "no constraints"
     * instead of blowing up the assertion.
     */
    private fun foreignKeyNames(table: String): List<String> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT conname FROM pg_constraint WHERE conrelid = to_regclass(?) AND contype = 'f' ORDER BY conname",
            ).use { stmt ->
                stmt.setString(1, table)
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @Test
    fun `registers a cycle when the dependent table comes first`() {
        dropAll()
        val driver = PostgresDriver(dataSource, autoDdl = true)

        // cyc_posts.author_id points at a table created later in the batch.
        driver.registerAll(listOf(CYC_POSTS, CYC_USERS))

        assertEquals(listOf("fk_cyc_posts_author_id"), foreignKeyNames("cyc_posts"))
        assertEquals(listOf("fk_cyc_users_pinned_post_id"), foreignKeyNames("cyc_users"))
    }

    @Test
    fun `registers a cycle when the other table comes first`() {
        dropAll()
        val driver = PostgresDriver(dataSource, autoDdl = true)

        driver.registerAll(listOf(CYC_USERS, CYC_POSTS))

        assertEquals(listOf("fk_cyc_posts_author_id"), foreignKeyNames("cyc_posts"))
        assertEquals(listOf("fk_cyc_users_pinned_post_id"), foreignKeyNames("cyc_users"))
    }

    @Test
    fun `the constraints are real and enforced`() {
        dropAll()
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.registerAll(listOf(CYC_POSTS, CYC_USERS))

        // Splitting the FK out of CREATE TABLE must not weaken it.
        val ex = assertFailsWith<PSQLException> {
            driver.insert("cyc_posts", mapOf("author_id" to 999_999L))
        }
        assertEquals("23503", ex.sqlState)

        // And a satisfied reference still inserts.
        val user = driver.insert("cyc_users", mapOf("pinned_post_id" to null))
        val post = driver.insert("cyc_posts", mapOf("author_id" to user["id"]))
        assertTrue(post["id"] != null)
    }

    @Test
    fun `re-registering is idempotent and does not duplicate constraints`() {
        dropAll()
        // Registration is replayed constantly — every generated client
        // construction, every test-suite driver. ADD CONSTRAINT has no
        // IF NOT EXISTS, so a second pass must not fail or double up.
        PostgresDriver(dataSource, autoDdl = true).registerAll(listOf(CYC_POSTS, CYC_USERS))
        PostgresDriver(dataSource, autoDdl = true).registerAll(listOf(CYC_POSTS, CYC_USERS))

        assertEquals(listOf("fk_cyc_posts_author_id"), foreignKeyNames("cyc_posts"))
        assertEquals(listOf("fk_cyc_users_pinned_post_id"), foreignKeyNames("cyc_users"))
    }

    @Test
    fun `re-registering the same batch touches the database not at all`() {
        dropAll()
        // Load-bearing, not an optimization: withTransaction builds a
        // generated client INSIDE the open transaction and
        // withPrivacyContext builds one per call, so this path runs
        // constantly on a fully-registered driver. Opening a connection
        // here would put DDL inside every user transaction.
        val counting = CountingDataSource(dataSource)
        val driver = PostgresDriver(counting, autoDdl = true)
        driver.registerAll(listOf(CYC_POSTS, CYC_USERS))

        val afterFirst = counting.connections
        assertTrue(afterFirst > 0, "the first batch must actually create the tables")

        repeat(5) { driver.registerAll(listOf(CYC_POSTS, CYC_USERS)) }

        assertEquals(
            afterFirst,
            counting.connections,
            "re-registering an unchanged set must not open a connection",
        )
    }

    @Test
    fun `a partial batch fails loudly and names the missing target`() {
        dropAll()
        // Registering a subset can't resolve cyc_posts.author_id. That
        // has to fail at startup rather than silently leaving the table
        // without its constraint — the error is the only chance to
        // notice before orphaned rows accumulate.
        val driver = PostgresDriver(dataSource, autoDdl = true)

        val ex = assertFailsWith<IllegalStateException> {
            driver.registerAll(listOf(CYC_POSTS))
        }
        assertTrue(ex.message!!.contains("fk_cyc_posts_author_id"), "names the constraint; got: ${ex.message}")
        assertTrue(ex.message!!.contains("cyc_users"), "names the missing target; got: ${ex.message}")
        assertTrue(ex.message!!.contains("registerAll"), "points at the fix; got: ${ex.message}")
    }

    @Test
    fun `a single register still works when its target already exists`() {
        dropAll()
        // Incremental registration is still valid as long as each FK
        // target is already present — only cycles need the batch.
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(CYC_USERS.copy(columns = listOf(CYC_USERS.columns.first())))
        driver.register(CYC_POSTS)

        assertEquals(listOf("fk_cyc_posts_author_id"), foreignKeyNames("cyc_posts"))
    }

    @Test
    fun `concurrent batches on one driver do not race on DDL`() {
        dropAll()
        // The DDL runs before the cache write, so without serialization
        // every thread classifies the same tables as missing and they all
        // execute CREATE TABLE / ADD CONSTRAINT at once. Concurrent
        // `CREATE TABLE IF NOT EXISTS` for the same name can fail in
        // Postgres with a duplicate key on pg_type_typname_nsp_index.
        val driver = PostgresDriver(dataSource, autoDdl = true)
        val threads = 8
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        val workers = (1..threads).map {
            kotlin.concurrent.thread {
                start.await()
                runCatching { driver.registerAll(listOf(CYC_POSTS, CYC_USERS)) }
                    .onFailure { failures += it }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(emptyList(), failures.toList(), "concurrent registration must not fail")
        assertEquals(listOf("fk_cyc_posts_author_id"), foreignKeyNames("cyc_posts"))
        assertEquals(listOf("fk_cyc_users_pinned_post_id"), foreignKeyNames("cyc_users"))
    }

    @Test
    fun `a losing conflicting batch does not mutate the database first`() {
        dropAll()
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.registerAll(listOf(CYC_POSTS, CYC_USERS))

        // A batch that conflicts on one table must be rejected before it
        // creates the others — validation and caching happen under the
        // same lock as the DDL, and nothing is written until the whole
        // batch is accepted.
        val conflicting = CYC_USERS.copy(
            columns = CYC_USERS.columns + ColumnMetadata("extra", FieldType.STRING, nullable = true),
        )
        val newcomer = CYC_POSTS.copy(table = "cyc_orphans")

        assertFailsWith<IllegalStateException> {
            driver.registerAll(listOf(conflicting, newcomer))
        }
        assertEquals(
            emptyList(),
            foreignKeyNames("cyc_orphans"),
            "the rejected batch must not have created the unrelated table's constraints",
        )
    }

    /** Counts borrowed connections so a test can assert "no I/O". */
    private class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
        var connections = 0
            private set

        override fun getConnection(): java.sql.Connection {
            connections++
            return delegate.connection
        }
    }
}
