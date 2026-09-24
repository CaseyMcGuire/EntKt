package entkt.types

/**
 * An immutable binary value with content-based equality and hashing.
 *
 * [of] and [toByteArray] copy their arrays so callers cannot change this value's
 * contents. The same [Bytes] instance can be shared by entities and mutation
 * lifecycle values without further copying.
 */
class Bytes private constructor(private val value: ByteArray) {
    /** Number of bytes in this value. */
    val size: Int get() = value.size

    /** Read the byte at [index], throwing if the index is outside this value. */
    operator fun get(index: Int): Byte = value[index]

    /** Return an independent, mutable copy of this value's bytes. */
    fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Bytes && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /** Describe the size without exposing potentially sensitive binary content. */
    override fun toString(): String = "Bytes(size=$size)"

    companion object {
        /** Create a value by copying [value]; later changes to that array are independent. */
        fun of(value: ByteArray): Bytes = Bytes(value.copyOf())
    }
}
