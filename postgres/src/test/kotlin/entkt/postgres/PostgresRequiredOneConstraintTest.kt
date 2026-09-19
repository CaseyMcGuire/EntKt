package entkt.postgres

import entkt.migrations.MigrationOp
import entkt.migrations.NormalizedSchema
import entkt.migrations.SchemaDiffer
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.RequiredOneConstraint
import entkt.runtime.result.TransactionFailureState
import entkt.schema.FieldType
import entkt.schema.OnDelete
import org.postgresql.util.PSQLException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresRequiredOneConstraintTest {
    private val dataSource = SharedPostgres.dataSource

    private val users = EntitySchema(
        "required_users", "id", IdStrategy.EXPLICIT,
        listOf(ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true)),
        emptyMap(),
        requiredOneConstraints = listOf(
            RequiredOneConstraint("profile", "required_profiles", "owner_id"),
            RequiredOneConstraint("settings", "required_settings", "user_id"),
        ),
    )

    private fun child(table: String, column: String) = EntitySchema(
        table, "id", IdStrategy.EXPLICIT,
        listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata(
                column, FieldType.LONG, nullable = false, unique = true,
                references = ForeignKeyRef(users.table, "id", OnDelete.CASCADE),
            ),
        ),
        emptyMap(),
    )

    private val profiles = child("required_profiles", "owner_id")
    private val settings = child("required_settings", "user_id")
    private val schemas = listOf(users, profiles, settings)

    private fun execute(vararg statements: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { statement.execute(it) }
            }
        }
    }

    @BeforeTest
    fun resetTables() {
        execute("DROP TABLE IF EXISTS required_profiles, required_settings, required_users CASCADE")
    }

    private fun registeredDriver(): PostgresDriver =
        PostgresDriver(dataSource, autoDdl = true).also { it.registerAll(schemas) }

    private fun createUser(driver: DatabaseDriver, id: Long = 1L) {
        assertIs<DriverTransactionResult.Success<*>>(driver.withTransaction { tx ->
            // Transaction-scoped generated clients register the same metadata
            // and repositories check the capability on the transaction driver.
            tx.registerAll(schemas)
            tx.checkRequiredOneConstraintsSupported(users)
            tx.insert(users.table, mapOf("id" to id))
            tx.insert(profiles.table, mapOf("id" to id, "owner_id" to id))
            tx.insert(settings.table, mapOf("id" to id, "user_id" to id))
        })
    }

    private fun assertConstraintRejection(result: DriverTransactionResult<*>) {
        val failure = assertIs<DriverTransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.NotCommitted, failure.transactionState)
        assertEquals("23503", assertIs<PSQLException>(failure.exception).sqlState)
    }

    private fun assertNoDrift() {
        val desired = NormalizedSchema.fromEntitySchemas(schemas, PostgresTypeMapper())
        val actual = PostgresIntrospector(dataSource).introspect(schemas.map { it.table }.toSet())
        val diff = SchemaDiffer().diff(desired, actual)
        assertTrue(diff.ops.isEmpty(), "unexpected automatic operations: ${diff.ops}")
        assertTrue(diff.manual.isEmpty(), "unexpected manual operations: ${diff.manual}")
    }

    @Test
    fun `multiple required relationships register idempotently in either table order`() {
        registeredDriver()
        PostgresDriver(dataSource, autoDdl = true).registerAll(schemas.reversed())
        assertNoDrift()
    }

    @Test
    fun `migration DDL creates the unique targets before adding reverse constraints`() {
        val desired = NormalizedSchema.fromEntitySchemas(schemas, PostgresTypeMapper())
        val operations = SchemaDiffer().diff(desired, NormalizedSchema(emptyMap())).ops
        val firstForeignKey = operations.indexOfFirst { it is MigrationOp.AddForeignKey }
        assertTrue(operations.indexOfLast { it is MigrationOp.AddIndex } < firstForeignKey)
        execute(*operations.flatMap { PostgresSqlRenderer().render(it) }.toTypedArray())

        assertNoDrift()
        // The migration and auto-DDL paths must recognize each other's constraints.
        createUser(registeredDriver())
    }

    @Test
    fun `user and required children can be created in one transaction`() {
        val driver = registeredDriver()
        createUser(driver)
        assertNotNull(driver.byId(users.table, 1L))
        assertNotNull(driver.byId(profiles.table, 1L))
        assertNotNull(driver.byId(settings.table, 1L))
    }

    @Test
    fun `missing even one required child rejects commit and rolls back all writes`() {
        val driver = registeredDriver()
        var blockCompleted = false
        val result = driver.withTransaction { tx ->
            tx.insert(users.table, mapOf("id" to 1L))
            tx.insert(profiles.table, mapOf("id" to 1L, "owner_id" to 1L))
            blockCompleted = true
        }
        assertTrue(blockCompleted, "the presence check must be deferred until commit")
        assertConstraintRejection(result)
        assertNull(driver.byId(users.table, 1L))
        assertNull(driver.byId(profiles.table, 1L))
    }

    @Test
    fun `autocommit cannot leave a user without its required children`() {
        val driver = registeredDriver()
        val error = assertFailsWith<PSQLException> { driver.insert(users.table, mapOf("id" to 1L)) }
        assertEquals("23503", error.sqlState)
        assertNull(driver.byId(users.table, 1L))
    }

    @Test
    fun `deleting only a required child rejects commit`() {
        val driver = registeredDriver()
        createUser(driver)
        assertConstraintRejection(driver.withTransaction { tx -> tx.delete(profiles.table, 1L) })
        assertNotNull(driver.byId(profiles.table, 1L))
    }

    @Test
    fun `a required child can be replaced inside one transaction`() {
        val driver = registeredDriver()
        createUser(driver)
        assertIs<DriverTransactionResult.Success<*>>(driver.withTransaction { tx ->
            tx.delete(profiles.table, 1L)
            tx.insert(profiles.table, mapOf("id" to 2L, "owner_id" to 1L))
        })
        assertNull(driver.byId(profiles.table, 1L))
        assertEquals(1L, driver.byId(profiles.table, 2L)?.get("owner_id"))
    }

    @Test
    fun `reassigning a child cannot leave its previous owner without one`() {
        val driver = registeredDriver()
        createUser(driver, 1L)
        createUser(driver, 2L)
        assertConstraintRejection(driver.withTransaction { tx ->
            tx.delete(profiles.table, 1L)
            tx.update(profiles.table, 2L, mapOf("owner_id" to 1L))
        })
        assertEquals(1L, driver.byId(profiles.table, 1L)?.get("owner_id"))
        assertEquals(2L, driver.byId(profiles.table, 2L)?.get("owner_id"))
    }

    @Test
    fun `deleting the owner and cascading its children satisfies requiredness`() {
        val driver = registeredDriver()
        createUser(driver)
        assertTrue(driver.delete(users.table, 1L))
        assertNull(driver.byId(users.table, 1L))
        assertNull(driver.byId(profiles.table, 1L))
        assertNull(driver.byId(settings.table, 1L))
    }

    @Test
    fun `an equivalent constraint under another name is not duplicated`() {
        registeredDriver()
        execute(
            "ALTER TABLE required_users RENAME CONSTRAINT required_one_required_users_profile TO custom_required_profile",
        )
        registeredDriver()
        assertNoDrift()
    }

    @Test
    fun `deferred RESTRICT is not accepted in place of deferred NO ACTION`() {
        registeredDriver()
        execute(
            "ALTER TABLE required_users DROP CONSTRAINT required_one_required_users_profile",
            "ALTER TABLE required_users ADD CONSTRAINT required_one_required_users_profile " +
                "FOREIGN KEY (id) REFERENCES required_profiles(owner_id) ON DELETE RESTRICT " +
                "DEFERRABLE INITIALLY DEFERRED",
        )
        val error = assertFailsWith<IllegalStateException> { registeredDriver() }
        assertContains(error.message!!, "ON DELETE RESTRICT")
        assertContains(error.message!!, "ON DELETE NO ACTION")
    }

    @Test
    fun `an immediate constraint under another name cannot mask the deferred requirement`() {
        registeredDriver()
        execute(
            "ALTER TABLE required_users DROP CONSTRAINT required_one_required_users_profile",
            "ALTER TABLE required_users ADD CONSTRAINT custom_required_profile " +
                "FOREIGN KEY (id) REFERENCES required_profiles(owner_id) ON DELETE NO ACTION",
        )
        val error = assertFailsWith<IllegalStateException> { registeredDriver() }
        assertContains(error.message!!, "DEFERRABLE INITIALLY DEFERRED")
    }
}
