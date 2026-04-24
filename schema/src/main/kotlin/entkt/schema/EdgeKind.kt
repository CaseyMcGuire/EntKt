package entkt.schema

sealed interface EdgeKind {
    data class BelongsTo(
        val required: Boolean = false,
        val unique: Boolean = false,
        val field: String? = null,
        val onDelete: OnDelete? = null,
    ) : EdgeKind

    data object HasMany : EdgeKind
    data object HasOne : EdgeKind
    data class ManyToMany(val through: Through) : EdgeKind
}
