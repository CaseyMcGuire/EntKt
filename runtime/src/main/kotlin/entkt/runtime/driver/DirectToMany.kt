@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.driver

import entkt.query.EntktInternal
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.EagerWindowStrategy

// ------------------------------------------------------------------
// Phase 2A of the set-based eager graph loader: the strategy-neutral
// driver contract for one direct to-many relationship read, and the
// runtime-owned executor that selects between a driver's native
// per-parent window lowering and the mandatory phase-1 emulation
// fallback. Generated code supplies typed schema metadata, entity
// decoding, IDs, and attachment; the runtime owns the logical plan
// and the fallback; the driver owns the physical lowering.
// ------------------------------------------------------------------

/**
 * Whether a driver can push a direct to-many eager edge's per-parent
 * ordering, offset, and limit into storage.
 *
 * The runtime consults this before every direct to-many eager fetch:
 * NATIVE routes the step through [Driver.queryDirectToMany]; EMULATED
 * retains the phase-1 lowering — one ordinary [Driver.query] with the
 * complete frozen predicate list and the window applied in memory
 * after grouping. Emulation is the mandatory compatibility fallback:
 * a driver without the native capability accepts every eager query it
 * accepted before this capability existed.
 */
public enum class DirectToManyWindowCapability {
    /** [Driver.queryDirectToMany] applies per-parent windows in storage. */
    NATIVE,

    /**
     * No native per-parent window: the runtime fetches every matching
     * row through [Driver.query] and applies each parent's window in
     * memory, which can overfetch rows that the runtime later discards.
     */
    EMULATED,
}

/**
 * One logical direct to-many relationship read: fetch the target rows
 * related to [sourceKeys] through [targetForeignKey], filtered by the
 * remaining frozen target predicates, in the canonical effective
 * order, with each parent's window applied.
 *
 * The target read interceptors have already seen the complete logical
 * structural constraint (`targetForeignKey IN sourceKeys`) exactly
 * once before this value is constructed. [targetPredicates] therefore
 * contains the frozen caller and interceptor predicates only — the
 * relationship constraint is carried by [sourceKeys] +
 * [targetForeignKey] so the driver can lower it itself (PostgreSQL:
 * one typed-array parameter rather than one scalar bind per parent).
 */
@EntktInternal
public data class DirectToManyQuery(
    /** Storage table of the target entity. */
    val targetTable: String,
    /**
     * The current parent keys, in parent encounter order. May contain
     * duplicates; matching is set-semantics (`IN`/`= ANY`), so
     * duplicates cannot duplicate rows.
     */
    val sourceKeys: List<Any>,
    /** The target-side FK column the relationship matches on. */
    val targetForeignKey: String,
    /**
     * The frozen non-structural target predicates (caller and
     * interceptor contributions), to be AND-ed with the relationship
     * constraint. Never contains the structural relationship `IN`.
     */
    val targetPredicates: List<Predicate<*>>,
    /**
     * The canonical effective target order — the caller's terms plus
     * the framework's primary-key ascending tie-breaker. Never empty
     * for an eager step; it drives both the per-parent ranking and
     * the returned global row order.
     */
    val effectiveOrder: List<OrderField<*>>,
    /** The per-parent window to apply. */
    val window: PerParentWindow,
)

/**
 * A per-parent window: skip [offset] rows of each parent's ordered
 * target sequence, then take at most [limit] (null = unbounded).
 * Window-bound arithmetic must never be evaluated in `Int` — a
 * conforming driver computes `offset + limit` in `Long` (or wider).
 */
@EntktInternal
public data class PerParentWindow(
    val offset: Int,
    val limit: Int?,
) {
    init {
        require(offset >= 0) { "per-parent offset must be non-negative; was $offset" }
        require(limit == null || limit >= 0) { "per-parent limit must be non-negative; was $limit" }
    }
}

/**
 * One target row plus the source key it is associated with. The
 * source key rides outside the row map so association never depends
 * on re-reading the FK out of the entity row; it must equal the
 * row's decoded [DirectToManyQuery.targetForeignKey] value.
 */
@EntktInternal
public data class RelatedRow(
    val sourceKey: Any?,
    val targetRow: Map<String, Any?>,
)

/**
 * The rows of one logical direct to-many read, in the same global
 * effective target order phase 1 produces, plus the window strategy
 * that actually executed.
 *
 * Under [EagerWindowStrategy.STORAGE_NATIVE] every row is already
 * inside its parent's window; under
 * [EagerWindowStrategy.IN_MEMORY_EMULATED] the rows are the complete
 * unwindowed match set and the caller applies each parent's window
 * after grouping, exactly as phase 1 did. A driver must never report
 * STORAGE_NATIVE for a read whose window it did not apply in storage.
 * Synthetic driver columns (ranking aliases) never appear in
 * [RelatedRow.targetRow].
 */
@EntktInternal
public data class RelatedRows(
    val rows: List<RelatedRow>,
    val strategy: EagerWindowStrategy,
)

/**
 * Execute one logical direct to-many relationship read, selecting the
 * driver's native per-parent window lowering when available and the
 * phase-1 emulation otherwise.
 *
 * [capability] is the caller's ONE
 * [Driver.directToManyWindowCapability] sample for this eager step —
 * generated code takes it before the interceptor chain (the running
 * bind budget depends on it) and passes the same sample here, so
 * budgeting and routing cannot disagree even against a driver whose
 * capability answer is not stable between calls.
 *
 * Driver reads stay data-gated exactly as in phase 1: an empty parent
 * set or a `limit(0)` window performs no driver I/O at all (the
 * interceptor pass has already run by the time this is called). On
 * the emulated path the driver receives [emulationPredicates] — the
 * frozen spec's complete ordered predicate list, structural
 * relationship constraint included — so the fallback issues the
 * byte-identical phase-1 query; the returned rows are unwindowed and
 * the caller applies each parent's window after grouping. On the
 * native path the driver lowers the relationship constraint itself
 * from [DirectToManyQuery.sourceKeys] and returns storage-windowed
 * rows.
 */
@EntktInternal
public fun executeDirectToMany(
    driver: Driver,
    query: DirectToManyQuery,
    emulationPredicates: List<Predicate<*>>,
    capability: DirectToManyWindowCapability,
): RelatedRows {
    if (query.sourceKeys.isEmpty() || query.window.limit == 0) {
        val strategy = when (capability) {
            DirectToManyWindowCapability.NATIVE -> EagerWindowStrategy.STORAGE_NATIVE
            DirectToManyWindowCapability.EMULATED -> EagerWindowStrategy.IN_MEMORY_EMULATED
        }
        return RelatedRows(emptyList(), strategy)
    }
    return when (capability) {
        DirectToManyWindowCapability.NATIVE -> driver.queryDirectToMany(query)
        DirectToManyWindowCapability.EMULATED -> {
            val rows = driver.query(
                query.targetTable,
                emulationPredicates,
                query.effectiveOrder,
                null,
                null,
            )
            RelatedRows(
                rows.map { RelatedRow(it[query.targetForeignKey], it) },
                EagerWindowStrategy.IN_MEMORY_EMULATED,
            )
        }
    }
}
