package entkt.schema

/**
 * Referential action applied when a referenced row is deleted.
 *
 * Used on edge declarations to control the FK `ON DELETE` clause:
 * ```kotlin
 * override fun edges() = edges {
 *     from("owner", Owner).unique().required().onDelete(OnDelete.CASCADE)
 * }
 * ```
 */
enum class OnDelete {
    /** Delete the child row when the parent is deleted. */
    CASCADE,
    /** Set the FK column to NULL when the parent is deleted. Only valid on optional edges. */
    SET_NULL,
    /** Prevent deletion of the parent while children exist. */
    RESTRICT,
}
