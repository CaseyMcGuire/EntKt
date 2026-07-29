package entkt.postgres

import entkt.migrations.FkAction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FK introspection reads `pg_constraint`, not the `information_schema`
 * views: the views join by constraint name (unique per *table*, not
 * per schema) and carry no ordinal pairing, which cross-paired
 * composite FKs into invented single-column FKs and let same-named
 * constraints on other tables contaminate results. These tests pin the
 * pg_constraint behavior against a real server.
 */
class PostgresFkIntrospectionTest {

    private val tables = listOf(
        "fkintro_child", "fkintro_parent", "fkintro_other_child", "fkintro_other_parent",
    )

    @BeforeTest
    fun resetTables() {
        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                for (t in tables) st.execute("DROP TABLE IF EXISTS \"$t\" CASCADE")
            }
        }
    }

    private fun exec(vararg sql: String) {
        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { st -> for (s in sql) st.execute(s) }
        }
    }

    private fun introspect(table: String) =
        PostgresIntrospector(SharedPostgres.dataSource)
            .introspect(tables.toSet())
            .tables
            .getValue(table)

    @Test
    fun `composite FK pairs columns ordinally instead of cross-producting`() {
        exec(
            "CREATE TABLE fkintro_parent (a integer NOT NULL, b integer NOT NULL, PRIMARY KEY (a, b))",
            "CREATE TABLE fkintro_child (id serial PRIMARY KEY, x integer NOT NULL, y integer NOT NULL, " +
                "CONSTRAINT fk_composite FOREIGN KEY (x, y) REFERENCES fkintro_parent (a, b))",
        )

        val fks = introspect("fkintro_child").foreignKeys
        assertEquals(1, fks.size, "one constraint, not an m×n explosion; got $fks")
        assertEquals(listOf("x", "y"), fks[0].columns)
        assertEquals(listOf("a", "b"), fks[0].targetColumns)
        assertEquals("fkintro_parent", fks[0].targetTable)
        assertEquals("fk_composite", fks[0].constraintName)
    }

    @Test
    fun `same-named constraints on other tables do not contaminate results`() {
        exec(
            "CREATE TABLE fkintro_parent (id serial PRIMARY KEY)",
            "CREATE TABLE fkintro_other_parent (id serial PRIMARY KEY)",
            "CREATE TABLE fkintro_child (id serial PRIMARY KEY, parent_id integer NOT NULL, " +
                "CONSTRAINT fk_shared_name FOREIGN KEY (parent_id) REFERENCES fkintro_parent (id))",
            "CREATE TABLE fkintro_other_child (id serial PRIMARY KEY, other_id integer NOT NULL, " +
                "CONSTRAINT fk_shared_name FOREIGN KEY (other_id) REFERENCES fkintro_other_parent (id))",
        )

        val fks = introspect("fkintro_child").foreignKeys
        assertEquals(1, fks.size, "the other table's same-named FK must not leak in; got $fks")
        assertEquals(listOf("parent_id"), fks[0].columns)
        assertEquals("fkintro_parent", fks[0].targetTable)
    }

    @Test
    fun `constraint semantics round-trip exactly`() {
        exec(
            "CREATE TABLE fkintro_parent (id integer PRIMARY KEY DEFAULT 0)",
            "CREATE TABLE fkintro_child (id serial PRIMARY KEY, parent_id integer NOT NULL DEFAULT 0, " +
                "CONSTRAINT fk_exotic FOREIGN KEY (parent_id) REFERENCES fkintro_parent (id) " +
                "ON DELETE SET DEFAULT ON UPDATE CASCADE DEFERRABLE)",
            "INSERT INTO fkintro_parent (id) VALUES (0)",
            "ALTER TABLE fkintro_child ADD CONSTRAINT fk_not_valid " +
                "FOREIGN KEY (parent_id) REFERENCES fkintro_parent (id) NOT VALID",
        )

        val fks = introspect("fkintro_child").foreignKeys.associateBy { it.constraintName }

        val exotic = fks.getValue("fk_exotic")
        assertEquals(FkAction.SET_DEFAULT, exotic.onDelete, "SET DEFAULT must survive, not map to an inferred action")
        assertEquals(FkAction.CASCADE, exotic.onUpdate)
        assertTrue(exotic.deferrable)
        assertFalse(exotic.initiallyDeferred)
        assertTrue(exotic.validated)

        val notValid = fks.getValue("fk_not_valid")
        assertFalse(notValid.validated, "NOT VALID must be visible to the differ")
        assertEquals(FkAction.NO_ACTION, notValid.onDelete)
    }
}
