package entkt.postgres

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.ColumnMetadata
import entkt.runtime.EdgeMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.ForeignKeyRef
import entkt.runtime.IdStrategy
import entkt.runtime.IndexMetadata
import entkt.schema.FieldType
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
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
    edges = emptyMap(),
)

private fun quoteIdent(identifier: String): String =
    "\"${identifier.replace("\"", "\"\"")}\""

/**
 * Parity tests for [PostgresDriver] against a real Postgres container.
 * The assertions mirror `InMemoryDriverTest` so any divergence between
 * the two drivers shows up immediately.
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
        val driver = PostgresDriver(dataSource)
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
    fun `query filters with leaf predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "age" to 17, "active" to true))
        driver.insert("users", mapOf<String, Any?>("name" to "Carol", "age" to 65, "active" to false))

        val adults = driver.query(
            table = "users",
            predicates = listOf(Predicate.Leaf("age", Op.GTE, 18)),
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
            orderBy = listOf(OrderField("age", OrderDirection.DESC)),
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
            Predicate.Leaf("active", Op.EQ, true),
            Predicate.Or(
                Predicate.Leaf("age", Op.GTE, 65),
                Predicate.Leaf("name", Op.EQ, "Alice"),
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

        val pred = Predicate.HasEdgeWith(
            edge = "posts",
            inner = Predicate.Leaf("published", Op.EQ, true),
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
            listOf(Predicate.HasEdge("author")),
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
            listOf(Predicate.Leaf("name", Op.CONTAINS, "example")),
            emptyList(), null, null,
        )
        assertEquals(
            setOf("alice@example.com", "bob@admin.example.com"),
            containsExample.map { it["name"] }.toSet(),
        )

        val prefixAlice = driver.query(
            "users",
            listOf(Predicate.Leaf("name", Op.HAS_PREFIX, "alice")),
            emptyList(), null, null,
        )
        assertEquals(setOf("alice@example.com"), prefixAlice.map { it["name"] }.toSet())

        val suffixOrg = driver.query(
            "users",
            listOf(Predicate.Leaf("name", Op.HAS_SUFFIX, ".org")),
            emptyList(), null, null,
        )
        assertEquals(setOf("carol@other.org"), suffixOrg.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles IN and NOT_IN`() {
        val driver = fresh()
        driver.insert("users", mapOf<String, Any?>("name" to "Alice", "age" to 30))
        driver.insert("users", mapOf<String, Any?>("name" to "Bob", "age" to 40))
        driver.insert("users", mapOf<String, Any?>("name" to "Carol", "age" to 50))

        val inAges = driver.query(
            "users",
            listOf(Predicate.Leaf("age", Op.IN, listOf(30, 50))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice", "Carol"), inAges.map { it["name"] }.toSet())

        val notInAges = driver.query(
            "users",
            listOf(Predicate.Leaf("age", Op.NOT_IN, listOf(30, 50))),
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
            listOf(Predicate.Leaf("age", Op.IS_NULL, null)),
            emptyList(), null, null,
        )
        assertEquals(setOf("Bob"), noAge.map { it["name"] }.toSet())

        val withAge = driver.query(
            "users",
            listOf(Predicate.Leaf("age", Op.IS_NOT_NULL, null)),
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

        val driver = PostgresDriver(dataSource)
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
            predicates = listOf(Predicate.Leaf(nameColumn, Op.EQ, "Alice")),
            orderBy = listOf(OrderField(nameColumn, OrderDirection.ASC)),
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
        val driver = PostgresDriver(dataSource)
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
    fun `transaction driver throws after block returns including register`() {
        val driver = fresh()
        var captured: entkt.runtime.Driver? = null
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
        val driver = PostgresDriver(dataSource)
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
                IndexMetadata(columns = listOf("user_id", "group_id"), unique = true),
            ),
        )
        val driver = PostgresDriver(dataSource)
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
                IndexMetadata(columns = listOf("category", "priority")),
            ),
        )
        val driver = PostgresDriver(dataSource)
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
        val driver = PostgresDriver(dataSource)
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
        val driver = PostgresDriver(dataSource)
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
        val driver = PostgresDriver(dataSource)
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
        val driver = PostgresDriver(dataSource)
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
            listOf(Predicate.HasEdge("users")),
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
            listOf(Predicate.HasEdgeWith("users", Predicate.Leaf("age", Op.GTE, 18))),
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
            listOf(Predicate.HasEdge("groups")),
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
            listOf(Predicate.HasEdgeWith("groups", Predicate.Leaf("name", Op.EQ, "Admins"))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }
}
