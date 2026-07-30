// Driver-level test fixtures construct edge predicates
// (`Predicate.HasEdgeWith` / `HasEdge` / `HasM2MEdgeFrom`) directly
// to exercise the SQL renderer in isolation. These types carry
// `@EntktInternal` so application code can't fabricate them outside
// the generated `EdgeRef.has(...)` surface. Driver tests opt in once at the file header — the
// fabrication is intentional and scoped to driver-internal coverage.
@file:OptIn(EntktInternal::class)

package entkt.postgres

import entkt.query.EntktInternal
import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.TraversalSourceShape
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EdgeMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.IndexMetadata
import entkt.runtime.mutation.RelationshipLockKey
import entkt.schema.FieldType
import entkt.schema.OnDelete
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val USER_SCHEMA = EntitySchema(
    table = "users",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("name", FieldType.STRING, nullable = false),
        ColumnMetadata("age", FieldType.INT, nullable = true),
        ColumnMetadata("active", FieldType.BOOL, nullable = true),
    ),
    edges = mapOf(
        "posts" to EdgeMetadata(
            targetTable = "posts",
            sourceColumn = "id",
            targetColumn = "author_id",
        ),
    ),
)

private val POST_SCHEMA = EntitySchema(
    table = "posts",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("title", FieldType.STRING, nullable = false),
        ColumnMetadata("published", FieldType.BOOL, nullable = false),
        ColumnMetadata("author_id", FieldType.LONG, nullable = true),
    ),
    edges = mapOf(
        "author" to EdgeMetadata(
            targetTable = "users",
            sourceColumn = "author_id",
            targetColumn = "id",
        ),
    ),
)

// ---------- M2M test schemas ----------

private val M2M_USER_SCHEMA = EntitySchema(
    table = "m2m_users",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("name", FieldType.STRING, nullable = false),
        ColumnMetadata("age", FieldType.INT, nullable = true),
    ),
    edges = mapOf(
        "groups" to EdgeMetadata(
            targetTable = "m2m_groups",
            sourceColumn = "id",
            targetColumn = "id",
            junctionTable = "m2m_user_groups",
            junctionSourceColumn = "user_id",
            junctionTargetColumn = "group_id",
        ),
    ),
)

private val M2M_GROUP_SCHEMA = EntitySchema(
    table = "m2m_groups",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("name", FieldType.STRING, nullable = false),
    ),
    edges = mapOf(
        "users" to EdgeMetadata(
            targetTable = "m2m_users",
            sourceColumn = "id",
            targetColumn = "id",
            junctionTable = "m2m_user_groups",
            junctionSourceColumn = "group_id",
            junctionTargetColumn = "user_id",
        ),
    ),
)

private val M2M_USER_GROUP_SCHEMA = EntitySchema(
    table = "m2m_user_groups",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("user_id", FieldType.LONG, nullable = false),
        ColumnMetadata("group_id", FieldType.LONG, nullable = false),
    ),
    // The unique pair index is what `insertIgnore(... ON CONFLICT (user_id,
    // group_id) DO NOTHING)` targets — a real link table always carries it.
    indexes = listOf(
        IndexMetadata(columns = listOf("user_id", "group_id"), unique = true, name = "idx_m2m_user_group_pair"),
    ),
    edges = emptyMap(),
)

private fun quoteIdent(identifier: String): String =
    "\"${identifier.replace("\"", "\"\"")}\""

/**
 * Driver-contract tests for [PostgresDriver] against a real Postgres
 * container.
 *
 * Each test starts from a TRUNCATE — schemas live for the lifetime of
 * the container, but rows and id sequences reset between tests so the
 * assertions can pin specific ids without coupling to test order.
 */
