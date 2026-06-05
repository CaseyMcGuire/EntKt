package entkt.postgres.vector

/**
 * A Postgres `pgvector` value: a fixed-length list of 32-bit floats with
 * **content** equality (RFC "Native Database Column Types", §7).
 *
 * The backing array is never shared — [of] copies on the way in and
 * [toFloatArray] copies on the way out (with read-only [get] / [dimensions]
 * for the common case) — so a caller mutating their array, before or after
 * construction, can never silently change a `PgVector`'s identity. This is a
 * regular class (not `FloatArray` exposed directly, not a `@JvmInline value
 * class` over `FloatArray`, both of which inherit referential array equality
 * that is surprising inside generated `data class` entities).
 *
 * Carries no fixed dimension itself: the column's declared `dimensions` is the
 * source of truth, checked at the generated write boundary and at bind.
 */
class PgVector private constructor(private val values: FloatArray) {
    /** Number of components. */
    val dimensions: Int get() = values.size

    /** Read-only component access. */
    operator fun get(index: Int): Float = values[index]

    /** A defensive copy of the components (the backing array is never shared). */
    fun toFloatArray(): FloatArray = values.copyOf()

    companion object {
        /** Build from a float array (copied — the caller may keep mutating theirs). */
        fun of(values: FloatArray): PgVector = PgVector(values.copyOf())

        /** Build from a list of floats. */
        fun of(values: List<Float>): PgVector = PgVector(values.toFloatArray())
    }

    override fun equals(other: Any?): Boolean = other is PgVector && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    // Never dump the raw components — a vector can be large and is often a
    // sensitive embedding.
    override fun toString(): String = "PgVector(dimensions=$dimensions)"
}
