package entkt.schema

/**
 * Describes the value a field should receive on every update when the
 * caller doesn't explicitly set it. Each variant tells codegen what
 * expression to emit in the generated update builder.
 */
sealed class UpdateDefault {
    /** Emit `Instant.now()` — only valid on TIME fields. */
    data object Now : UpdateDefault()
}
