package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import entkt.schema.OnDelete
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val GUARD_USERS = EntitySchema(
    table = "guard_users",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true)),
    edges = emptyMap(),
)

private val GUARD_TENANTS = EntitySchema(
    table = "guard_tenants",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true)),
    edges = emptyMap(),
)

private val GUARD_POSTS = EntitySchema(
    table = "guard_posts",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata(
            "author_id",
            FieldType.LONG,
            nullable = false,
            references = ForeignKeyRef("guard_users", "id", OnDelete.CASCADE),
        ),
    ),
    edges = emptyMap(),
)

/**
 * Auto-DDL reconciles an existing foreign key against what the schema
 * asks for, rather than treating the constraint *name* as proof.
 *
 * `ADD CONSTRAINT` has no `IF NOT EXISTS`, so applying a foreign key
 * idempotently means checking the catalog first. Checking only that
 * *something* holds the name is not enough: a stale FK left over from an
 * earlier shape of the schema, or an unrelated constraint that happens
 * to collide, would make registration report success while the
 * constraint the schema actually describes was never created.
 *
 * The name is fully derived (`fk_<table>_<column>`), so a stale one is
 * the expected outcome of editing an edge and restarting against a
 * database that still has the old table.
 */
class PostgresForeignKeyGuardTest {

    private val dataSource: DataSource = SharedPostgres.dataSource

