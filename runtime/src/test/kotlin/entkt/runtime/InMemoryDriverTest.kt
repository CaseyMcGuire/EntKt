package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.schema.FieldType
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

class InMemoryDriverTest {

    private fun fresh(): InMemoryDriver = InMemoryDriver().apply {
        register(USER_SCHEMA)
        register(POST_SCHEMA)
    }

    @Test
    fun `insert assigns auto-long ids and returns the persisted row`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))

        assertEquals(1L, row["id"], "First insert should get id=1")
        assertEquals("Alice", row["name"])

        val second = driver.insert("users", mapOf("name" to "Bob", "age" to 25, "active" to true))
        assertEquals(2L, second["id"], "Second insert should get id=2")
    }

    @Test
    fun `byId returns null for missing rows and the persisted row otherwise`() {
        val driver = fresh()
        val inserted = driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))

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
        assertNull(driver.update("users", 42L, mapOf("name" to "Ghost")))
    }

    @Test
    fun `query filters with leaf predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 17, "active" to true))
        driver.insert("users", mapOf("name" to "Carol", "age" to 65, "active" to false))

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
        driver.insert("users", mapOf("name" to "Carol", "age" to 65, "active" to true))
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 25, "active" to true))

        val sortedByAgeDesc = driver.query(
            "users",
            predicates = emptyList(),
            orderBy = listOf(OrderField("age", OrderDirection.DESC)),
            limit = 2,
            offset = 1,
        )
        // age order desc: Carol(65), Alice(30), Bob(25). offset=1 → Alice, Bob.
        assertEquals(listOf("Alice", "Bob"), sortedByAgeDesc.map { it["name"] })
    }

    @Test
    fun `query handles compound and or predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 70, "active" to false))
        driver.insert("users", mapOf("name" to "Carol", "age" to 17, "active" to true))

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
        // Users who have at least one published post.
        val driver = fresh()
        val alice = driver.insert("users", mapOf("name" to "Alice"))
        val bob = driver.insert("users", mapOf("name" to "Bob"))

        driver.insert("posts", mapOf("title" to "hi", "published" to true, "author_id" to alice["id"]))
        driver.insert("posts", mapOf("title" to "draft", "published" to false, "author_id" to bob["id"]))

        val pred = Predicate.HasEdgeWith(
            edge = "posts",
            inner = Predicate.Leaf("published", Op.EQ, true),
        )
        val rows = driver.query("users", listOf(pred), emptyList(), null, null)
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles HasEdge from the from-side`() {
        // Posts whose author exists at all (always true here, but the
        // join machinery still has to find the user).
        val driver = fresh()
        val alice = driver.insert("users", mapOf("name" to "Alice"))
        driver.insert("posts", mapOf("title" to "hi", "published" to true, "author_id" to alice["id"]))

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
    fun `delete removes the row and returns true`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf("name" to "Alice"))

        assertTrue(driver.delete("users", row["id"]!!))
        assertNull(driver.byId("users", row["id"]!!))
        assertEquals(false, driver.delete("users", row["id"]!!), "second delete is a no-op")
    }

    @Test
    fun `unregistered table is rejected loudly`() {
        val driver = InMemoryDriver()
        assertFailsWith<IllegalStateException> { driver.insert("nope", emptyMap()) }
    }

    // ---------- Transactions ----------

    @Test
    fun `withTransaction commits on success`() {
        val driver = fresh()
        driver.withTransaction { tx ->
            tx.insert("users", mapOf("name" to "Alice"))
            tx.insert("users", mapOf("name" to "Bob"))
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(setOf("Alice", "Bob"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `withTransaction rolls back on exception`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Pre-existing"))

        assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.insert("users", mapOf("name" to "Alice"))
                tx.insert("users", mapOf("name" to "Bob"))
                error("boom")
            }
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(listOf("Pre-existing"), rows.map { it["name"] })
    }

    @Test
    fun `withTransaction rollback restores id counters`() {
        val driver = fresh()
        assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.insert("users", mapOf("name" to "Ghost"))  // id=1
                error("boom")
            }
        }
        // After rollback, the next insert should get id=1 again.
        val row = driver.insert("users", mapOf("name" to "Real"))
        assertEquals(1L, row["id"])
    }

    @Test
    fun `nested withTransaction reuses the same transaction`() {
        val driver = fresh()
        driver.withTransaction { outer ->
            outer.insert("users", mapOf("name" to "Alice"))
            outer.withTransaction { inner ->
                inner.insert("users", mapOf("name" to "Bob"))
            }
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(setOf("Alice", "Bob"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `transaction driver throws after block returns`() {
        val driver = fresh()
        var captured: Driver? = null
        driver.withTransaction { tx ->
            captured = tx
        }
        assertFailsWith<IllegalStateException> {
            captured!!.insert("users", mapOf("name" to "Late"))
        }
    }
}
