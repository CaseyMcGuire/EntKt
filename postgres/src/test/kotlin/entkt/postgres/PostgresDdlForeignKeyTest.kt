package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto-DDL renders foreign keys as standalone
 * `ALTER TABLE ... ADD CONSTRAINT` statements rather than inline
 * `REFERENCES` clauses, so that `register()` — which only ever sees one
 * schema at a time — doesn't impose an impossible ordering on
 * mutually-referencing tables.
 *
 * See [PostgresCyclicForeignKeyTest] for the end-to-end proof against a
 * real server.
 */
class PostgresDdlForeignKeyTest {

    private val ddl = PostgresDdl()

    private val posts = EntitySchema(
        table = "posts",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata(
                "author_id",
                FieldType.LONG,
                nullable = false,
                references = ForeignKeyRef("users", "id", OnDelete.CASCADE),
            ),
        ),
        edges = emptyMap(),
    )

    @Test
    fun `createTableSql does not inline REFERENCES`() {
        val sql = ddl.createTableSql(posts)

        // The whole point: creating this table must not depend on
        // "users" already existing.
        assertFalse(sql.contains("REFERENCES"), "FK must not be inline; was: $sql")
        // Everything else about the column survives.
        assertTrue(sql.contains("\"author_id\" bigint NOT NULL"), "column DDL preserved; was: $sql")
    }

    @Test
    fun `foreignKeysFor renders an ALTER TABLE per FK column`() {
        val fks = ddl.foreignKeysFor(posts)

        assertEquals(1, fks.size)
        val fk = fks.single()
        assertEquals("posts", fk.table)
        assertEquals("author_id", fk.column)
        assertEquals("users", fk.targetTable)
        assertEquals("id", fk.targetColumn)
        // Same convention as PostgresSqlRenderer, so auto-DDL and the
        // migration path converge on one constraint name.
        assertEquals("fk_posts_author_id", fk.constraintName)

        assertEquals(
            "ALTER TABLE \"posts\" ADD CONSTRAINT \"fk_posts_author_id\" " +
                "FOREIGN KEY (\"author_id\") REFERENCES \"users\"(\"id\") ON DELETE CASCADE",
            fk.sql,
        )
    }

    @Test
    fun `foreignKeysFor carries the catalog code for its delete action`() {
        // The driver compares this against pg_constraint.confdeltype to
        // decide whether a constraint already holding the name is the
        // one the schema describes. A name match alone would let a stale
        // constraint survive silently.
        assertEquals('c', ddl.foreignKeysFor(posts).single().onDeleteCode)
        assertEquals("CASCADE", ddl.foreignKeysFor(posts).single().onDelete)
    }

    @Test
    fun `nullable FK without an explicit action defaults to SET NULL`() {
        val schema = posts.copy(
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "author_id",
                    FieldType.LONG,
                    nullable = true,
                    references = ForeignKeyRef("users", "id"),
                ),
            ),
        )

        assertTrue(
            ddl.foreignKeysFor(schema).single().sql.contains("ON DELETE SET NULL"),
            "nullability-inferred action carries over to the ALTER form",
        )
    }

    @Test
    fun `a schema with no FK columns produces no constraints`() {
        val schema = posts.copy(
            columns = listOf(ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true)),
        )
        assertEquals(emptyList(), ddl.foreignKeysFor(schema))
    }
}
