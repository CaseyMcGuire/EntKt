package entkt.schema

sealed interface EdgeKind {
    data class BelongsTo(
        /** Exact physical FK column, independent of the relationship's Kotlin name. */
        val column: String,
        val required: Boolean = true,
        val unique: Boolean = false,
        val field: String? = null,
        val onDelete: OnDelete? = null,
        val immutable: Boolean = false,
    ) : EdgeKind

    data object HasMany : EdgeKind
    data object HasOne : EdgeKind
    data class ManyToMany(val through: ManyToManyThrough) : EdgeKind
}
