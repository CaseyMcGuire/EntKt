package entkt.runtime

/**
 * Per-save owner-row-stability mode for generated `update(...)` saves
 * (RFC #4).
 *
 * - [ReadCurrent]: the default. The owner row is read but not locked
 *   before hooks, privacy, validation, and writes. Another transaction
 *   may change the owner row's scalar fields or delete it between the
 *   read and the write — the staleness window from RFC #1's
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
