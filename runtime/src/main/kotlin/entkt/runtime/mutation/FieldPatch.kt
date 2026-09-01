package entkt.runtime.mutation

/**
 * A tri-state assignment for one field or FK in generated mutation state.
 *
 * `Unset` means the entry is absent — the caller and hooks didn't assign
 * the field, so the database write must skip it. `Set(value)` means the
 * entry is present with that value; for nullable fields, `Set(null)` is
 * an explicit clear, distinct from `Unset`.
 *
 * Generated drafts lower their assignments into immutable before-hook states.
 * Updates also lower the final hook state into a per-entity patch type whose
 * fields are `FieldPatch<T>`. Update privacy and validation items expose the
 * requested patch (caller/hook intent) and the
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
