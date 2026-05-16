package entkt.runtime

/**
 * Read-only snapshot of pending link-table M2M edge operations for one
 * edge (RFC #5). Hooks receive this through the generated per-entity
 * `${Entity}PendingEdgeOps` aggregator on `${Entity}UpdateHookContext`,
 * so they can inspect what the caller has staged without seeing the
 * mutator surface itself.
 *
 * Captured **before** the current junction rows are read, so the
 * post-junction-read computed delta is not on this type — it lives on
 * [EdgeChanges], which privacy and validation contexts receive.
 *
 * - [requestedSet]: non-null only when the caller invoked `set(ids)`.
 *   The deduplicated final intended set from the latest `set` call.
 *   Mutually exclusive with [requestedAdds] / [requestedRemoves] —
 *   mixed replacement and delta operations on the same edge in one
 *   mutation throw `IllegalStateException` at the call site, so this
 *   type cannot represent the mixed shape.
 * - [requestedAdds]: deduplicated ids the caller passed to `add(...)`.
 *   Present only in delta mode. Same-id cancellations (a paired
 *   `remove(...)`) do NOT remove from this set — the field is the
 *   literal call log (deduped). A validator that wants to reject
 *   `remove(unknownId)` can read it regardless of whether the eventual
 *   database effect cancels out.
 * - [requestedRemoves]: deduplicated ids the caller passed to
 *   `remove(...)`. Same delta-mode + literal-call-log semantics as
 *   [requestedAdds].
 */
public data class PendingEdgeOps<ID>(
    val requestedSet: Set<ID>? = null,
    val requestedAdds: Set<ID> = emptySet(),
    val requestedRemoves: Set<ID> = emptySet(),
) {
    /** `true` when the caller invoked `set(...)` (replacement mode). */
    val hasReplacement: Boolean get() = requestedSet != null

    /**
     * `true` when any intent field is non-empty — shorthand for "the
     * caller staged at least one operation on this edge in this
     * mutation."
     */
    val hasChanges: Boolean get() =
        requestedSet != null || requestedAdds.isNotEmpty() || requestedRemoves.isNotEmpty()
}

/**
 * The full view of one link-table M2M edge's changes for one save
 * (RFC #5): the caller's intent (the literal call log, identical to
 * the [PendingEdgeOps] surface seen in before hooks) plus the computed
 * database delta. Privacy and validation contexts receive this through
 * the generated per-entity `${Entity}EdgeChangesView` sidecar.
 *
 * - [requestedSet] / [requestedAdds] / [requestedRemoves]: caller
 *   intent. Identical semantics and dedup rules to [PendingEdgeOps] —
 *   same-id cancellations do NOT remove from these sets, so a
 *   validator that wants to reject `remove(unknownId)` reads
 *   [requestedRemoves] regardless of whether [removed] ends up empty.
 * - [added]: target ids that will be inserted into the junction
 *   table — the deduplicated database delta, computed against the
 *   current junction rows.
 * - [removed]: target ids that will be deleted from the junction
 *   table — the deduplicated database delta.
 *
 * In replacement mode (`set(...)` called) [requestedSet] is non-null
 * and [requestedAdds] / [requestedRemoves] are empty; [added] and
 * [removed] are computed against the current junction rows.
 *
 * In delta mode (`add(...)` / `remove(...)` calls) [requestedSet] is
 * null; [requestedAdds] / [requestedRemoves] reflect the literal calls
 * and [added] / [removed] are the net database delta after canceling
 * paired add/remove operations on the same id.
 */
public data class EdgeChanges<ID>(
    val requestedSet: Set<ID>? = null,
    val requestedAdds: Set<ID> = emptySet(),
    val requestedRemoves: Set<ID> = emptySet(),
    val added: Set<ID> = emptySet(),
    val removed: Set<ID> = emptySet(),
) {
    val hasReplacement: Boolean get() = requestedSet != null

    val hasChanges: Boolean get() =
        requestedSet != null || requestedAdds.isNotEmpty() || requestedRemoves.isNotEmpty()

    /** `true` when the save will issue at least one junction-row write. */
    val hasDatabaseEffect: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
}