    private fun exec(vararg sql: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt -> sql.forEach { stmt.execute(it) } }
        }
    }

    private fun dropAll() =
        exec("DROP TABLE IF EXISTS guard_posts, guard_users, guard_tenants CASCADE")

    /** The rendered definition of [name] on [table], or null if absent. */
    private fun constraintDef(table: String, name: String): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                    "WHERE conname = ? AND conrelid = to_regclass(?)",
            ).use { stmt ->
                stmt.setString(1, name)
                stmt.setString(2, table)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun registerAll() =
        PostgresDriver(dataSource, autoDdl = true).registerAll(listOf(GUARD_POSTS, GUARD_USERS, GUARD_TENANTS))

    @Test
    fun `creates the foreign key on a clean database`() {
        dropAll()
        registerAll()

        assertEquals(
            "FOREIGN KEY (author_id) REFERENCES guard_users(id) ON DELETE CASCADE",
            constraintDef("guard_posts", "fk_guard_posts_author_id"),
        )
    }

    @Test
    fun `re-registering an already-correct foreign key is a no-op`() {
        dropAll()
        registerAll()
        registerAll()

        assertEquals(
            "FOREIGN KEY (author_id) REFERENCES guard_users(id) ON DELETE CASCADE",
            constraintDef("guard_posts", "fk_guard_posts_author_id"),
        )
    }

    @Test
    fun `a stale foreign key pointing at the wrong table fails loudly`() {
        dropAll()
        registerAll()
        // Simulate an edge whose target was changed: the old constraint
        // survives under the derived name, pointing somewhere else.
        exec(
            "ALTER TABLE guard_posts DROP CONSTRAINT fk_guard_posts_author_id",
            "ALTER TABLE guard_posts ADD CONSTRAINT fk_guard_posts_author_id " +
                "FOREIGN KEY (author_id) REFERENCES guard_tenants(id) ON DELETE CASCADE",
        )

        val ex = assertFailsWith<IllegalStateException> { registerAll() }
        assertTrue(ex.message!!.contains("fk_guard_posts_author_id"), "names the constraint; got: ${ex.message}")
        assertTrue(ex.message!!.contains("guard_users"), "states what the schema wants; got: ${ex.message}")
        assertTrue(ex.message!!.contains("guard_tenants"), "states what is there; got: ${ex.message}")
    }

    @Test
    fun `a stale foreign key with the wrong delete action fails loudly`() {
        dropAll()
        registerAll()
        // Changing `onDelete` on an edge is the likeliest way to end up
        // here — the name doesn't change, so nothing else would notice.
        exec(
            "ALTER TABLE guard_posts DROP CONSTRAINT fk_guard_posts_author_id",
            "ALTER TABLE guard_posts ADD CONSTRAINT fk_guard_posts_author_id " +
                "FOREIGN KEY (author_id) REFERENCES guard_users(id) ON DELETE RESTRICT",
        )

        val ex = assertFailsWith<IllegalStateException> { registerAll() }
        assertTrue(ex.message!!.contains("CASCADE"), "states the wanted action; got: ${ex.message}")
        assertTrue(ex.message!!.contains("RESTRICT"), "states the existing action; got: ${ex.message}")
    }

    @Test
    fun `a same-named FK with an ON UPDATE action fails loudly`() {
        dropAll()
        registerAll()
        // Everything the earlier checks compare lines up — same columns,
        // same target, same ON DELETE — but this one also cascades on
        // parent-key updates, which the schema never asked for.
        exec(
            "ALTER TABLE guard_posts DROP CONSTRAINT fk_guard_posts_author_id",
            "ALTER TABLE guard_posts ADD CONSTRAINT fk_guard_posts_author_id " +
                "FOREIGN KEY (author_id) REFERENCES guard_users(id) ON DELETE CASCADE ON UPDATE CASCADE",
        )

        val ex = assertFailsWith<IllegalStateException> { registerAll() }
        assertTrue(ex.message!!.contains("ON UPDATE CASCADE"), "surfaces the extra action; got: ${ex.message}")
    }

    @Test
    fun `a same-named NOT VALID FK fails loudly`() {
        dropAll()
        registerAll()
        // NOT VALID enforces new rows but was never checked against the
        // rows already in the table — accepting it would report success
        // for referential integrity that was never actually established.
        exec(
            "ALTER TABLE guard_posts DROP CONSTRAINT fk_guard_posts_author_id",
            "ALTER TABLE guard_posts ADD CONSTRAINT fk_guard_posts_author_id " +
                "FOREIGN KEY (author_id) REFERENCES guard_users(id) ON DELETE CASCADE NOT VALID",
        )

        val ex = assertFailsWith<IllegalStateException> { registerAll() }
        assertTrue(ex.message!!.contains("NOT VALID"), "surfaces the unvalidated state; got: ${ex.message}")
    }

    @Test
    fun `a same-named DEFERRABLE FK fails loudly`() {
        dropAll()
        registerAll()
        // Deferring moves enforcement to commit time, so violations that
        // would have failed immediately survive until the transaction ends.
        exec(
            "ALTER TABLE guard_posts DROP CONSTRAINT fk_guard_posts_author_id",
            "ALTER TABLE guard_posts ADD CONSTRAINT fk_guard_posts_author_id " +
                "FOREIGN KEY (author_id) REFERENCES guard_users(id) ON DELETE CASCADE " +
                "DEFERRABLE INITIALLY DEFERRED",
        )

        val ex = assertFailsWith<IllegalStateException> { registerAll() }
        assertTrue(ex.message!!.contains("DEFERRABLE"), "surfaces the deferrability; got: ${ex.message}")
    }

    @Test
    fun `a same-named MATCH FULL FK fails loudly`() {
        dropAll()
        registerAll()
        // Inert for a single-column FK, but the rule is "exactly what
        // this schema would create", so it's still a mismatch.
        exec(
            "ALTER TABLE guard_posts DROP CONSTRAINT fk_guard_posts_author_id",
            "ALTER TABLE guard_posts ADD CONSTRAINT fk_guard_posts_author_id " +
                "FOREIGN KEY (author_id) REFERENCES guard_users(id) MATCH FULL ON DELETE CASCADE",
        )

        val ex = assertFailsWith<IllegalStateException> { registerAll() }
        assertTrue(ex.message!!.contains("MATCH FULL"), "surfaces the match type; got: ${ex.message}")
    }

    @Test
    fun `a non-foreign-key constraint holding the name fails loudly`() {
        dropAll()
        registerAll()
        // The pathological case: something that isn't a foreign key at
        // all occupies the derived name. Treating the name as proof
        // would leave the table with no referential integrity while
        // registration reported success.
        exec(
            "ALTER TABLE guard_posts DROP CONSTRAINT fk_guard_posts_author_id",
            "ALTER TABLE guard_posts ADD CONSTRAINT fk_guard_posts_author_id CHECK (author_id > 0)",
        )

        val ex = assertFailsWith<IllegalStateException> { registerAll() }
        assertTrue(ex.message!!.contains("fk_guard_posts_author_id"), "names the constraint; got: ${ex.message}")
    }
}
