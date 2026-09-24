package entkt.postgres

import entkt.codegen.SchemaInput
import entkt.codegen.buildEntitySchemas
import entkt.query.Column
import entkt.query.NullableColumn
import entkt.query.isNull
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.types.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class BinaryValueRecord : EntSchema("binary_value_records", clientName = "binaryValueRecords") {
    override fun id() = EntId.long()
    val payload by bytes("payload")
    val thumbnail by bytes("thumbnail").nullable()
}

class BytesPostgresDriverTest {
    private fun fresh(): PostgresDriver {
        val dataSource = SharedPostgres.dataSource
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS binary_value_records")
            }
        }
        val schema = buildEntitySchemas(listOf(SchemaInput(BinaryValueRecord()))).single()
        return PostgresDriver(dataSource, autoDdl = true).apply { register(schema) }
    }

    @Test
    fun `binary values round trip all bytes and preserve empty versus null`() {
        val driver = fresh()
        val table = "binary_value_records"
        val full = Bytes.of(ByteArray(256) { it.toByte() })
        val empty = Bytes.of(byteArrayOf())
        val created = driver.insert(table, mapOf("payload" to full, "thumbnail" to null))
        val id = created.getValue("id")!!

        assertEquals(full, created["payload"])
        assertNull(created["thumbnail"])
        assertEquals(created, driver.byId(table, id))

        val updated = driver.update(table, id, mapOf("payload" to empty, "thumbnail" to full))
        assertEquals(empty, updated?.get("payload"))
        assertEquals(full, updated?.get("thumbnail"))
        assertEquals(updated, driver.byId(table, id))

        val cleared = driver.update(table, id, mapOf("thumbnail" to null))
        assertEquals(empty, cleared?.get("payload"))
        assertNull(cleared?.get("thumbnail"))
        assertEquals(cleared, driver.byId(table, id))
    }

    @Test
    fun `bulk writes and binary predicates use Bytes at the driver boundary`() {
        val driver = fresh()
        val table = "binary_value_records"
        val first = Bytes.of(byteArrayOf(0, -1))
        val second = Bytes.of(byteArrayOf(1, -128))
        val created = driver.insertMany(
            table,
            listOf(
                mapOf("payload" to first, "thumbnail" to null),
                mapOf("payload" to second, "thumbnail" to Bytes.of(byteArrayOf())),
            ),
        )
        val payload = Column<Any, Bytes>("payload")
        val thumbnail = NullableColumn<Any, Bytes>("thumbnail")

        val equal = driver.query(table, listOf(payload eq Bytes.of(byteArrayOf(0, -1))), emptyList(), null, null)
        assertEquals(listOf(created[0]), equal)
        val members = driver.query(table, listOf(payload `in` listOf(first, second)), emptyList(), null, null)
        assertEquals(created.toSet(), members.toSet())
        val missing = driver.query(table, listOf(thumbnail.isNull()), emptyList(), null, null)
        assertEquals(listOf(created[0]), missing)
    }
}
