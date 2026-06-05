package entkt.postgres.vector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PgVectorTest {

    @Test
    fun `content equality and hashCode, not referential`() {
        val a = PgVector.of(floatArrayOf(1f, 2f, 3f))
        val b = PgVector.of(floatArrayOf(1f, 2f, 3f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, PgVector.of(floatArrayOf(1f, 2f, 4f)))
    }

    @Test
    fun `of(FloatArray) and of(List) agree`() {
        assertEquals(
            PgVector.of(floatArrayOf(0.5f, -1f, 2f)),
            PgVector.of(listOf(0.5f, -1f, 2f)),
        )
    }

    @Test
    fun `dimensions and indexed access`() {
        val v = PgVector.of(floatArrayOf(7f, 8f, 9f))
        assertEquals(3, v.dimensions)
        assertEquals(8f, v[1])
    }

    @Test
    fun `defensive copy IN — mutating the source array does not change the vector`() {
        val src = floatArrayOf(1f, 2f, 3f)
        val v = PgVector.of(src)
        src[0] = 99f
        assertEquals(PgVector.of(floatArrayOf(1f, 2f, 3f)), v)
        assertEquals(1f, v[0])
    }

    @Test
    fun `defensive copy OUT — mutating toFloatArray does not change the vector`() {
        val v = PgVector.of(floatArrayOf(1f, 2f, 3f))
        val out = v.toFloatArray()
        out[0] = 99f
        assertEquals(1f, v[0])
        assertEquals(PgVector.of(floatArrayOf(1f, 2f, 3f)), v)
    }

    @Test
    fun `toString does not dump the components`() {
        val s = PgVector.of(floatArrayOf(1f, 2f, 3f)).toString()
        assertTrue("dimensions=3" in s, s)
        assertTrue("1.0" !in s && "2.0" !in s, "must not leak component values: $s")
    }
}
