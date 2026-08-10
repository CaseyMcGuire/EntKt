package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.IndexMetadata
import entkt.schema.FieldType
import entkt.schema.OnDelete
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins introspection fidelity against catalog shapes hand-written DDL
 * can produce but the entkt DSL cannot:
 *
 *  - `varchar(n)` must keep its length modifier (a write longer than n
 *    fails at runtime, so adopting it as unconstrained `text` hides a
 *    live constraint); modifier-less `character varying` still folds
 *    into `text`.
 *  - `INCLUDE` columns are storage, not key columns — reading them as
 *    key columns made `UNIQUE(a) INCLUDE(b)` satisfy a declared
 *    `UNIQUE(a,b)` while enforcing uniqueness on `a` alone.
 *  - An INVALID index (failed `CREATE INDEX CONCURRENTLY`) is ignored
 *    by the planner and enforces nothing; it must never satisfy a
 *    declaration.
 *  - A plain hand-written `REFERENCES` (server default `NO ACTION`)
 *    is behaviorally identical to the immediate `RESTRICT` a required
 *    edge resolves to; auto-DDL must accept it like the differ does.
 */
class PostgresIntrospectionFidelityTest {

    private val dataSource: DataSource = SharedPostgres.dataSource

    private fun exec(vararg sql: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt -> sql.forEach { stmt.execute(it) } }
        }
    }

    @BeforeTest
    fun dropAll() = exec(
        "DROP TABLE IF EXISTS fidelity_varchar, fidelity_include, fidelity_invalid, " +
            "fidelity_ts, fidelity_expr, fidelity_pkidx, fidelity_fk_child, fidelity_fk_parent, " +
            "fidelity_nnd, fidelity_generated, fidelity_pk_shared_b, fidelity_pk_shared_a CASCADE",
    )

    private fun introspect(table: String) =
        PostgresIntrospector(dataSource).introspect(setOf(table)).tables.getValue(table)

    // ---------- varchar(n) fidelity ----------

    @Test
    fun `varchar length modifiers survive introspection`() {
        exec(
            "CREATE TABLE fidelity_varchar (id bigserial PRIMARY KEY, " +
                "capped varchar(5) NOT NULL, uncapped varchar NOT NULL, plain text NOT NULL)",
        )

        val columns = introspect("fidelity_varchar").columns.associateBy { it.name }

        assertEquals("character varying(5)", columns.getValue("capped").sqlType)
        // Modifier-less varchar is unconstrained — same storage contract
        // as text, so it still folds.
        assertEquals("text", columns.getValue("uncapped").sqlType)
        assertEquals("text", columns.getValue("plain").sqlType)
    }

    @Test
    fun `auto-DDL rejects a pre-existing varchar-capped column for a text schema`() {
        exec(
            "CREATE TABLE fidelity_varchar (id bigserial PRIMARY KEY, capped varchar(5) NOT NULL)",
        )
        val schema = EntitySchema(
            table = "fidelity_varchar",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("capped", FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )

        val error = assertFailsWith<IllegalStateException> {
            PostgresDriver(dataSource, autoDdl = true).register(schema)
        }
        assertTrue(
            "character varying(5)" in error.message.orEmpty(),
            "drift message should name the live capped type: ${error.message}",
        )
    }

    @Test
    fun `default timestamp precision folds while a real precision survives`() {
        // Hibernate-style DDL spells the default precision out:
        // timestamp(6) with time zone IS timestamptz and must not
        // drift; timestamp(3) truncates and is a real difference.
        exec(
            "CREATE TABLE fidelity_ts (id bigserial PRIMARY KEY, " +
                "created_at timestamp(6) with time zone NOT NULL, " +
                "coarse timestamp(3) with time zone NOT NULL)",
        )

        val columns = introspect("fidelity_ts").columns.associateBy { it.name }

        assertEquals("timestamptz", columns.getValue("created_at").sqlType)
        assertEquals("timestamp(3) with time zone", columns.getValue("coarse").sqlType)
    }

    // ---------- INCLUDE columns ----------

    @Test
    fun `INCLUDE columns are not read as index key columns`() {
        exec(
            "CREATE TABLE fidelity_include (id bigserial PRIMARY KEY, a int NOT NULL, b int NOT NULL)",
            "CREATE UNIQUE INDEX fidelity_include_a ON fidelity_include (a) INCLUDE (b)",
        )

        val index = introspect("fidelity_include").indexes.single { it.name == "fidelity_include_a" }

        // The live index enforces uniqueness on `a` alone; reading
        // [a, b] would let it satisfy a declared UNIQUE(a, b).
        assertEquals(listOf("a"), index.columns)
        assertTrue(index.unique)
        assertEquals(listOf("b"), index.include)
    }

    @Test
    fun `expression key slots pad instead of collapsing onto plain columns`() {
        // UNIQUE (a, lower(c)) INCLUDE (b) does NOT enforce uniqueness
        // on `a` alone — dropping the expression slot would let it
        // silently satisfy a declared unique index on (a).
        exec(
            "CREATE TABLE fidelity_expr (id bigserial PRIMARY KEY, a int NOT NULL, b int NOT NULL, c text NOT NULL)",
            "CREATE UNIQUE INDEX fidelity_expr_mixed ON fidelity_expr (a, lower(c)) INCLUDE (b)",
        )

        val index = introspect("fidelity_expr").indexes.single { it.name == "fidelity_expr_mixed" }

        assertEquals(listOf("a", "<expression>"), index.columns)
    }

    @Test
    fun `a secondary index on the primary-key column stays visible`() {
        // NOT indisprimary already excludes the PK's own index; hiding
        // secondary indexes on the id column let an invalid (or merely
        // undeclared) one escape drift detection entirely.
        exec(
            "CREATE TABLE fidelity_pkidx (id bigserial PRIMARY KEY, body text NOT NULL)",
            "CREATE INDEX fidelity_pkidx_id ON fidelity_pkidx (id)",
        )

        val index = introspect("fidelity_pkidx").indexes.single()

        assertEquals("fidelity_pkidx_id", index.name)
        assertEquals(listOf("id"), index.columns)
    }

    // ---------- INVALID indexes ----------

    /**
     * Seed duplicates, then let `CREATE UNIQUE INDEX CONCURRENTLY`
     * fail on them — PostgreSQL keeps the half-built index in the
     * catalog marked `indisvalid = false`.
     */
    private fun leaveInvalidIndexBehind() {
        exec(
            "CREATE TABLE fidelity_invalid (id bigserial PRIMARY KEY, email text NOT NULL)",
            "INSERT INTO fidelity_invalid (email) VALUES ('dup'), ('dup')",
        )
        try {
            exec("CREATE UNIQUE INDEX CONCURRENTLY idx_fidelity_invalid_email_unique ON fidelity_invalid (email)")
            error("concurrent unique build over duplicates should fail")
        } catch (expected: SQLException) {
            // the invalid index stays behind
        }
    }

    @Test
    fun `an invalid index introspects as invalid`() {
        leaveInvalidIndexBehind()

        val index = introspect("fidelity_invalid").indexes.single()

        assertFalse(index.valid, "failed concurrent build must not read as a live index")
    }

    @Test
    fun `auto-DDL refuses to accept an invalid index as the declared one`() {
        leaveInvalidIndexBehind()
        val schema = EntitySchema(
            table = "fidelity_invalid",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("email", FieldType.STRING, nullable = false, unique = true),
            ),
            edges = emptyMap(),
        )

        val error = assertFailsWith<IllegalStateException> {
            PostgresDriver(dataSource, autoDdl = true).register(schema)
        }
        assertTrue(
            "INVALID" in error.message.orEmpty(),
            "failure should name the invalid index: ${error.message}",
        )
    }

    // ---------- NO ACTION ≡ RESTRICT ----------

    @Test
    fun `auto-DDL accepts a hand-written NO ACTION FK for a required RESTRICT edge`() {
        // Plain REFERENCES defaults to ON DELETE NO ACTION; under the
        // derived constraint name it must reconcile with the RESTRICT a
        // required edge resolves to — migrate validate already treats
        // the two as one constraint.
        exec(
            "CREATE TABLE fidelity_fk_parent (id bigserial PRIMARY KEY)",
            "CREATE TABLE fidelity_fk_child (id bigserial PRIMARY KEY, " +
                "parent_id bigint NOT NULL, " +
                "CONSTRAINT fk_fidelity_fk_child_parent_id FOREIGN KEY (parent_id) " +
                "REFERENCES fidelity_fk_parent (id))",
        )
        val parent = EntitySchema(
            table = "fidelity_fk_parent",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true)),
            edges = emptyMap(),
        )
        val child = EntitySchema(
            table = "fidelity_fk_child",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id",
                    FieldType.LONG,
                    nullable = false,
                    references = ForeignKeyRef("fidelity_fk_parent", "id", OnDelete.RESTRICT),
                ),
            ),
            edges = emptyMap(),
        )

        PostgresDriver(dataSource, autoDdl = true).registerAll(listOf(parent, child))
    }

    // ---------- UNIQUE NULLS NOT DISTINCT fidelity ----------

    @Test
    fun `NULLS NOT DISTINCT uniqueness survives introspection`() {
        // It permits only one NULL per key where ordinary uniqueness
        // permits any number — reading it as plain unique would validate
        // a schema whose second NULL insert fails at runtime.
        exec(
            "CREATE TABLE fidelity_nnd (id serial PRIMARY KEY, code text, tag text)",
            "CREATE UNIQUE INDEX idx_fidelity_nnd_code ON fidelity_nnd (code) NULLS NOT DISTINCT",
            "CREATE UNIQUE INDEX idx_fidelity_nnd_tag ON fidelity_nnd (tag)",
        )
        val table = introspect("fidelity_nnd")

        val code = table.indexes.single { it.name == "idx_fidelity_nnd_code" }
        assertTrue(code.unique)
        assertTrue(code.nullsNotDistinct, "NULLS NOT DISTINCT must survive introspection")
        val tag = table.indexes.single { it.name == "idx_fidelity_nnd_tag" }
        assertFalse(tag.nullsNotDistinct, "ordinary unique index must not read as NULLS NOT DISTINCT")
    }

    // ---------- stored generated column fidelity ----------

    @Test
    fun `stored generated columns surface their expression`() {
        // PostgreSQL forbids supplying a value for a generated column, so
        // it must never introspect as an ordinary writable column that
        // could silently match a declared field.
        exec(
            "CREATE TABLE fidelity_generated (id serial PRIMARY KEY, a integer NOT NULL, " +
                "doubled integer GENERATED ALWAYS AS (a * 2) STORED)",
        )
        val table = introspect("fidelity_generated")

        val doubled = table.columns.single { it.name == "doubled" }
        assertTrue(
            doubled.generatedAs.orEmpty().contains("a * 2"),
            "generated column must carry its expression, got: ${doubled.generatedAs}",
        )
        assertEquals(null, table.columns.single { it.name == "a" }.generatedAs)
    }

    // ---------- PK constraint-name isolation ----------

    @Test
    fun `a same-named constraint on another table does not contaminate primary keys`() {
        // Constraint names are only unique per table: an FK elsewhere may
        // share a PK's constraint name (PK/UNIQUE names are index-backed
        // and schema-unique, FK names are not). The key_column_usage join
        // must not pull that constraint's columns into this table's PK.
        exec(
            "CREATE TABLE fidelity_pk_shared_a (id integer, tenant_id integer, " +
                "CONSTRAINT fidelity_shared_name PRIMARY KEY (id))",
            "CREATE TABLE fidelity_pk_shared_b (id integer PRIMARY KEY, tenant_id integer, " +
                "CONSTRAINT fidelity_shared_name FOREIGN KEY (tenant_id) " +
                "REFERENCES fidelity_pk_shared_a (id))",
        )
        val table = introspect("fidelity_pk_shared_a")

        assertTrue(table.columns.single { it.name == "id" }.primaryKey)
        assertFalse(
            table.columns.single { it.name == "tenant_id" }.primaryKey,
            "another table's same-named FK constraint must not mark tenant_id as PK",
        )
    }
}
