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
 *   Present only in delta mode. A validator that wants to reject
 *   `remove(unknownId)` reads [requestedRemoves] for intent regardless
 *   of whether the eventual database effect deletes anything.
 * - [requestedRemoves]: deduplicated ids the caller passed to
 *   `remove(...)`. Same delta-mode semantics as [requestedAdds].
 *
 * **Invariants (generated-code guarantee).** The generated mutator
 * rejects same-id mixed-direction calls (`add(x); remove(x)` and the
 * reverse) at the call site, so when an instance flows through the
 * generated pipeline:
 *
 *  - `requestedSet == null` XOR `requestedAdds + requestedRemoves` is
 *    non-empty (replacement and delta are mutually exclusive).
 *  - `requestedAdds intersect requestedRemoves` is always empty.
 *
 * The data class itself does NOT enforce these invariants in its
 * constructor — instances are immutable value carriers and any caller
 * (including a test) can construct an inconsistent value. Downstream
 * consumers that operate on trusted generated input (`computeEdgeChanges`)
 * assert the invariants explicitly.
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
 *   intent. Same semantics, dedup rules, and disjoint-by-construction
 *   invariants as [PendingEdgeOps] (same-id mixed-direction rejected
 *   at the mutator call site). A validator that wants to reject
 *   `remove(unknownId)` reads [requestedRemoves] for intent regardless
 *   of whether [removed] ends up empty.
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
 * null; [requestedAdds] and [requestedRemoves] reflect the literal
 * calls and are disjoint by construction, so [added] and [removed]
 * are straight set algebra (`requestedAdds - current` and
 * `requestedRemoves ∩ current` respectively) — no cancellation logic.
 *
 * Same invariant-enforcement note as [PendingEdgeOps]: the data class
 * doesn't validate its constructor; trusted-input consumers
 * ([computeEdgeChanges]) assert the invariants explicitly.
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

/**
 * Compute the database-delta [EdgeChanges] for one link-table M2M edge
 * given the caller's [pending] intent (captured in
 * [PendingEdgeOps]) and the [current] set of target ids already linked
 * to the owner (read from the junction table). Called from generated
 * `${Schema}Update.save()` after the canonical patch is built and the
 * junction rows are read; the result feeds into the privacy / validation
 * `edgeChanges` sidecar surfaced through
 * `${Schema}EdgeChangesView` (RFC #5 Phase 5).
 *
 * **Replacement mode** (`pending.requestedSet != null`): the requested
 * set is the final intended membership, so the delta is
 * `added = requestedSet - current` and `removed = current - requestedSet`.
 * The intent fields `requestedAdds` / `requestedRemoves` stay empty
 * because the mutator rejects mixed replacement+delta at the call
 * site (RFC #5 Phase 2).
 *
 * **Delta mode** (`pending.requestedAdds` / `requestedRemoves`
 * populated): the two intent sets are disjoint by construction — the
 * mutator rejects same-id mixed-direction calls (`add(x)` after
 * `remove(x)` and the reverse) at the call site, so no id can appear
 * in both sets. The database delta is straight set algebra:
 *
 *  - `added = requestedAdds - current` — ids the caller asked to add
 *    that are not already linked
 *  - `removed = requestedRemoves ∩ current` — ids the caller asked to
 *    remove that are currently linked
 *
 * The intent fields pass through verbatim. A `remove(x)` for an id
 * that isn't currently linked is a database no-op but still appears
 * in `requestedRemoves`, so a validator inspecting intent can reject
 * it.
 *
 * **Invariant checks.** [PendingEdgeOps]'s data class doesn't validate
 * its constructor, so this function enforces the two invariants the
 * generated mutator guarantees in normal usage:
 *
 *  1. Replacement / delta are mutually exclusive — `requestedSet`
 *     non-null implies both intent sets are empty.
 *  2. `requestedAdds` and `requestedRemoves` are disjoint.
 *
 * Either failing throws `IllegalArgumentException` (via `require`).
 * In generated-code call sites these can't fire — the mutator's
 * call-site checks and the save-preflight defense-in-depth
 * `_checkLinkTableM2MMixedMode` already reject the corresponding
 * states. The guards make the public utility safe for direct callers
 * (tests, ad-hoc usage) that construct `PendingEdgeOps` manually.
 */
public fun <ID> computeEdgeChanges(
    pending: PendingEdgeOps<ID>,
    current: Set<ID>,
): EdgeChanges<ID> {
    require(
        pending.requestedSet == null ||
            (pending.requestedAdds.isEmpty() && pending.requestedRemoves.isEmpty()),
    ) {
        "PendingEdgeOps: requestedSet is mutually exclusive with requestedAdds / requestedRemoves"
    }
    require((pending.requestedAdds intersect pending.requestedRemoves).isEmpty()) {
        "PendingEdgeOps: requestedAdds and requestedRemoves must be disjoint " +
            "(overlap = ${pending.requestedAdds intersect pending.requestedRemoves})"
    }
    return if (pending.requestedSet != null) {
        val rs = pending.requestedSet
        EdgeChanges(
            requestedSet = rs,
            added = rs - current,
            removed = current - rs,
        )
    } else {
        EdgeChanges(
            requestedAdds = pending.requestedAdds,
            requestedRemoves = pending.requestedRemoves,
            added = pending.requestedAdds - current,
            removed = pending.requestedRemoves intersect current,
        )
    }
}
