package entkt.postgres

import entkt.runtime.driver.KotlinxJsonCodec
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.JsonColumnCodec
import entkt.runtime.driver.JsonColumnMetadata
import entkt.runtime.driver.JsonMapperIds
import entkt.schema.FieldType
import entkt.types.Bytes
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

@Serializable
private data class SnapshotDocument(val tags: List<String>)

class PostgresValueCodecTest {

    private val codec = PostgresValueCodec(KotlinxJsonCodec())

    @Test
    fun `binary binds export an independent array and preserve SQL null`() {
        val value = Bytes.of(byteArrayOf(-128, -1, 0, 127))
        val bound = bind(FieldType.BYTES, value)
        val array = assertIs<ByteArray>(bound.value)

        assertEquals("setBytes", bound.method)
        assertEquals(1, bound.index)
        assertContentEquals(byteArrayOf(-128, -1, 0, 127), array)
        array[0] = 0
        assertEquals((-128).toByte(), value[0])
        assertContentEquals(byteArrayOf(), bind(FieldType.BYTES, Bytes.of(byteArrayOf())).value as ByteArray)
        assertEquals(Bound("setNull", 1, Types.BINARY), bind(FieldType.BYTES, null))
    }

    @Test
    fun `binary decode returns immutable Bytes and distinguishes empty from SQL null`() {
        for (array in listOf(byteArrayOf(-128, -1, 0, 127), byteArrayOf(), null)) {
            val expected = array?.let(Bytes::of)
            var getBytesCalled = false
            val resultSet = Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
            ) { _, method, args ->
                assertEquals("getBytes", method.name)
                assertEquals(listOf("payload"), args.toList())
                getBytesCalled = true
                array
            } as ResultSet

            val decoded = codec.decodeColumn(
                resultSet,
                "attachments",
                ColumnMetadata("payload", FieldType.BYTES, nullable = true),
            )
            if (array != null && array.isNotEmpty()) {
                array[0] = 9
            }

            assertTrue(getBytesCalled)
            assertEquals(expected, decoded)
        }
    }

    @Test
    fun `JSON snapshots use the codec copy contract even when decode is cached`() {
        val delegate = KotlinxJsonCodec()
        val sharedDecode = SnapshotDocument(mutableListOf("cached"))
        var copyCalls = 0
        val cachingCodec = object : JsonColumnCodec {
            override val id: String = JsonMapperIds.KOTLINX
            override fun validate(table: String, column: ColumnMetadata) = delegate.validate(table, column)
            override fun encode(table: String, column: ColumnMetadata, value: Any): String =
                delegate.encode(table, column, value)
            override fun decode(table: String, column: ColumnMetadata, text: String): Any = sharedDecode
            override fun copyValue(table: String, column: ColumnMetadata, value: Any): Any {
                copyCalls++
                return delegate.copyValue(table, column, value)
            }
        }
        val column = ColumnMetadata(
            name = "document",
            type = FieldType.JSON,
            nullable = false,
            json = JsonColumnMetadata(
                klass = SnapshotDocument::class,
                kType = typeOf<SnapshotDocument>(),
                typeName = SnapshotDocument::class.qualifiedName!!,
                mapper = JsonMapperIds.KOTLINX,
                kotlinxSerializer = SnapshotDocument.serializer(),
            ),
        )
        val originalTags = mutableListOf("original")
        val copied = PostgresValueCodec(cachingCodec).copyJsonValue(
            "documents",
            column,
            SnapshotDocument(originalTags),
        )

        @Suppress("UNCHECKED_CAST")
        (copied.tags as MutableList<String>) += "changed"
        assertEquals(1, copyCalls)
        assertEquals(listOf("original"), originalTags)
        assertEquals(listOf("original", "changed"), copied.tags)
    }

    @Test
    fun `date binds use LocalDate directly and SQL DATE for null`() {
        val date = LocalDate.of(2024, 2, 29)
        assertEquals(Bound("setObject", 1, date), bind(FieldType.DATE, date))
        assertEquals(Bound("setNull", 1, Types.DATE), bind(FieldType.DATE, null))
        assertFailsWith<ClassCastException> { bind(FieldType.DATE, java.time.Instant.EPOCH) }
    }

    @Test
    fun `date decode requests LocalDate and preserves SQL null`() {
        for (date in listOf(LocalDate.of(2024, 2, 29), null)) {
            var getObjectCalled = false
            val resultSet = Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
            ) { _, method, args ->
                assertEquals("getObject", method.name)
                assertEquals(listOf("starts_on", LocalDate::class.java), args.toList())
                getObjectCalled = true
                date
            } as ResultSet

            assertEquals(
                date,
                codec.decodeColumn(resultSet, "events", ColumnMetadata("starts_on", FieldType.DATE, nullable = true)),
            )
            assertTrue(getObjectCalled)
        }
    }

    @Test
    fun `integral binds accept exactly representable Number values`() {
        assertEquals(Bound("setInt", 1, 42), bind(FieldType.INT, BigDecimal("42.0")))
        assertEquals(Bound("setInt", 1, 7), bind(FieldType.INT, 7.toByte()))
        assertEquals(Bound("setLong", 1, 1L), bind(FieldType.LONG, BigInteger.ONE))
        assertEquals(Bound("setLong", 1, Long.MAX_VALUE), bind(FieldType.LONG, BigDecimal(Long.MAX_VALUE.toString())))
    }

    @Test
    fun `integral binds reject fractions and values outside the target range`() {
        val invalidInts = listOf(
            BigDecimal("1.9"),
            BigInteger.valueOf(Int.MAX_VALUE.toLong()).add(BigInteger.ONE),
            BigInteger.valueOf(Int.MIN_VALUE.toLong()).subtract(BigInteger.ONE),
            Double.NaN,
            Double.POSITIVE_INFINITY,
        )
        invalidInts.forEach { value ->
            val error = assertFailsWith<IllegalArgumentException> { bind(FieldType.INT, value) }
            assertTrue("finite whole number" in error.message.orEmpty(), error.message)
        }

        val invalidLongs = listOf(
            BigDecimal("2.5"),
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
            BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
        )
        invalidLongs.forEach { value ->
            val error = assertFailsWith<IllegalArgumentException> { bind(FieldType.LONG, value) }
            assertTrue("finite whole number" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `floating binds reject overflow and underflow without rejecting ordinary rounding`() {
        assertEquals(Bound("setFloat", 1, 1.25f), bind(FieldType.FLOAT, BigDecimal("1.25")))
        assertEquals(Bound("setDouble", 1, 0.1), bind(FieldType.DOUBLE, BigDecimal("0.1")))

        listOf(BigDecimal("1e1000"), BigDecimal("1e-1000"), Double.MAX_VALUE).forEach { value ->
            val error = assertFailsWith<IllegalArgumentException> { bind(FieldType.FLOAT, value) }
            assertTrue("overflow or underflow" in error.message.orEmpty(), error.message)
        }
        listOf(BigDecimal("1e10000"), BigDecimal("1e-10000")).forEach { value ->
            val error = assertFailsWith<IllegalArgumentException> { bind(FieldType.DOUBLE, value) }
            assertTrue("overflow or underflow" in error.message.orEmpty(), error.message)
        }

        assertEquals(
            Bound("setFloat", 1, Float.POSITIVE_INFINITY),
            bind(FieldType.FLOAT, Float.POSITIVE_INFINITY),
        )
        assertEquals(
            Bound("setDouble", 1, Double.NaN),
            bind(FieldType.DOUBLE, Double.NaN),
        )
    }

    private fun bind(type: FieldType, value: Any?): Bound {
        var bound: Bound? = null
        val statement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
            if (method.name in setOf("setInt", "setLong", "setFloat", "setDouble", "setObject", "setBytes", "setNull")) {
                bound = Bound(method.name, args[0] as Int, args[1])
            }
            null
        } as PreparedStatement

        codec.bind(statement, 1, type, value)
        return checkNotNull(bound) { "Codec did not bind $type" }
    }

    private data class Bound(
        val method: String,
        val index: Int,
        val value: Any?,
    )
}
