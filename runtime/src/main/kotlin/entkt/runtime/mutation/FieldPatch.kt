package entkt.runtime.mutation

/**
 * A tri-state patch entry for one field or FK in a generated update.
 *
 * `Unset` means the entry is absent — the builder and hooks didn't touch
 * the field, so the database write must skip it. `Set(value)` means the
 * entry is present with that value; for nullable fields, `Set(null)` is
 * an explicit clear, distinct from `Unset`.
 *
 * Generated update builders lower their dirty state into a per-entity
 * patch type whose fields are `FieldPatch<T>`. Update privacy and validation
 * items expose the requested patch (caller/hook intent) and the
 * effective patch (after framework update defaults). The driver write
 * set is the effective patch's `Set` entries only.
 */
sealed interface FieldPatch<out T> {
    data object Unset : FieldPatch<Nothing>
    data class Set<T>(val value: T) : FieldPatch<T>
}

/**
 * Resolve a patch entry against a fallback. Returns the patched value
 * when the entry is `Set` (including `Set(null)` for nullable fields),
 * or [fallback] when the entry is `Unset`. Generated update code uses
 * this to build the after-state candidate by folding the effective
 * patch onto the loaded `before` row.
 */
fun <T> FieldPatch<T>.orElse(fallback: T): T = when (this) {
    is FieldPatch.Set -> value
    FieldPatch.Unset -> fallback
}
