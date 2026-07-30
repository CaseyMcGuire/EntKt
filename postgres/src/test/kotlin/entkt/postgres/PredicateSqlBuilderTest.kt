@file:OptIn(entkt.query.EntktInternal::class)

package entkt.postgres

import entkt.postgres.vector.PgVector
import entkt.postgres.vector.cosineDistance
import entkt.query.Column
import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.TraversalSourceShape
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EdgeMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.schema.ColumnStorage
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [PredicateSqlBuilder] — no database. These pin the
 * component's contract directly (exact SQL fragments, parameter order,
 * alias allocation, error messages) so a lowering regression fails here
 * in milliseconds instead of surfacing as a distant testcontainers
 * failure. Behavioral coverage against real Postgres lives in
 * [PostgresDriverTest]; this file is about the *text* contract.
 */
class PredicateSqlBuilderTest {

    private val users = EntitySchema(
        table = "users",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata("age", FieldType.INT, nullable = true),
            ColumnMetadata(
                "embedding", FieldType.PGVECTOR, nullable = true,
                storage = ColumnStorage.Native(
                    dialect = "postgres", typeName = "vector", sqlType = "vector(3)",
                    codec = "postgres.vector", requiredExtension = "vector", dimensions = 3,
                ),
            ),
        ),
        edges = mapOf(
            "posts" to EdgeMetadata(targetTable = "posts", sourceColumn = "id", targetColumn = "author_id"),
            "groups" to EdgeMetadata(
                targetTable = "groups", sourceColumn = "id", targetColumn = "id",
                junctionTable = "user_groups", junctionSourceColumn = "user_id", junctionTargetColumn = "group_id",
            ),
            // Deliberately points at a table absent from the registry.
            "ghost" to EdgeMetadata(targetTable = "phantoms", sourceColumn = "id", targetColumn = "user_id"),
        ),
    )

    private val posts = EntitySchema(
        table = "posts",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("author_id", FieldType.LONG, nullable = false),
            ColumnMetadata("title", FieldType.STRING, nullable = false),
        ),
        edges = mapOf(
            "comments" to EdgeMetadata(targetTable = "comments", sourceColumn = "id", targetColumn = "post_id"),
            "author" to EdgeMetadata(targetTable = "users", sourceColumn = "author_id", targetColumn = "id"),
        ),
    )

    private val comments = EntitySchema(
        table = "comments",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("post_id", FieldType.LONG, nullable = false),
        ),
        edges = emptyMap(),
    )

    private val groups = EntitySchema(
        table = "groups",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ),
        edges = emptyMap(),
    )

    private val registry = mapOf(
        "users" to users, "posts" to posts, "comments" to comments, "groups" to groups,
    )

    /** Lower [p] against `users` as `t0` with a fresh per-statement builder. */
    private fun lower(p: Predicate<*>, schema: EntitySchema = users): Pair<String, List<Param>> {
        val builder = PredicateSqlBuilder(registry)
        val sql = builder.lower(p, schema, "t0")
        return sql to builder.params.toList()
    }

    // ---------- scalar leaves ----------

    @Test
    fun `comparison ops lower to a placeholder with the column's type`() {
        val expected = mapOf(
            Op.EQ to "=", Op.NEQ to "<>", Op.GT to ">", Op.GTE to ">=", Op.LT to "<", Op.LTE to "<=",
        )
        for ((op, symbol) in expected) {
            val (sql, params) = lower(Predicate.Leaf<Any>("age", op, 30))
            assertEquals("""t0."age" $symbol ?""", sql, "op $op")
            assertEquals(listOf(Param(FieldType.INT, 30)), params, "op $op binds the typed value")
        }
    }

    @Test
    fun `null checks lower without parameters`() {
        assertEquals("""t0."age" IS NULL""" to emptyList(), lower(Predicate.Leaf<Any>("age", Op.IS_NULL, null)))
        assertEquals("""t0."age" IS NOT NULL""" to emptyList(), lower(Predicate.Leaf<Any>("age", Op.IS_NOT_NULL, null)))
    }

    @Test
    fun `a leaf on a column unknown to the schema binds an untyped parameter`() {
        // Raw callers can reference any column; the type lookup falls back to
        // null (bind resolves it via setObject) rather than rejecting here.
        val (sql, params) = lower(Predicate.Leaf<Any>("nope", Op.EQ, 1))
        assertEquals("""t0."nope" = ?""", sql)
        assertEquals(listOf(Param(null, 1)), params)
    }

    // ---------- IN / NOT IN ----------

    @Test
    fun `IN lowers one placeholder per item, params in item order`() {
        val (sql, params) = lower(Predicate.Leaf<Any>("age", Op.IN, listOf(1, 2, 3)))
        assertEquals("""t0."age" IN (?, ?, ?)""", sql)
        assertEquals(listOf(Param(FieldType.INT, 1), Param(FieldType.INT, 2), Param(FieldType.INT, 3)), params)
    }

    @Test
    fun `NOT IN lowers like IN, negated`() {
        val (sql, params) = lower(Predicate.Leaf<Any>("age", Op.NOT_IN, listOf(7)))
        assertEquals("""t0."age" NOT IN (?)""", sql)
        assertEquals(listOf(Param(FieldType.INT, 7)), params)
    }

    @Test
    fun `empty IN folds to FALSE and empty NOT IN folds to TRUE`() {
        assertEquals("FALSE" to emptyList(), lower(Predicate.Leaf<Any>("age", Op.IN, emptyList<Int>())))
        assertEquals("TRUE" to emptyList(), lower(Predicate.Leaf<Any>("age", Op.NOT_IN, emptyList<Int>())))
    }

    // ---------- LIKE family ----------

    @Test
    fun `CONTAINS escapes LIKE wildcards and the escape char itself`() {
        val (sql, params) = lower(Predicate.Leaf<Any>("name", Op.CONTAINS, """50%_off\"""))
        assertEquals("""t0."name" LIKE ? ESCAPE '\'""", sql)
        assertEquals(listOf(Param(FieldType.STRING, """%50\%\_off\\%""")), params)
    }

    @Test
    fun `HAS_PREFIX anchors the pattern at the start`() {
        val (sql, params) = lower(Predicate.Leaf<Any>("name", Op.HAS_PREFIX, "Al"))
        assertEquals("""t0."name" LIKE ? ESCAPE '\'""", sql)
        assertEquals(listOf(Param(FieldType.STRING, "Al%")), params)
    }

    @Test
    fun `HAS_SUFFIX anchors the pattern at the end`() {
        val (sql, params) = lower(Predicate.Leaf<Any>("name", Op.HAS_SUFFIX, "ce"))
        assertEquals("""t0."name" LIKE ? ESCAPE '\'""", sql)
        assertEquals(listOf(Param(FieldType.STRING, "%ce")), params)
    }

    // ---------- boolean composition ----------

    @Test
    fun `And and Or parenthesize and accumulate params left to right`() {
        val p = Predicate.And<Any>(
            Predicate.Leaf("age", Op.GT, 30),
            Predicate.Or(
                Predicate.Leaf("name", Op.EQ, "x"),
                Predicate.Leaf("name", Op.EQ, "y"),
            ),
        )
        val (sql, params) = lower(p)
        assertEquals("""(t0."age" > ? AND (t0."name" = ? OR t0."name" = ?))""", sql)
        assertEquals(
            listOf(Param(FieldType.INT, 30), Param(FieldType.STRING, "x"), Param(FieldType.STRING, "y")),
            params,
        )
    }

    // ---------- edge predicates ----------

    @Test
    fun `HasEdge lowers a direct edge to an EXISTS subquery`() {
        val (sql, params) = lower(Predicate.HasEdge<Any>("posts"))
        assertEquals(
            """EXISTS (SELECT 1 FROM "posts" AS t1 WHERE t1."author_id" = t0."id")""",
            sql,
        )
        assertTrue(params.isEmpty())
    }

    @Test
    fun `HasEdgeWith lowers the inner predicate against the target alias`() {
        val (sql, params) = lower(
            Predicate.HasEdgeWith<Any, Any>("posts", Predicate.Leaf("title", Op.EQ, "hi")),
        )
        assertEquals(
            """EXISTS (SELECT 1 FROM "posts" AS t1 WHERE t1."author_id" = t0."id" AND t1."title" = ?)""",
            sql,
        )
        assertEquals(listOf(Param(FieldType.STRING, "hi")), params)
    }

    @Test
    fun `nested edge predicates allocate fresh aliases per subquery`() {
        val (sql, _) = lower(
            Predicate.HasEdgeWith<Any, Any>("posts", Predicate.HasEdge<Any>("comments")),
        )
        assertEquals(
            """EXISTS (SELECT 1 FROM "posts" AS t1 WHERE t1."author_id" = t0."id" AND """ +
                """EXISTS (SELECT 1 FROM "comments" AS t2 WHERE t2."post_id" = t1."id"))""",
            sql,
        )
    }

    @Test
    fun `sibling edge predicates in one statement get distinct aliases`() {
        val (sql, _) = lower(
            Predicate.And<Any>(Predicate.HasEdge("posts"), Predicate.HasEdge("posts")),
        )
        assertEquals(
            """(EXISTS (SELECT 1 FROM "posts" AS t1 WHERE t1."author_id" = t0."id") AND """ +
                """EXISTS (SELECT 1 FROM "posts" AS t2 WHERE t2."author_id" = t0."id"))""",
            sql,
        )
    }

    @Test
    fun `HasEdge lowers an M2M edge through its junction table`() {
        val (sql, _) = lower(Predicate.HasEdge<Any>("groups"))
        assertEquals(
            """EXISTS (SELECT 1 FROM "user_groups" AS t1 JOIN "groups" AS t2 ON t2."id" = t1."group_id" """ +
                """WHERE t1."user_id" = t0."id")""",
            sql,
        )
    }

    @Test
    fun `HasM2MEdgeFrom walks the junction backwards from the candidate target`() {
        val (sql, params) = lower(
            Predicate.HasM2MEdgeFrom<Any, Any>("users", "groups", Predicate.Leaf("name", Op.EQ, "x")),
            schema = groups,
        )
        assertEquals(
            """EXISTS (SELECT 1 FROM "user_groups" AS t1 JOIN "users" AS t2 ON t1."user_id" = t2."id" """ +
                """WHERE t1."group_id" = t0."id" AND t1."user_id" = t2."id" AND t2."name" = ?)""",
            sql,
        )
        assertEquals(listOf(Param(FieldType.STRING, "x")), params)
    }

    // ---------- error contracts ----------

    @Test
    fun `an edge without metadata fails with a named error`() {
        val ex = assertFailsWith<IllegalStateException> { lower(Predicate.HasEdge<Any>("nope")) }
        assertTrue("users.nope has no metadata" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `an edge to an unregistered table fails with a named error`() {
        val ex = assertFailsWith<IllegalStateException> { lower(Predicate.HasEdge<Any>("ghost")) }
        assertTrue("points at unregistered phantoms" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `HasM2MEdgeFrom rejects an unregistered source table and a non-M2M edge`() {
        val unregistered = assertFailsWith<IllegalStateException> {
            lower(Predicate.HasM2MEdgeFrom<Any, Any>("nowhere", "groups", null), schema = groups)
        }
        assertTrue("unregistered source table nowhere" in unregistered.message!!, unregistered.message ?: "")

        val notM2m = assertFailsWith<IllegalStateException> {
            lower(Predicate.HasM2MEdgeFrom<Any, Any>("users", "posts", null), schema = posts)
        }
        assertTrue("is not M2M" in notM2m.message!!, notM2m.message ?: "")
    }

    // ---------- pgvector operand validation ----------

    @Test
    fun `a wrong-dimension vector operand is rejected with a field-named error`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            lower(Predicate.Leaf<Any>("embedding", Op.EQ, PgVector.of(floatArrayOf(1f, 0f))))
        }
        assertTrue("predicate on 'users.embedding'" in ex.message!!, ex.message ?: "")
        assertTrue("expects a 3-dimensional vector, got 2" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `IN validates vector dimensions per item`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            lower(
                Predicate.Leaf<Any>(
                    "embedding", Op.IN,
                    listOf(PgVector.of(floatArrayOf(1f, 0f, 0f)), PgVector.of(floatArrayOf(1f))),
                ),
            )
        }
        assertTrue("expects a 3-dimensional vector, got 1" in ex.message!!, ex.message ?: "")
    }

    // ---------- shaped traversal source ----------

    private fun userShape(
        orderBy: List<OrderField<Any>> = emptyList(),
        limit: Int? = null,
        offset: Int? = null,
    ) = TraversalSourceShape<Any>(
        table = "users",
        selectedColumn = "id",
        predicates = emptyList(),
        orderBy = orderBy,
        limit = limit,
        offset = offset,
        flags = emptySet(),
    )

    @Test
    fun `shaped source distance ordering binds the operand after subquery WHERE params`() {
        val shape = TraversalSourceShape<Any>(
            table = "users",
            selectedColumn = "id",
            predicates = listOf(Predicate.Leaf("age", Op.GTE, 18)),
            orderBy = listOf(
                Column<Any, PgVector>("embedding")
                    .cosineDistance(PgVector.of(floatArrayOf(1f, 0f, 0f))).asc(),
            ),
            limit = 5,
            offset = null,
            flags = emptySet(),
        )
        val (sql, params) = lower(Predicate.HasEdgeFromShape<Any, Any>("author", shape), schema = posts)
        assertEquals(
            """t0."author_id" IN (SELECT t1."id" FROM "users" AS t1 WHERE t1."age" >= ? """ +
                """ORDER BY t1."embedding" <=> ? ASC NULLS LAST LIMIT 5)""",
            sql,
        )
        assertEquals(FieldType.INT, params[0].type)
        assertEquals(FieldType.PGVECTOR, params[1].type)
    }

    @Test
    fun `shaped source without bounds still rejects a wrong-dimension distance operand`() {
        // The subquery renders its ORDER BY (and so validates the
        // operand) even when no limit/offset is present — the same
        // query with limit(5) throws, and validity must not depend on
        // whether bounds are present.
        val shape = userShape(
            orderBy = listOf(
                Column<Any, PgVector>("embedding")
                    .cosineDistance(PgVector.of(floatArrayOf(1f, 0f))).asc(),
            ),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            lower(Predicate.HasEdgeFromShape<Any, Any>("author", shape), schema = posts)
        }
        assertTrue("orderBy distance on 'users.embedding'" in ex.message!!, ex.message ?: "")
        assertTrue("expects a 3-dimensional vector, got 2" in ex.message!!, ex.message ?: "")
    }
}
