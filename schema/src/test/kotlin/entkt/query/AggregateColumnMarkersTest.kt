package entkt.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AggregateColumnMarkersTest {

    private enum class Status { OPEN, CLOSED }

    @Test
    fun `numeric columns form the Integral and Floating hierarchy and are groupable`() {
        val i = IntegralColumn<Any, Long>("n")
        val f = FloatingColumn<Any, Double>("x")
        assertTrue(i is NumericColumn<*, *>)
        assertTrue(i is ComparableColumn<*, *>)   // min/max-able
        assertTrue(i is GroupableColumn<*, *>)    // groupable
        assertTrue(f is NumericColumn<*, *>)
        assertTrue(f is GroupableColumn<*, *>)
    }

    @Test
    fun `bool and uuid scalars are groupable but not comparable`() {
        val b = GroupableScalarColumn<Any, Boolean>("active")
        assertTrue(b is GroupableColumn<*, *>)
        assertFalse(b is ComparableColumn<*, *>)  // no min/max/sum/avg by type
    }

    @Test
    fun `enum column is groupable, not comparable, and decodes its stored name`() {
        val status = EnumColumn<Any, Status>("status") { Status.valueOf(it) }
        assertTrue(status is GroupableColumn<*, *>)
        assertFalse(status is ComparableColumn<*, *>)   // enums excluded from min/max
        assertEquals(Status.OPEN, status.decodeKey("OPEN"))
        assertEquals(null, status.decodeKey(null))
    }

    @Test
    fun `comparable column decodeKey is identity`() {
        val n = IntegralColumn<Any, Long>("n")
        assertEquals(42L, n.decodeKey(42L))
        assertEquals(null, n.decodeKey(null))
    }

    @Test
    fun `nullable columns mix in NullableGroupableColumn`() {
        assertTrue(NullableIntegralColumn<Any, Long>("n") is NullableGroupableColumn<*, *>)
        assertTrue(NullableComparableColumn<Any, java.time.Instant>("t") is NullableGroupableColumn<*, *>)
        assertTrue(NullableGroupableScalarColumn<Any, java.util.UUID>("u") is NullableGroupableColumn<*, *>)
        assertTrue(NullableStringColumn<Any>("s") is NullableGroupableColumn<*, *>)
    }

    // The crux of the nullable-key design: overload resolution must send a
    // non-null column to the GroupableColumn overload (key K) and a nullable
    // column to the more-specific NullableGroupableColumn overload (key K?).
    private fun <K> pick(c: GroupableColumn<Any, K>): String = "nonnull"
    private fun <K> pick(c: NullableGroupableColumn<Any, K>): String = "nullable"

    @Test
    fun `overload resolution distinguishes nullable group columns by specificity`() {
        assertEquals("nonnull", pick(IntegralColumn<Any, Long>("n")))
        assertEquals("nonnull", pick(GroupableScalarColumn<Any, Boolean>("b")))
        assertEquals("nonnull", pick(EnumColumn<Any, Status>("s") { Status.valueOf(it) }))
        assertEquals("nullable", pick(NullableIntegralColumn<Any, Long>("n")))
        assertEquals("nullable", pick(NullableComparableColumn<Any, java.time.Instant>("t")))
        assertEquals("nullable", pick(NullableGroupableScalarColumn<Any, java.util.UUID>("u")))
    }

    @Test
    fun `an enum column without decode metadata throws when decoded`() {
        val status = EnumColumn<Any, Status>("status")  // no fromName → throwing default
        assertFailsWith<IllegalStateException> { status.decodeKey("OPEN") }
    }
}
