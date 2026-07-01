package entkt.runtime.mutation

/**
 * Per-save owner-row-stability mode for generated `update(...)` saves
 * (transaction locking).
 *
 * - [ReadCurrent]: the default. The owner row is read but not locked
 *   before hooks, privacy, validation, and writes. Another transaction
 *   may change the owner row's scalar fields or delete it between the
 *   read and the write — the staleness window from id-rooted update behavior's
 *   id-based update roots.
 * - [Pessimistic]: the owner row is read under a true row lock
 *   (`SELECT ... FOR UPDATE`-equivalent) before hooks, privacy,
 *   validation, and writes. The checked owner state stays stable
 *   through the write.
 *
 * `Pessimistic` requires a transaction-scoped client and a driver
 * that reports `supportsReadRowForUpdate = true`. Both checks fire
 * at the start of `save()`, before any observable work — a missing
 * transaction throws [TransactionRequiredException], a driver without
 * true row-lock support throws [UnsupportedDriverCapabilityException].
 */
enum class UpdateConsistency {
    ReadCurrent,
    Pessimistic,
}

/**
 * Thrown by generated saves when the requested consistency mode (or
 * a multi-write helper) needs a driver capability the configured
 * [Driver] doesn't expose — e.g. `UpdateConsistency.Pessimistic` on a
 * driver that reports `supportsReadRowForUpdate = false`.
 *
 * The save throws *before* hooks, privacy, validation, driver reads,
 * or driver writes, so this exception is never racy with partial
 * writes.
 */
class UnsupportedDriverCapabilityException(message: String) : RuntimeException(message)

/**
 * Per-update relationship-locking mode for symmetric link-table M2M writes.
 * Layered *on top of* the always-on owner-edge serialization, which
 * is unaffected by this selector.
 *
 * - [OwnerOnly]: the default. The save takes only the owner-edge lock, which
 *   does not coordinate writes from the opposite orientation of the same link
 *   table — so concurrent cross-orientation writes can deadlock (retryable) or
 *   last-writer-win.
 * - [Canonical]: additionally take a canonical relationship lock keyed by the
 *   junction + unordered FK pair, so both orientations of the same link table
 *   serialize. Cooperative — it only protects against other writers that also
 *   select `Canonical`. Requires a transaction-scoped client and a driver
 *   reporting `supportsRelationshipSerialization = true`.
 *
 * Has no effect on saves with no pending link-table M2M writes (there is no
 * relationship to lock).
 */
enum class RelationshipLocking {
    OwnerOnly,
    Canonical,
}

/**
 * Canonical identity of a link-table relationship, used as the key for the
 * relationship lock ([Driver.serializeRelationship]). [fkColumns] is the
 * unordered FK pair stored in canonical (sorted) order so both orientations
 * of the same link table produce an equal key. Build via [canonical] to
 * enforce the sort.
 */
data class RelationshipLockKey(
    val junctionTable: String,
    val fkColumns: List<String>,
) {
    companion object {
        /** Build a key with [fkColumns] sorted into canonical order. */
        fun canonical(junctionTable: String, fkColumns: List<String>): RelationshipLockKey =
            RelationshipLockKey(junctionTable, fkColumns.sorted())
    }
}
