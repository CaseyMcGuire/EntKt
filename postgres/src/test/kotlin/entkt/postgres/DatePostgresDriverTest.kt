package entkt.postgres

import entkt.codegen.SchemaInput
import entkt.codegen.buildEntitySchemas
import entkt.migrations.NormalizedSchema
import entkt.migrations.SchemaDiffer
import entkt.schema.EntId
import entkt.schema.EntSchema
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DateDefaultRecord(defaultDate: LocalDate) :
    EntSchema("date_default_records", clientName = "dateDefaultRecords") {
    override fun id() = EntId.long()
    val requiredOn by date("required_on").default(defaultDate)
    val optionalOn by date("optional_on").nullable().default(defaultDate)
    val byOptionalOn = index("idx_date_default_records_optional_on", optionalOn)
}

class DatePostgresDriverTest {
    private val dataSource = SharedPostgres.dataSource

    private fun schema(date: LocalDate) =
        buildEntitySchemas(listOf(SchemaInput(DateDefaultRecord(date)))).single()

    private fun fresh(date: LocalDate): PostgresDriver {
        val schema = schema(date)
        val desired = NormalizedSchema.fromEntitySchemas(listOf(schema), PostgresTypeMapper())
        val creation = SchemaDiffer().diff(desired, NormalizedSchema(emptyMap()))
        val statements = creation.ops.flatMap(PostgresSqlRenderer()::render)
        // SQL defaults are migration metadata; autoDdl does not install them.
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS date_default_records")
                statements.forEach { statement.execute(it) }
            }
        }
        return PostgresDriver(dataSource).apply { register(schema) }
    }

    @Test
    fun `SQL defaults and bound values agree for leap days eras extended years and infinity`() {
        val dates = listOf(
            LocalDate.of(2024, 2, 29),
            LocalDate.of(1500, 3, 1),
            LocalDate.of(1, 1, 1),
            LocalDate.of(0, 1, 1),
            LocalDate.of(-1, 12, 31),
            LocalDate.of(10000, 1, 1),
            LocalDate.MIN,
            LocalDate.MAX,
        )
        for (date in dates) {
            val driver = fresh(date)
            val defaulted = driver.insert("date_default_records", emptyMap())
            val explicit = driver.insert("date_default_records", mapOf("required_on" to date, "optional_on" to date))
            for (row in listOf(defaulted, explicit)) {
                assertEquals(date, row["required_on"], "required value for $date")
                assertEquals(date, row["optional_on"], "nullable value for $date")
                assertEquals(row, driver.byId("date_default_records", row.getValue("id")!!))
            }

            val desired = NormalizedSchema.fromEntitySchemas(listOf(schema(date)), PostgresTypeMapper())
            val current = PostgresIntrospector(dataSource).introspect(setOf("date_default_records"))
            val column = current.tables.getValue("date_default_records").columns.single { it.name == "required_on" }
            assertEquals("date", column.sqlType)
            val diff = SchemaDiffer().diff(desired, current)
            assertTrue(diff.ops.isEmpty(), "default $date caused drift: ${diff.ops}")
            assertTrue(diff.manual.isEmpty(), "default $date caused manual drift: ${diff.manual}")
        }
    }

    @Test
    fun `SQL default applies to omission but not explicit null`() {
        val date = LocalDate.of(2024, 2, 29)
        val driver = fresh(date)
        val omitted = driver.insert("date_default_records", emptyMap())
        val explicitNull = driver.insert("date_default_records", mapOf("optional_on" to null))

        assertEquals(date, omitted["optional_on"])
        assertEquals(date, explicitNull["required_on"])
        assertNull(explicitNull["optional_on"])
        assertEquals(explicitNull, driver.byId("date_default_records", explicitNull.getValue("id")!!))
    }
}
