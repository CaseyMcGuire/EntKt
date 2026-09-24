package entkt.types

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame

class BytesTest {
    @Test
    fun `equality and hashing compare content`() {
        val first = Bytes.of(byteArrayOf(-128, -1, 0, 127))
        val second = Bytes.of(byteArrayOf(-128, -1, 0, 127))

        assertNotSame(first, second)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("found", mapOf(first to "found")[second])
        assertNotEquals(first, Bytes.of(byteArrayOf(-128, -1, 0)))
        assertNotEquals(first, Bytes.of(byteArrayOf(-128, -1, 0, 126)))
        assertFalse(first.equals(null))
        assertFalse(first.equals(byteArrayOf(-128, -1, 0, 127)))
    }

    @Test
    fun `modifying input or exported arrays cannot change a value or its hash`() {
        val input = byteArrayOf(1, 2)
        val value = Bytes.of(input)
        val hash = value.hashCode()
        val keyed = mapOf(value to "found")
        val exported = value.toByteArray()

        input[0] = 9
        exported[1] = 9

        assertEquals(Bytes.of(byteArrayOf(1, 2)), value)
        assertContentEquals(byteArrayOf(1, 2), value.toByteArray())
        assertNotSame(exported, value.toByteArray())
        assertEquals(hash, value.hashCode())
        assertEquals("found", keyed[Bytes.of(byteArrayOf(1, 2))])
    }

    @Test
    fun `size and indexing provide read only access`() {
        val value = Bytes.of(byteArrayOf(-128, 0, 127))

        assertEquals(3, value.size)
        assertEquals((-128).toByte(), value[0])
        assertEquals(0.toByte(), value[1])
        assertEquals(127.toByte(), value[2])
        assertFailsWith<IndexOutOfBoundsException> { value[-1] }
        assertFailsWith<IndexOutOfBoundsException> { value[3] }
    }

    @Test
    fun `empty values are equal and export an empty array`() {
        val empty = Bytes.of(byteArrayOf())

        assertEquals(0, empty.size)
        assertEquals(Bytes.of(byteArrayOf()), empty)
        assertEquals(Bytes.of(byteArrayOf()).hashCode(), empty.hashCode())
        assertContentEquals(byteArrayOf(), empty.toByteArray())
        assertNotEquals(empty, Bytes.of(byteArrayOf(0)))
        assertFailsWith<IndexOutOfBoundsException> { empty[0] }
    }

    @Test
    fun `string representation does not expose contents`() {
        assertEquals("Bytes(size=6)", Bytes.of("secret".toByteArray()).toString())
    }
}
