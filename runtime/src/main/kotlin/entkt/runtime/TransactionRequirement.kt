package entkt.runtime

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
 *   prior to RFC #4.
 * - [RequiredForMultiWrite]: saves that issue more than one driver
 *   write must run on a transaction-scoped client. Single-write
 *   create/update/delete saves don't need a transaction; link-table
 *   M2M helpers and other multi-write paths do. (Until link-table
 *   helpers land — RFC #5 — this behaves the same as [Optional],
 *   because no multi-write save shape exists yet.)
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
