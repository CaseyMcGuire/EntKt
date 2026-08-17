package entkt.runtime.mutation

/**
 * Client-level transaction discipline requested by the application.
 * Generated saves enforce the configured requirement at the start of
 * `save()` (and `delete()` on the repo), before hooks, privacy,
 * validation, driver reads, or driver writes — so a stricter setting
 * surfaces a missing transaction as a [TransactionRequiredException]
 * before any work is done.
 *
 * - [Optional]: no requirement. Saves run in whatever client scope
 *   they were called from. This is the default and matches behavior
 *   prior to transaction locking.
 * - [RequiredForMultiWrite]: generated operations classified as a
 *   multi-row or multi-write shape must run on a transaction-scoped
 *   client; single-row create/update/delete saves stay
 *   transaction-optional. This classification describes the logical
 *   operation, not the number of driver method calls — a driver may
 *   execute one set-based statement or chunk it internally. The
 *   aggregate APIs classified this way today are `createMany(...)`
 *   (when called with 2+ blocks) and
 *   `deleteMany(...)` (regardless of how many rows match — classified
 *   by operation shape, not result size, so an empty-match call
     *   still rejects). Future link-table M2M write helpers
 *   (`tags.add(...)`, `tags.remove(...)`, `tags.set(...)`) will join
 *   the same classification once they land.
 * - [RequiredForAllWrites]: every generated write — create, update,
 *   delete, multi-write — requires a transaction-scoped client.
 *   Useful for teams that always want explicit transaction boundaries
 *   around mutations.
 */
enum class TransactionRequirement {
    Optional,
    RequiredForMultiWrite,
    RequiredForAllWrites,
}

/**
 * Thrown by generated saves when the client's
 * [TransactionRequirement] is not satisfied — e.g. a write under
 * [TransactionRequirement.RequiredForAllWrites] called on a normal
 * (non-transactional) client. The save throws *before* hooks,
 * privacy, validation, driver reads, or driver writes, so this
 * exception is never racy with partial writes.
 */
class TransactionRequiredException(message: String) : RuntimeException(message)