@Testcontainers
class PostgresDriverTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:16-alpine")
    }

    private val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private fun fresh(): PostgresDriver {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(USER_SCHEMA)
        driver.register(POST_SCHEMA)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"posts\", \"users\" RESTART IDENTITY")
            }
        }
        return driver
    }

    @Test
    fun `insert assigns auto-long ids and returns the persisted row`() {
        val driver = fresh()
        val row = driver.insert(
            "users",
            mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true),
        )

        assertEquals(1L, row["id"], "First insert should get id=1")
        assertEquals("Alice", row["name"])

        val second = driver.insert(
            "users",
            mapOf<String, Any?>("name" to "Bob", "age" to 25, "active" to true),
        )
        assertEquals(2L, second["id"], "Second insert should get id=2")
    }

    @Test
    fun `byId returns null for missing rows and the persisted row otherwise`() {
        val driver = fresh()
        val inserted = driver.insert(
            "users",
            mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true),
        )

        assertEquals(inserted, driver.byId("users", inserted["id"]!!))
        assertNull(driver.byId("users", 9999L))
    }

    @Test
    fun `update merges values and never rewrites the id`() {
        val driver = fresh()
        val inserted = driver.insert(
            "users",
            mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true),
        )

        val updated = driver.update(
            "users",
            inserted["id"]!!,
            mapOf<String, Any?>("id" to 9999L, "age" to 31),
        )

        assertNotNull(updated)
        assertEquals(inserted["id"], updated["id"], "id must be preserved through update")
        assertEquals(31, updated["age"])
        assertEquals("Alice", updated["name"], "untouched columns survive")
    }

    @Test
    fun `update returns null when the row is gone`() {
        val driver = fresh()
        assertNull(
            driver.update("users", 42L, mapOf<String, Any?>("name" to "Ghost")),
        )
    }

    @Test
    fun `update hydrates the row even when all values equal current state`() {
        val driver = fresh()
        val inserted = driver.insert(
            "users",
            mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true),
        )

        // State-equal write: the values map matches the existing row.
        // PostgresDriver uses UPDATE ... RETURNING * so the row hydrates
        // regardless of whether Postgres reports any rows changed.
        val hydrated = driver.update(
            "users",
            inserted["id"]!!,
            mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true),
        )

        assertNotNull(hydrated, "state-equal update must hydrate the row, not return null")
        assertEquals("Alice", hydrated["name"])
        assertEquals(30, hydrated["age"])
        assertEquals(true, hydrated["active"])
    }

    @Test
    fun `query filters with leaf predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "age" to 17, "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Carol", "age" to 65, "active" to false))

        val adults = driver.query(
            table = "users",
            predicates = listOf(Predicate.Leaf<Any>("age", Op.GTE, 18)),
            orderBy = emptyList(),
            limit = null,
            offset = null,
        )
        assertEquals(setOf("Alice", "Carol"), adults.map { it["name"] }.toSet())
    }

    @Test
    fun `query honors orderBy, limit, and offset`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Carol", "age" to 65, "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "age" to 25, "active" to true))

        val sortedByAgeDesc = driver.query(
            "users",
            predicates = emptyList(),
            orderBy = listOf(OrderField<Any>("age", OrderDirection.DESC)),
            limit = 2,
            offset = 1,
        )
        assertEquals(listOf("Alice", "Bob"), sortedByAgeDesc.map { it["name"] })
    }

    @Test
    fun `query handles compound and or predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "age" to 70, "active" to false))
        driver.insert("users", mapOf<String, Any?>("name" to "Carol", "age" to 17, "active" to true))

        // active AND (age >= 65 OR name == "Alice")
        val pred = Predicate.And(
            Predicate.Leaf<Any>("active", Op.EQ, true),
            Predicate.Or(
                Predicate.Leaf<Any>("age", Op.GTE, 65),
                Predicate.Leaf<Any>("name", Op.EQ, "Alice"),
            ),
        )
        val rows = driver.query("users", listOf(pred), emptyList(), null, null)
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles HasEdgeWith forward direction`() {
        val driver = fresh()
        val alice = driver.insert("users", mapOf<String, Any?>("name" to "Alice"))
        val bob = driver.insert("users", mapOf<String, Any?>("name" to "Bob"))

        driver.insert(
            "posts",
            mapOf<String, Any?>("title" to "hi", "published" to true, "author_id" to alice["id"]),
        )
        driver.insert(
            "posts",
            mapOf<String, Any?>("title" to "draft", "published" to false, "author_id" to bob["id"]),
        )

        val pred = Predicate.HasEdgeWith<Any, Any>(
            edge = "posts",
            inner = Predicate.Leaf<Any>("published", Op.EQ, true),
        )
        val rows = driver.query("users", listOf(pred), emptyList(), null, null)
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles HasEdge from the from-side`() {
        val driver = fresh()
        val alice = driver.insert("users", mapOf<String, Any?>("name" to "Alice"))
        driver.insert(
            "posts",
            mapOf<String, Any?>("title" to "hi", "published" to true, "author_id" to alice["id"]),
        )

        val rows = driver.query(
            "posts",
            listOf(Predicate.HasEdge<Any>("author")),
            emptyList(),
            null,
            null,
        )
        assertEquals(1, rows.size)
    }

    @Test
    fun `query handles string predicates contains, hasPrefix, hasSuffix`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "alice@example.com"))
        driver.insert("users", mapOf<String, Any?>("name" to "bob@admin.example.com"))
        driver.insert("users", mapOf<String, Any?>("name" to "carol@other.org"))

        val containsExample = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.CONTAINS, "example")),
            emptyList(), null, null,
        )
        assertEquals(
            setOf("alice@example.com", "bob@admin.example.com"),
            containsExample.map { it["name"] }.toSet(),
        )

        val prefixAlice = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.HAS_PREFIX, "alice")),
            emptyList(), null, null,
        )
        assertEquals(setOf("alice@example.com"), prefixAlice.map { it["name"] }.toSet())

        val suffixOrg = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.HAS_SUFFIX, ".org")),
            emptyList(), null, null,
        )
        assertEquals(setOf("carol@other.org"), suffixOrg.map { it["name"] }.toSet())
    }

    @Test
    fun `contains hasPrefix hasSuffix treat percent and underscore as literal characters`() {
        // Wildcard-injection regression guard: caller input flows raw
        // into the LIKE pattern would mean a search for "%" matches
        // everything and "_" matches every single-char column. After
        // escaping `%`, `_`, and `\`, the predicate behaves as
        // literal-substring matching.
        val driver = fresh()
        // Rows with literal special characters.
        driver.insert("users", mapOf<String, Any?>("name" to "50% off"))
        driver.insert("users", mapOf<String, Any?>("name" to "alice"))
        driver.insert("users", mapOf<String, Any?>("name" to "a_b"))
        driver.insert("users", mapOf<String, Any?>("name" to "ab"))
        driver.insert("users", mapOf<String, Any?>("name" to "back\\slash"))

        // contains "%" matches only the row with a literal percent.
        val containsPct = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.CONTAINS, "%")),
            emptyList(), null, null,
        )
        assertEquals(setOf("50% off"), containsPct.map { it["name"] }.toSet())

        // contains "_" matches only the row with a literal underscore,
        // not every two-character name (which an unescaped `_` wildcard
        // would catch).
        val containsUnderscore = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.CONTAINS, "_")),
            emptyList(), null, null,
        )
        assertEquals(setOf("a_b"), containsUnderscore.map { it["name"] }.toSet())

        // The escape char itself: contains "\\" (one literal backslash)
        // matches only the row containing it.
        val containsBackslash = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.CONTAINS, "\\")),
            emptyList(), null, null,
        )
        assertEquals(setOf("back\\slash"), containsBackslash.map { it["name"] }.toSet())

        // hasPrefix with a wildcard-looking value: only literal matches.
        val prefixPct = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.HAS_PREFIX, "50%")),
            emptyList(), null, null,
        )
        assertEquals(setOf("50% off"), prefixPct.map { it["name"] }.toSet())

        // hasSuffix with a wildcard-looking value: only literal matches.
        val suffixPct = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.HAS_SUFFIX, "% off")),
            emptyList(), null, null,
        )
        assertEquals(setOf("50% off"), suffixPct.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles IN and NOT_IN`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "age" to 30))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "age" to 40))
        driver.insert("users", mapOf<String, Any?>("name" to "Carol", "age" to 50))

        val inAges = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("age", Op.IN, listOf(30, 50))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice", "Carol"), inAges.map { it["name"] }.toSet())

        val notInAges = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("age", Op.NOT_IN, listOf(30, 50))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Bob"), notInAges.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles IS_NULL and IS_NOT_NULL`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "age" to 30))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "age" to null))

        val noAge = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("age", Op.IS_NULL, null)),
            emptyList(), null, null,
        )
        assertEquals(setOf("Bob"), noAge.map { it["name"] }.toSet())

        val withAge = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("age", Op.IS_NOT_NULL, null)),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice"), withAge.map { it["name"] }.toSet())
    }

    @Test
    fun `embedded quotes in identifiers are escaped defensively`() {
        val table = "audit\"logs"
        val nameColumn = "display\"name"
        val schema = EntitySchema(
            table = table,
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(nameColumn, FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )

        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(schema)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE ${quoteIdent(table)} RESTART IDENTITY")
            }
        }

        val inserted = driver.insert(table, mapOf(nameColumn to "Alice"))
        assertEquals("Alice", inserted[nameColumn])

        val rows = driver.query(
            table = table,
            predicates = listOf(Predicate.Leaf<Any>(nameColumn, Op.EQ, "Alice")),
            orderBy = listOf(OrderField<Any>(nameColumn, OrderDirection.ASC)),
            limit = null,
            offset = null,
        )
        assertEquals(1, rows.size)
        assertEquals("Alice", rows.single()[nameColumn])
    }

    @Test
    fun `delete removes the row and returns true`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf<String, Any?>("name" to "Alice"))

        assertTrue(driver.delete("users", row["id"]!!))
        assertNull(driver.byId("users", row["id"]!!))
        assertEquals(false, driver.delete("users", row["id"]!!), "second delete is a no-op")
    }

    @Test
    fun `unregistered table is rejected loudly`() {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        assertFailsWith<IllegalStateException> {
            driver.insert("nope", emptyMap())
        }
    }

    // ---------- Transactions ----------

    @Test
    fun `withTransaction commits on success`() {
        val driver = fresh()
        driver.withTransaction { tx ->
            tx.insert("users", mapOf<String, Any?>("name" to "Alice"))
            tx.insert("users", mapOf<String, Any?>("name" to "Bob"))
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(setOf("Alice", "Bob"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `withTransaction rolls back on exception`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Pre-existing"))

        assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.insert("users", mapOf<String, Any?>("name" to "Alice"))
                tx.insert("users", mapOf<String, Any?>("name" to "Bob"))
                error("boom")
            }
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(listOf("Pre-existing"), rows.map { it["name"] })
    }

    @Test
    fun `withTransaction supports queries inside the transaction`() {
        val driver = fresh()
        driver.withTransaction { tx ->
            tx.insert("users", mapOf<String, Any?>("name" to "Alice"))
            // Should see the uncommitted insert within the same transaction.
            val rows = tx.query("users", emptyList(), emptyList(), null, null)
            assertEquals(1, rows.size)
            assertEquals("Alice", rows.single()["name"])
        }
    }

    @Test
    fun `nested withTransaction reuses the same transaction`() {
        val driver = fresh()
        driver.withTransaction { outer ->
            outer.insert("users", mapOf<String, Any?>("name" to "Alice"))
            outer.withTransaction { inner ->
                inner.insert("users", mapOf<String, Any?>("name" to "Bob"))
            }
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(setOf("Alice", "Bob"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `the transaction driver cannot be used after the block returns`() {
        val driver = fresh()
        var leaked: entkt.runtime.driver.Driver? = null
        driver.withTransaction { tx -> leaked = tx }
        // The block-scoped driver's connection is returned to the pool when
        // withTransaction exits; a captured reference must fail loudly, not
        // run I/O on a recycled connection.
        val ex = assertFailsWith<IllegalStateException> {
            leaked!!.byId("users", 1L)
        }
        assertTrue("after transaction block" in ex.message!!, ex.message ?: "")
    }

    // ---------- transaction locking capability surface ----------

    @Test
    fun `inTransaction is false on the root driver and true inside withTransaction`() {
        val driver = fresh()
        assertEquals(false, driver.inTransaction)
        driver.withTransaction { tx ->
            assertEquals(true, tx.inTransaction)
        }
    }

    @Test
    fun `Postgres driver advertises both row-lock capabilities`() {
        val driver = fresh()
        assertEquals(true, driver.supportsReadRowForUpdate)
        assertEquals(true, driver.supportsOwnerEdgeSerialization)
        driver.withTransaction { tx ->
            assertEquals(true, tx.supportsReadRowForUpdate)
            assertEquals(true, tx.supportsOwnerEdgeSerialization)
        }
    }

    @Test
    fun `readRowForUpdate returns the current row state inside a transaction`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf<String, Any?>("name" to "Alice"))

        driver.withTransaction { tx ->
            val locked = tx.readRowForUpdate("users", row["id"]!!)
            assertEquals("Alice", locked!!["name"])
            assertEquals(row["id"], locked["id"])
        }
    }

    @Test
    fun `readRowForUpdate returns null for a missing id`() {
        val driver = fresh()
        driver.withTransaction { tx ->
            assertNull(tx.readRowForUpdate("users", 9999L))
        }
    }

    @Test
    fun `readRowForUpdate on the root driver throws because the lock would release immediately`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf<String, Any?>("name" to "Alice"))
        // Calling readRowForUpdate outside a transaction would release the lock as soon
        // as the auto-commit statement returned, defeating the contract.
        assertFailsWith<IllegalStateException> {
            driver.readRowForUpdate("users", row["id"]!!)
        }
    }

    @Test
    fun `serializeOwnerEdgeAndRead returns the current row state inside a transaction`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf<String, Any?>("name" to "Bob"))

        driver.withTransaction { tx ->
            val serialized = tx.serializeOwnerEdgeAndRead("users", row["id"]!!)
            assertEquals("Bob", serialized!!["name"])
        }
    }

    @Test
    fun `serializeOwnerEdgeAndRead returns null for a missing id`() {
        val driver = fresh()
        driver.withTransaction { tx ->
            assertNull(tx.serializeOwnerEdgeAndRead("users", 9999L))
        }
    }

    @Test
    fun `serializeOwnerEdgeAndRead on the root driver throws because the advisory lock is xact-scoped`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf<String, Any?>("name" to "Bob"))
        // Calling serializeOwnerEdgeAndRead outside a transaction would release the
        // pg_advisory_xact_lock immediately at statement end, defeating the contract.
        assertFailsWith<IllegalStateException> {
            driver.serializeOwnerEdgeAndRead("users", row["id"]!!)
        }
    }

    @Test
    fun `lock capability flags advertise driver-family support, not 'this instance can call right now'`() {
        // Subtle contract: `supportsReadRowForUpdate == true` on the
        // root driver means "the Postgres driver family supports the
        // capability when transaction-scoped," not "calling
        // readRowForUpdate on this instance right now will succeed."
        // Generated code preflights `inTransaction` *before* invoking
        // the lock method, so it never hits the root throw — but the
        // asymmetry still needs to be visible in tests so a future
        // change that conflates the flag with "callable right now"
        // breaks loudly.
        val driver = fresh()
        val row = driver.insert("users", mapOf<String, Any?>("name" to "Carol"))

        // Flag says yes...
        assertEquals(true, driver.supportsReadRowForUpdate)
        assertEquals(true, driver.supportsOwnerEdgeSerialization)

        // ...but calling on the root throws (auto-commit would release
        // the lock immediately).
        assertFailsWith<IllegalStateException> {
            driver.readRowForUpdate("users", row["id"]!!)
        }
        assertFailsWith<IllegalStateException> {
            driver.serializeOwnerEdgeAndRead("users", row["id"]!!)
        }

        // The same flag still says yes on the transaction-scoped sub-driver,
        // and there the call actually executes.
        driver.withTransaction { tx ->
            assertEquals(true, tx.supportsReadRowForUpdate)
            assertEquals(true, tx.supportsOwnerEdgeSerialization)
            assertEquals("Carol", tx.readRowForUpdate("users", row["id"]!!)!!["name"])
            assertEquals("Carol", tx.serializeOwnerEdgeAndRead("users", row["id"]!!)!!["name"])
        }
    }

    @Test
    fun `transaction driver throws after block returns including register`() {
        val driver = fresh()
        var captured: entkt.runtime.driver.Driver? = null
        driver.withTransaction { tx ->
            captured = tx
        }
        assertFailsWith<IllegalStateException> {
            captured!!.insert("users", mapOf<String, Any?>("name" to "Late"))
        }
        assertFailsWith<IllegalStateException> {
            captured!!.register(USER_SCHEMA)
        }
    }

    // ---------- Unique / Index DDL ----------

    @Test
    fun `unique column constraint rejects duplicate values`() {
        val schema = EntitySchema(
            table = "emails",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("addr", FieldType.STRING, nullable = false, unique = true),
            ),
            edges = emptyMap(),
        )
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(schema)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"emails\" RESTART IDENTITY")
            }
        }

        driver.insert("emails", mapOf("addr" to "a@b.com"))
        // Second insert with the same addr should violate the UNIQUE constraint.
        assertFailsWith<Exception> {
            driver.insert("emails", mapOf("addr" to "a@b.com"))
        }
    }

    @Test
    fun `composite unique index rejects duplicate combinations`() {
        val schema = EntitySchema(
            table = "memberships",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("user_id", FieldType.LONG, nullable = false),
                ColumnMetadata("group_id", FieldType.LONG, nullable = false),
            ),
            edges = emptyMap(),
            indexes = listOf(
                IndexMetadata(columns = listOf("user_id", "group_id"), unique = true, name = "idx_user_group"),
            ),
        )
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(schema)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"memberships\" RESTART IDENTITY")
            }
        }

        driver.insert("memberships", mapOf("user_id" to 1L, "group_id" to 10L))
        // Same pair should be rejected.
        assertFailsWith<Exception> {
            driver.insert("memberships", mapOf("user_id" to 1L, "group_id" to 10L))
        }
        // Different pair should be fine.
        driver.insert("memberships", mapOf("user_id" to 1L, "group_id" to 20L))
    }

    @Test
    fun `non-unique composite index is created without error`() {
        val schema = EntitySchema(
            table = "events",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("category", FieldType.STRING, nullable = false),
                ColumnMetadata("priority", FieldType.INT, nullable = false),
            ),
            edges = emptyMap(),
            indexes = listOf(
                IndexMetadata(columns = listOf("category", "priority"), name = "idx_category_priority"),
            ),
        )
        val driver = PostgresDriver(dataSource, autoDdl = true)
        // Should not throw — idempotent index creation.
        driver.register(schema)
        driver.register(schema)
    }

    // ---------- Foreign key constraints ----------

    @Test
    fun `nullable FK emits REFERENCES with ON DELETE SET NULL`() {
        val parentSchema = EntitySchema(
            table = "fk_parents",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )
        val childSchema = EntitySchema(
            table = "fk_children",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = true,
                    references = ForeignKeyRef(table = "fk_parents", column = "id"),
                ),
            ),
            edges = emptyMap(),
        )
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(parentSchema)
        driver.register(childSchema)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"fk_children\", \"fk_parents\" RESTART IDENTITY")
            }
        }

        val parent = driver.insert("fk_parents", mapOf("name" to "Alice"))
        driver.insert("fk_children", mapOf("parent_id" to parent["id"]))

        // Inserting a child referencing a non-existent parent should fail.
        assertFailsWith<Exception> {
            driver.insert("fk_children", mapOf("parent_id" to 9999L))
        }

        // Deleting the parent should SET NULL on the child.
        driver.delete("fk_parents", parent["id"]!!)
        val child = driver.query("fk_children", emptyList(), emptyList(), null, null).single()
        assertNull(child["parent_id"])
    }

    @Test
    fun `required FK emits REFERENCES with ON DELETE RESTRICT`() {
        val parentSchema = EntitySchema(
            table = "fk_req_parents",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )
        val childSchema = EntitySchema(
            table = "fk_req_children",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = false,
                    references = ForeignKeyRef(table = "fk_req_parents", column = "id"),
                ),
            ),
            edges = emptyMap(),
        )
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(parentSchema)
        driver.register(childSchema)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"fk_req_children\", \"fk_req_parents\" RESTART IDENTITY")
            }
        }

        val parent = driver.insert("fk_req_parents", mapOf("name" to "Alice"))
        driver.insert("fk_req_children", mapOf("parent_id" to parent["id"]))

        // Deleting the parent should fail because the child still references it.
        assertFailsWith<Exception> {
            driver.delete("fk_req_parents", parent["id"]!!)
        }
    }

    @Test
    fun `register inside transaction delegates to root driver`() {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        // register() inside withTransaction should run DDL outside
        // the transaction — the table should exist even if we roll back.
        assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.register(USER_SCHEMA)
                error("rollback")
            }
        }
        // Table should still exist despite rollback.
        driver.register(USER_SCHEMA)  // idempotent, no error
        // Verify by inserting outside the transaction.
        val row = driver.insert("users", mapOf<String, Any?>("name" to "After rollback"))
        assertNotNull(row["id"])
        // Clean up
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"users\" RESTART IDENTITY")
            }
        }
    }

    // ---------- M2M edge predicates ----------

    private fun freshM2M(): PostgresDriver {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(M2M_USER_SCHEMA)
        driver.register(M2M_GROUP_SCHEMA)
        driver.register(M2M_USER_GROUP_SCHEMA)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"m2m_user_groups\", \"m2m_groups\", \"m2m_users\" RESTART IDENTITY")
            }
        }
        return driver
    }

    @Test
    fun `HasEdge through junction table`() {
        val driver = freshM2M()
        val alice = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Alice"))
        val group = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Admins"))
        driver.insert("m2m_user_groups", mapOf<String, Any?>("user_id" to alice["id"], "group_id" to group["id"]))

        // Empty group to verify filtering works.
        driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Empty"))

        val rows = driver.query(
            "m2m_groups",
            listOf(Predicate.HasEdge<Any>("users")),
            emptyList(), null, null,
        )
        assertEquals(1, rows.size)
        assertEquals("Admins", rows.single()["name"])
    }

    @Test
    fun `HasEdgeWith through junction table with inner predicate`() {
        val driver = freshM2M()
        val alice = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Alice", "age" to 30))
        val bob = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Bob", "age" to 17))
        val admins = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Admins"))
        val interns = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Interns"))

        driver.insert("m2m_user_groups", mapOf<String, Any?>("user_id" to alice["id"], "group_id" to admins["id"]))
        driver.insert("m2m_user_groups", mapOf<String, Any?>("user_id" to bob["id"], "group_id" to interns["id"]))

        val rows = driver.query(
            "m2m_groups",
            listOf(Predicate.HasEdgeWith<Any, Any>("users", Predicate.Leaf<Any>("age", Op.GTE, 18))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Admins"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `reverse M2M HasEdge from target side`() {
        val driver = freshM2M()
        val alice = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Alice"))
        val bob = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Bob"))
        val group = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Admins"))

        driver.insert("m2m_user_groups", mapOf<String, Any?>("user_id" to alice["id"], "group_id" to group["id"]))

        val rows = driver.query(
            "m2m_users",
            listOf(Predicate.HasEdge<Any>("groups")),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `reverse M2M HasEdgeWith with inner predicate`() {
        val driver = freshM2M()
        val alice = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Alice"))
        val bob = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Bob"))
        val admins = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Admins"))
        val guests = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Guests"))

        driver.insert("m2m_user_groups", mapOf<String, Any?>("user_id" to alice["id"], "group_id" to admins["id"]))
        driver.insert("m2m_user_groups", mapOf<String, Any?>("user_id" to bob["id"], "group_id" to guests["id"]))

        val rows = driver.query(
            "m2m_users",
            listOf(Predicate.HasEdgeWith<Any, Any>("groups", Predicate.Leaf<Any>("name", Op.EQ, "Admins"))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    // ---------- symmetric link-table writes: insertIgnore + relationship serialization ----------

    @Test
    fun `insertIgnore inserts a new junction pair and returns the persisted row`() {
        val driver = freshM2M()
        val alice = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Alice"))
        val admins = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Admins"))

        val inserted = driver.insertIgnore(
            "m2m_user_groups",
            mapOf<String, Any?>("user_id" to alice["id"], "group_id" to admins["id"]),
            conflictColumns = listOf("user_id", "group_id"),
        )

        assertNotNull(inserted, "first insert of a fresh pair returns the row")
        assertEquals(alice["id"], inserted["user_id"])
        assertEquals(admins["id"], inserted["group_id"])
        assertEquals(1L, driver.count("m2m_user_groups", emptyList()))
    }

    @Test
    fun `insertIgnore of an existing pair returns null and does not duplicate`() {
        // ON CONFLICT DO NOTHING yields zero result rows on a
        // conflict; insertIgnore must map that to null rather than throwing,
        // and the existing row must remain (no duplicate, no overwrite).
        val driver = freshM2M()
        val alice = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Alice"))
        val admins = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Admins"))
        val pair = mapOf<String, Any?>("user_id" to alice["id"], "group_id" to admins["id"])

        val first = driver.insertIgnore("m2m_user_groups", pair, conflictColumns = listOf("user_id", "group_id"))
        assertNotNull(first)

        val second = driver.insertIgnore("m2m_user_groups", pair, conflictColumns = listOf("user_id", "group_id"))
        assertNull(second, "re-adding an existing pair is a no-op that returns null")

        assertEquals(1L, driver.count("m2m_user_groups", emptyList()), "row count stays 1")
    }

    @Test
    fun `insertIgnore still throws on a conflict outside the conflictColumns`() {
        // The ON CONFLICT target is scoped to (user_id, group_id). A violation
        // of a *different* constraint — here the primary key on id — must still
        // throw rather than being silently swallowed.
        val driver = freshM2M()
        val alice = driver.insert("m2m_users", mapOf<String, Any?>("name" to "Alice"))
        val admins = driver.insert("m2m_groups", mapOf<String, Any?>("name" to "Admins"))

        val first = driver.insertIgnore(
            "m2m_user_groups",
            mapOf<String, Any?>("user_id" to alice["id"], "group_id" to admins["id"]),
            conflictColumns = listOf("user_id", "group_id"),
        )
        assertNotNull(first)
        val existingId = first["id"]

        // A fresh pair (so the (user_id, group_id) conflict does NOT fire) but a
        // colliding explicit primary key → unique violation on the PK, which is
        // outside the conflict target and therefore propagates. Assert the
        // specific SQLSTATE 23505 (unique_violation) so the test proves the
        // conflict-outside-target path, not merely "some exception".
        val ex = assertFailsWith<org.postgresql.util.PSQLException> {
            driver.insertIgnore(
                "m2m_user_groups",
                mapOf<String, Any?>("id" to existingId, "user_id" to 999L, "group_id" to 999L),
                conflictColumns = listOf("user_id", "group_id"),
            )
        }
        assertEquals("23505", ex.sqlState, "expected a unique_violation on the primary key, got: ${ex.message}")
    }

    @Test
    fun `Postgres driver advertises insertIgnore and relationship-serialization support`() {
        val driver = freshM2M()
        assertTrue(driver.supportsInsertIgnore)
        assertTrue(driver.supportsRelationshipSerialization)
        driver.withTransaction { tx ->
            assertTrue(tx.supportsInsertIgnore)
            assertTrue(tx.supportsRelationshipSerialization)
        }
    }

    @Test
    fun `serializeRelationship on the root driver throws because the advisory lock is xact-scoped`() {
        val driver = freshM2M()
        val key = RelationshipLockKey.canonical("m2m_user_groups", listOf("user_id", "group_id"))
        // Outside a transaction the pg_advisory_xact_lock would release at
        // statement end, defeating the contract — so the root rejects the call.
        assertFailsWith<IllegalStateException> {
            driver.serializeRelationship(key)
        }
    }

    @Test
    fun `serializeRelationship serializes two transactions on the same canonical key`() {
        val driver = freshM2M()
        val key = RelationshipLockKey.canonical("m2m_user_groups", listOf("user_id", "group_id"))
        val order = Collections.synchronizedList(mutableListOf<String>())
        val bEntered = CountDownLatch(1)
        val bError = AtomicReference<Throwable?>(null)

        // Contender: takes the same relationship lock in its own transaction.
        // It must block until the holder's transaction commits.
        val contender = thread(start = false, name = "relationship-lock-contender") {
            try {
                driver.withTransaction { tx ->
                    bEntered.countDown()
                    tx.serializeRelationship(key) // blocks until the holder releases
                    order.add("B-acquired")
                }
            } catch (t: Throwable) {
                bError.set(t)
            }
        }

        driver.withTransaction { tx ->
            tx.serializeRelationship(key) // holder takes the lock first
            order.add("A-acquired")
            contender.start()
            bEntered.await() // contender is now inside its own transaction
            // Give it ample time to reach (and block on) its lock acquisition.
            Thread.sleep(500)
            // It must still be blocked — only the holder has acquired so far.
            assertEquals(listOf("A-acquired"), order.toList())
            order.add("A-releasing")
        } // holder's transaction commits → advisory lock released

        contender.join(5_000)
        assertNull(bError.get(), "contender thread failed: ${bError.get()}")
        assertEquals(listOf("A-acquired", "A-releasing", "B-acquired"), order.toList())
    }

    // ---------- count ----------

    @Test
    fun `count returns total rows with no predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice"))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob"))
        driver.insert("users", mapOf<String, Any?>("name" to "Carol"))

        assertEquals(3L, driver.count("users", emptyList()))
    }

    @Test
    fun `count returns zero for empty table`() {
        val driver = fresh()
        assertEquals(0L, driver.count("users", emptyList()))
    }

    @Test
    fun `count filters with predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Carol", "active" to false))

        assertEquals(2L, driver.count("users", listOf(Predicate.Leaf<Any>("active", Op.EQ, true))))
    }

    // ---------- exists ----------

    @Test
    fun `exists returns true when rows match`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "active" to true))

        assertTrue(driver.exists("users", listOf(Predicate.Leaf<Any>("active", Op.EQ, true))))
    }

    @Test
    fun `exists returns false for empty table`() {
        val driver = fresh()
        assertEquals(false, driver.exists("users", emptyList()))
    }

    @Test
    fun `exists returns false when no rows match`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "active" to true))

        assertEquals(false, driver.exists("users", listOf(Predicate.Leaf<Any>("active", Op.EQ, false))))
    }

    // ---------- insertMany ----------

    @Test
    fun `insertMany inserts multiple rows and assigns ids`() {
        val driver = fresh()
        val rows = driver.insertMany("users", listOf(
            mapOf("name" to "Alice", "age" to 30),
            mapOf("name" to "Bob", "age" to 25),
            mapOf("name" to "Carol", "age" to 40),
        ))

        assertEquals(3, rows.size)
        assertEquals("Alice", rows[0]["name"])
        assertEquals("Bob", rows[1]["name"])
        assertEquals("Carol", rows[2]["name"])
        assertEquals(1L, rows[0]["id"])
        assertEquals(2L, rows[1]["id"])
        assertEquals(3L, rows[2]["id"])
    }

    @Test
    fun `insertMany with empty list returns empty`() {
        val driver = fresh()
        assertEquals(emptyList(), driver.insertMany("users", emptyList()))
    }

    @Test
    fun `insertMany with sparse maps preserves defaults`() {
        val driver = fresh()
        // active has a DB default of null — first row omits it, second provides it.
        // The first row should get null (DB default), not fail.
        val rows = driver.insertMany("users", listOf(
            mapOf("name" to "Alice"),
            mapOf("name" to "Bob", "active" to false),
        ))

        assertEquals(2, rows.size)
        assertNull(rows[0]["active"], "First row should get DB default (null)")
        assertEquals(false, rows[1]["active"])
    }

    @Test
    fun `insertMany with explicit null id uses serial default`() {
        val driver = fresh()
        // Explicit null id should be treated the same as omitted id
        val rows = driver.insertMany("users", listOf(
            mapOf("id" to null, "name" to "Alice"),
            mapOf("name" to "Bob"),
        ))

        assertEquals(2, rows.size)
        assertNotNull(rows[0]["id"], "First row should get auto-assigned id")
        assertNotNull(rows[1]["id"], "Second row should get auto-assigned id")
        assertTrue(rows[0]["id"] != rows[1]["id"], "IDs should be distinct")
    }

    @Test
    fun `insertMany rolls back on failure`() {
        val driver = fresh()
        // Insert a row to claim id=1
        driver.insert("users", mapOf("name" to "Existing"))

        // Try to batch-insert where the second row has a conflicting explicit id
        assertFailsWith<Exception> {
            driver.insertMany("users", listOf(
                mapOf("name" to "New"),
                mapOf("id" to 1L, "name" to "Conflict"),
            ))
        }

        // Only the original row should exist — the batch should have rolled back
        val all = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(1, all.size, "Batch should have rolled back")
        assertEquals("Existing", all[0]["name"])
    }

    @Test
    fun `insertMany correlates explicit ids regardless of value order`() {
        val driver = fresh()
        // Explicit ids deliberately not in ascending order: the result
        // pairing is by id correlation, so input order must survive no
        // matter how the engine orders the RETURNING rows.
        val rows = driver.insertMany("users", listOf(
            mapOf("id" to 30L, "name" to "Carol"),
            mapOf("id" to 10L, "name" to "Alice"),
            mapOf("id" to 20L, "name" to "Bob"),
        ))

        assertEquals(listOf(30L, 10L, 20L), rows.map { it["id"] })
        assertEquals(listOf("Carol", "Alice", "Bob"), rows.map { it["name"] })
    }

    @Test
    fun `insertMany preallocates sequence ids without breaking continuity`() {
        val driver = fresh()
        // Multi-row auto-id batches reserve their ids from the sequence
        // up front (to correlate RETURNING rows by id); a subsequent
        // default-id insert must continue after them, not collide.
        val rows = driver.insertMany("users", listOf(
            mapOf("name" to "Alice"),
            mapOf("name" to "Bob"),
            mapOf("name" to "Carol"),
        ))
        val next = driver.insert("users", mapOf("name" to "Dave"))

        assertEquals(listOf(1L, 2L, 3L), rows.map { it["id"] })
        assertEquals(4L, next["id"])
    }

    @Test
    fun `insertMany correlates mixed explicit-id and auto-id groups`() {
        val driver = fresh()
        // Explicit-id rows and auto-id rows land in different column-set
        // groups; each group correlates independently and the combined
        // result must still be in input order.
        val rows = driver.insertMany("users", listOf(
            mapOf("id" to 100L, "name" to "Explicit1"),
            mapOf("name" to "Auto1"),
            mapOf("id" to 50L, "name" to "Explicit2"),
            mapOf("name" to "Auto2"),
        ))

        assertEquals(listOf("Explicit1", "Auto1", "Explicit2", "Auto2"), rows.map { it["name"] })
        assertEquals(100L, rows[0]["id"])
        assertEquals(50L, rows[2]["id"])
        assertTrue((rows[1]["id"] as Long) != (rows[3]["id"] as Long))
    }

    // ---------- updateMany ----------

    @Test
    fun `updateMany updates matching rows and returns count`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 17, "active" to true))
        driver.insert("users", mapOf("name" to "Carol", "age" to 65, "active" to false))

        val count = driver.updateMany(
            "users",
            mapOf("active" to false),
            listOf(Predicate.Leaf<Any>("age", Op.LT, 18)),
        )

        assertEquals(1, count)
        val bob = driver.query("users", listOf(Predicate.Leaf<Any>("name", Op.EQ, "Bob")), emptyList(), null, null).single()
        assertEquals(false, bob["active"])
    }

    @Test
    fun `updateMany with empty values returns zero`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "active" to true))

        val count = driver.updateMany("users", emptyMap(), emptyList())
        assertEquals(0, count)
    }

    // ---------- deleteMany ----------

    @Test
    fun `deleteMany deletes matching rows and returns count`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30))
        driver.insert("users", mapOf("name" to "Bob", "age" to 17))
        driver.insert("users", mapOf("name" to "Carol", "age" to 65))

        val count = driver.deleteMany("users", listOf(Predicate.Leaf<Any>("age", Op.LT, 18)))

        assertEquals(1, count)
        val remaining = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(2, remaining.size)
    }

    @Test
    fun `deleteMany with no predicates deletes all rows`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))
        driver.insert("users", mapOf("name" to "Bob"))

        val count = driver.deleteMany("users", emptyList())
        assertEquals(2, count)
        assertEquals(0, driver.query("users", emptyList(), emptyList(), null, null).size)
    }

    // ---------- ON DELETE referential actions ----------

    private val CASCADE_PARENT_SCHEMA = EntitySchema(
        table = "cascade_parents",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
    )

    private val CASCADE_CHILD_SCHEMA = EntitySchema(
        table = "cascade_children",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "parent_id", FieldType.LONG, nullable = false,
                references = ForeignKeyRef("cascade_parents", "id", OnDelete.CASCADE),
            ),
        ),
        edges = emptyMap(),
    )

    private val SET_NULL_CHILD_SCHEMA = EntitySchema(
        table = "setnull_children",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "parent_id", FieldType.LONG, nullable = true,
                references = ForeignKeyRef("cascade_parents", "id", OnDelete.SET_NULL),
            ),
        ),
        edges = emptyMap(),
    )

    private val RESTRICT_CHILD_SCHEMA = EntitySchema(
        table = "restrict_children",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "parent_id", FieldType.LONG, nullable = false,
                references = ForeignKeyRef("cascade_parents", "id", OnDelete.RESTRICT),
            ),
        ),
        edges = emptyMap(),
    )

    private fun freshCascade(childSchema: EntitySchema): PostgresDriver {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        // Register all child schemas so their tables exist, then truncate everything
        driver.register(CASCADE_PARENT_SCHEMA)
        driver.register(CASCADE_CHILD_SCHEMA)
        driver.register(SET_NULL_CHILD_SCHEMA)
        driver.register(RESTRICT_CHILD_SCHEMA)
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute(
                    "TRUNCATE TABLE \"cascade_children\", \"setnull_children\", " +
                        "\"restrict_children\", \"cascade_parents\" RESTART IDENTITY",
                )
            }
        }
        return driver
    }

    @Test
    fun `cascade delete removes child rows in postgres`() {
        val driver = freshCascade(CASCADE_CHILD_SCHEMA)
        val parent = driver.insert("cascade_parents", mapOf("name" to "P1"))
        driver.insert("cascade_children", mapOf("name" to "C1", "parent_id" to parent["id"]))
        driver.insert("cascade_children", mapOf("name" to "C2", "parent_id" to parent["id"]))

        driver.delete("cascade_parents", parent["id"]!!)

        assertEquals(0, driver.query("cascade_children", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `set null nulls FK on child rows in postgres`() {
        val driver = freshCascade(SET_NULL_CHILD_SCHEMA)
        val parent = driver.insert("cascade_parents", mapOf("name" to "P1"))
        driver.insert("setnull_children", mapOf("name" to "C1", "parent_id" to parent["id"]))

        driver.delete("cascade_parents", parent["id"]!!)

        val children = driver.query("setnull_children", emptyList(), emptyList(), null, null)
        assertEquals(1, children.size)
        assertNull(children.single()["parent_id"])
    }

    @Test
    fun `restrict prevents delete when children exist in postgres`() {
        val driver = freshCascade(RESTRICT_CHILD_SCHEMA)
        val parent = driver.insert("cascade_parents", mapOf("name" to "P1"))
        driver.insert("restrict_children", mapOf("name" to "C1", "parent_id" to parent["id"]))

        assertFailsWith<Exception> {
            driver.delete("cascade_parents", parent["id"]!!)
        }
        // Parent should still exist
        assertNotNull(driver.byId("cascade_parents", parent["id"]!!))
    }

    @Test
    fun `SET_NULL on non-nullable column rejects at DDL render time`() {
        val badSchema = EntitySchema(
            table = "bad_setnull",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = false,
                    references = ForeignKeyRef("cascade_parents", "id", OnDelete.SET_NULL),
                ),
            ),
            edges = emptyMap(),
        )
        val pgDriver = PostgresDriver(dataSource, autoDdl = true)
        assertFailsWith<IllegalArgumentException> {
            pgDriver.register(badSchema)
        }
    }

    // ---------- explainQuery ----------

    @Test
    fun `explainQuery returns SQL and args without executing`() {
        val driver = fresh()
        val explanation = driver.explainQuery(
            "users",
            listOf(Predicate.Leaf<Any>("name", Op.EQ, "alice"), Predicate.Leaf<Any>("age", Op.GT, 25)),
            listOf(OrderField<Any>("name", OrderDirection.ASC)),
            10,
            5,
        )
        val pg = explanation as PostgresQueryExplanation
        assertEquals(
            "SELECT t0.* FROM \"users\" AS t0 WHERE (t0.\"name\" = ? AND t0.\"age\" > ?) ORDER BY t0.\"name\" ASC LIMIT 10 OFFSET 5",
            pg.sql,
        )
        assertEquals(listOf("alice", 25), pg.args)
    }

    @Test
    fun `explainQuery with no predicates produces unconditional SELECT`() {
        val driver = fresh()
        val pg = driver.explainQuery("users", emptyList(), emptyList(), null, null) as PostgresQueryExplanation
        assertEquals("SELECT t0.* FROM \"users\" AS t0", pg.sql)
        assertTrue(pg.args.isEmpty())
    }

    @Test
    fun `explainQuery with edge predicate produces EXISTS subquery`() {
        val driver = fresh()
        val pg = driver.explainQuery(
            "users",
            listOf(Predicate.HasEdgeWith<Any, Any>("posts", Predicate.Leaf<Any>("published", Op.EQ, true))),
            emptyList(),
            null,
            null,
        ) as PostgresQueryExplanation
        assertTrue(pg.sql.contains("EXISTS"))
        assertTrue(pg.sql.contains("\"posts\""))
        assertEquals(listOf(true), pg.args)
    }

    // ---------- shaped traversal lowering ----------

    @Test
    fun `shaped direct traversal lowers to a source-id IN subquery preserving order limit offset`() {
        val driver = fresh()
        // posts candidate constrained by a shaped users source —
        // the User.queryPosts() bridge: posts.author_id IN
        // (10 users, name-desc, after the first 5, active only).
        val pg = driver.explainQuery(
            "posts",
            listOf(
                Predicate.HasEdgeFromShape<Any, Any>(
                    "author",
                    TraversalSourceShape(
                        table = "users",
                        selectedColumn = "id",
                        predicates = listOf(Predicate.Leaf("active", Op.EQ, true)),
                        orderBy = listOf(OrderField("name", OrderDirection.DESC)),
                        limit = 10,
                        offset = 5,
                        flags = emptySet(),
                    ),
                ),
            ),
            emptyList(),
            null,
            null,
        ) as PostgresQueryExplanation
        assertEquals(
            "SELECT t0.* FROM \"posts\" AS t0 WHERE t0.\"author_id\" IN " +
                "(SELECT t1.\"id\" FROM \"users\" AS t1 WHERE t1.\"active\" = ? " +
                "ORDER BY t1.\"name\" DESC LIMIT 10 OFFSET 5)",
            pg.sql,
        )
        assertEquals(listOf(true), pg.args)
    }

    @Test
    fun `shaped child-to-parent traversal selects the source FK column`() {
        val driver = fresh()
        // users candidate constrained by a shaped posts source —
        // the Post.queryAuthor() bridge: users.id IN (author_id of
        // the 10 newest published posts).
        val pg = driver.explainQuery(
            "users",
            listOf(
                Predicate.HasEdgeFromShape<Any, Any>(
                    "posts",
                    TraversalSourceShape(
                        table = "posts",
                        selectedColumn = "author_id",
                        predicates = listOf(Predicate.Leaf("published", Op.EQ, true)),
                        orderBy = listOf(OrderField("id", OrderDirection.DESC)),
                        limit = 10,
                        offset = null,
                        flags = emptySet(),
                    ),
                ),
            ),
            emptyList(),
            null,
            null,
        ) as PostgresQueryExplanation
        assertEquals(
            "SELECT t0.* FROM \"users\" AS t0 WHERE t0.\"id\" IN " +
                "(SELECT t1.\"author_id\" FROM \"posts\" AS t1 WHERE t1.\"published\" = ? " +
                "ORDER BY t1.\"id\" DESC LIMIT 10)",
            pg.sql,
        )
        assertEquals(listOf(true), pg.args)
    }

    @Test
    fun `shaped M2M traversal feeds the junction from the shaped source subquery`() {
        val driver = freshM2M()
        // m2m_groups candidate via the forward "groups" edge on
        // m2m_users — the User.queryGroups() bridge.
        val pg = driver.explainQuery(
            "m2m_groups",
            listOf(
                Predicate.HasM2MEdgeFromShape<Any, Any>(
                    "groups",
                    TraversalSourceShape(
                        table = "m2m_users",
                        selectedColumn = "id",
                        predicates = listOf(Predicate.Leaf("age", Op.GTE, 18)),
                        orderBy = listOf(OrderField("name", OrderDirection.ASC)),
                        limit = 3,
                        offset = null,
                        flags = emptySet(),
                    ),
                ),
            ),
            emptyList(),
            null,
            null,
        ) as PostgresQueryExplanation
        assertEquals(
            "SELECT t0.* FROM \"m2m_groups\" AS t0 WHERE t0.\"id\" IN " +
                "(SELECT t1.\"group_id\" FROM \"m2m_user_groups\" AS t1 WHERE t1.\"user_id\" IN " +
                "(SELECT t2.\"id\" FROM \"m2m_users\" AS t2 WHERE t2.\"age\" >= ? " +
                "ORDER BY t2.\"name\" ASC LIMIT 3))",
            pg.sql,
        )
        assertEquals(listOf(18), pg.args)
    }

    @Test
    fun `shaped traversal with offset but no limit keeps the subquery ORDER BY`() {
        val driver = fresh()
        // OFFSET without ORDER BY skips rows in unspecified order, so
        // an offset-only source shape must keep its ordering to stay
        // deterministic. (The subquery renders ORDER BY
        // unconditionally — even with no bounds at all.)
        val pg = driver.explainQuery(
            "posts",
            listOf(
                Predicate.HasEdgeFromShape<Any, Any>(
                    "author",
                    TraversalSourceShape(
                        table = "users",
                        selectedColumn = "id",
                        predicates = emptyList(),
                        orderBy = listOf(OrderField("name", OrderDirection.ASC)),
                        limit = null,
                        offset = 5,
                        flags = emptySet(),
                    ),
                ),
            ),
            emptyList(),
            null,
            null,
        ) as PostgresQueryExplanation
        assertEquals(
            "SELECT t0.* FROM \"posts\" AS t0 WHERE t0.\"author_id\" IN " +
                "(SELECT t1.\"id\" FROM \"users\" AS t1 ORDER BY t1.\"name\" ASC OFFSET 5)",
            pg.sql,
        )
        assertTrue(pg.args.isEmpty())
    }

    @Test
    fun `shaped M2M traversal preserves a source offset through the junction walk`() {
        val driver = freshM2M()
        val pg = driver.explainQuery(
            "m2m_groups",
            listOf(
                Predicate.HasM2MEdgeFromShape<Any, Any>(
                    "groups",
                    TraversalSourceShape(
                        table = "m2m_users",
                        selectedColumn = "id",
                        predicates = emptyList(),
                        orderBy = listOf(OrderField("id", OrderDirection.ASC)),
                        limit = 2,
                        offset = 4,
                        flags = emptySet(),
                    ),
                ),
            ),
            emptyList(),
            null,
            null,
        ) as PostgresQueryExplanation
        assertEquals(
            "SELECT t0.* FROM \"m2m_groups\" AS t0 WHERE t0.\"id\" IN " +
                "(SELECT t1.\"group_id\" FROM \"m2m_user_groups\" AS t1 WHERE t1.\"user_id\" IN " +
                "(SELECT t2.\"id\" FROM \"m2m_users\" AS t2 ORDER BY t2.\"id\" ASC LIMIT 2 OFFSET 4))",
            pg.sql,
        )
        assertTrue(pg.args.isEmpty())
    }

    @Test
    fun `shaped traversal without bounds still renders the source ORDER BY`() {
        val driver = fresh()
        // Without limit/offset the ordering can't change which source
        // rows are selected, but it renders anyway so traversal keeps
        // exact behavior parity with the source query run standalone —
        // a malformed hand-built order field must not silently succeed
        // via traversal only until a limit is added.
        val pg = driver.explainQuery(
            "posts",
            listOf(
                Predicate.HasEdgeFromShape<Any, Any>(
                    "author",
                    TraversalSourceShape(
                        table = "users",
                        selectedColumn = "id",
                        predicates = listOf(Predicate.Leaf("active", Op.EQ, true)),
                        orderBy = listOf(OrderField("name", OrderDirection.DESC)),
                        limit = null,
                        offset = null,
                        flags = emptySet(),
                    ),
                ),
            ),
            emptyList(),
            null,
            null,
        ) as PostgresQueryExplanation
        assertEquals(
            "SELECT t0.* FROM \"posts\" AS t0 WHERE t0.\"author_id\" IN " +
                "(SELECT t1.\"id\" FROM \"users\" AS t1 WHERE t1.\"active\" = ? " +
                "ORDER BY t1.\"name\" DESC)",
            pg.sql,
        )
        assertEquals(listOf(true), pg.args)
    }
}
