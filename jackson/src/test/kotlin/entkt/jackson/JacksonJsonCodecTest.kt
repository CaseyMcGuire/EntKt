package entkt.jackson

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.JsonColumnMetadata
import entkt.runtime.driver.JsonMapperIds
import entkt.schema.FieldType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Plain Kotlin data classes — deliberately NOT @Serializable. The whole point
// of the Jackson codec is round-tripping without kotlinx.serialization.
data class Rect(val page: Int, val x: Double, val y: Double)

class JacksonJsonCodecTest {

    private val codec = JacksonJsonCodec(jacksonObjectMapper())

    private fun jsonColumn(name: String, meta: JsonColumnMetadata): ColumnMetadata =
        ColumnMetadata(name, FieldType.JSON, nullable = true, json = meta)

    private val rectColumn = jsonColumn(
        "rect",
        JsonColumnMetadata(
            klass = Rect::class,
            kType = typeOf<Rect>(),
            typeName = "entkt.jackson.Rect",
            mapper = JsonMapperIds.JACKSON,
        ),
    )

    private val rectsColumn = jsonColumn(
        "rects",
        JsonColumnMetadata(
            klass = List::class,
            kType = typeOf<List<Rect>>(),
            typeName = "kotlin.collections.List<entkt.jackson.Rect>",
            mapper = JsonMapperIds.JACKSON,
        ),
    )

    @Test
    fun `round-trips a plain data class without kotlinx serialization`() {
        val rect = Rect(1, 0.25, 0.75)
        val text = codec.encode("docs", rectColumn, rect)
        assertEquals(rect, codec.decode("docs", rectColumn, text))
    }

    @Test
    fun `round-trips a generic List with typed elements, not maps`() {
        val rects = listOf(Rect(1, 0.1, 0.2), Rect(2, 0.3, 0.4))
        val text = codec.encode("docs", rectsColumn, rects)
        val back = codec.decode("docs", rectsColumn, text)
        // assertEquals against data classes proves typed elements came back —
        // the raw-JavaType failure mode would decode List<LinkedHashMap>.
        assertEquals(rects, back)
    }

    @Test
    fun `validate accepts a mappable column`() {
        codec.validate("docs", rectsColumn)
    }

    @Test
    fun `a wrong element type fails at encode, not at a later read`() {
        // listOf("nope") passes the driver's erased List::class check; the
        // codec writes through the DECLARED List<Rect> type, so the mismatch
        // fails here — matching the kotlinx codec's write-time strictness —
        // instead of persisting and poisoning a later decode.
        val ex = assertFailsWith<IllegalStateException> {
            codec.encode("docs", rectsColumn, listOf("nope"))
        }
        assertTrue("docs.rects" in ex.message!!, ex.message ?: "")
        assertTrue("List<entkt.jackson.Rect>" in ex.message!!, ex.message ?: "")
        assertTrue(ex.cause != null, "original Jackson failure preserved as the cause")
    }

    @Test
    fun `decode failure names the table, column, and expected type`() {
        val ex = assertFailsWith<IllegalStateException> {
            codec.decode("docs", rectsColumn, """[{"page": "not-an-int and missing fields"}]""")
        }
        assertTrue("docs.rects" in ex.message!!, ex.message ?: "")
        assertTrue("List<entkt.jackson.Rect>" in ex.message!!, ex.message ?: "")
        assertTrue(ex.cause != null, "original Jackson exception preserved as cause")
    }

    @Test
    fun `encode failure names the table, column, and expected type`() {
        // A direct self-reference cycle is rejected by Jackson at write time
        // ("Direct self-reference leading to cycle").
        class Node { @Suppress("unused") var next: Node? = null }
        val cyclic = Node().also { it.next = it }
        val column = jsonColumn(
            "meta",
            JsonColumnMetadata(
                klass = Any::class,
                kType = typeOf<Any>(),
                typeName = "Node",
                mapper = JsonMapperIds.JACKSON,
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            codec.encode("docs", column, cyclic)
        }
        assertTrue("docs.meta" in ex.message!!, ex.message ?: "")
        assertTrue(ex.cause != null)
    }
}
