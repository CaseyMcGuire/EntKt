package entkt.postgres

import java.sql.Connection

/**
 * Shared unwind rules for the two places that drive a JDBC transaction
 * directly: [PostgresDriver.withTransaction] (a caller-supplied block
 * over a pinned connection) and [PostgresOperations]' multi-statement
 * helper.
 *
 * Both had the same three bugs independently, so the rules live here
 * rather than being restated at each site. The invariant they enforce:
 * *cleanup must never change what the caller observes, and must never
 * decide the transaction's outcome.*
 */

/**
 * Roll back [conn], attributing any failure to [cause] instead of
 * throwing in its place. Returns whether the transaction reached a
 * decided end — feed that to [restoreAutoCommit].
 *
 * A rollback that throws must not replace the exception that caused the
 * rollback: the business failure is what the caller needs, and a broken
 * connection tends to produce both at once. `addSuppressed` keeps the
 * second without losing the first.
 */
internal fun rollbackAttributingFailure(conn: Connection, cause: Throwable): Boolean =
    try {
        conn.rollback()
        true
    } catch (rollbackFailure: Throwable) {
        cause.addSuppressed(rollbackFailure)
        false
    }

/**
 * Restore autocommit on [conn], but **only if the transaction actually
 * resolved** — committed, or rolled back successfully.
 *
 * Switching a connection with a live transaction to autocommit *commits*
 * it (JDBC 4.3 §10.1.1). So restoring unconditionally takes the one path
 * where the outcome is undecided — a `rollback()` that itself failed —
 * and turns it into a commit. The caller receives the business exception
 * while the work it describes is durably persisted.
 *
 * When the outcome is undecided this does nothing and the caller is
 * expected to close (or release) the connection instead: the Postgres
 * driver rolls back an open transaction on close, which is what the
 * failed rollback was after. The connection goes back to its pool still
 * in manual-commit mode — pools reset that on release, and a connection
 * whose rollback just failed is one a pool is likely to discard anyway.
 *
 * A failure restoring autocommit is attached to [propagating] rather
 * than thrown. When [propagating] is null the transaction committed, so
 * there is nothing to attach to and the failure is dropped: surfacing it
 * would report failure for durably committed work and invite a retry
 * wrapper to apply it twice.
 */
internal fun restoreAutoCommit(conn: Connection, propagating: Throwable?, transactionResolved: Boolean) {
    if (!transactionResolved) return
    try {
        conn.autoCommit = true
    } catch (restoreFailure: Throwable) {
        propagating?.addSuppressed(restoreFailure)
    }
}

/**
 * Close [conn], attaching any failure to [propagating] rather than
 * throwing it.
 *
 * This is the one thing `Closeable.use` gets wrong for a connection that
 * has already done durable work. `use` suppresses a close failure only
 * when the block threw; on the success path it calls `close()` bare, so
 * the failure propagates. Every write outside an explicit transaction
 * runs in autocommit and is durable the moment its statement returns —
 * so a close failure there reports failure for work that already
 * happened, and a retry-on-exception wrapper applies it twice.
 *
 * Reads have no such hazard, but they release connections through the
 * same path: a close failure is never something the caller can act on
 * once the rows are in hand.
 */
internal fun closeAttributingFailure(conn: Connection, propagating: Throwable?) {
    try {
        conn.close()
    } catch (closeFailure: Throwable) {
        propagating?.addSuppressed(closeFailure)
    }
}

/**
 * `use`, except that closing never becomes the caller's result.
 *
 * [kotlin.io.use] suppresses a close failure only when the block threw;
 * on the success path it calls `close()` bare. For a `Statement` or
 * `ResultSet` belonging to a write that already executed in autocommit,
 * that turns a completed `INSERT`/`UPDATE`/`DELETE` into a thrown
 * exception — and a retry-on-exception wrapper then applies it twice.
 * Same failure shape as a connection close, one layer down.
 *
 * A close failure while the block is already failing is attached with
 * `addSuppressed`, matching what `use` does there.
 */
internal inline fun <T : AutoCloseable?, R> T.useQuietClose(block: (T) -> R): R {
    var propagating: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        propagating = e
        throw e
    } finally {
        try {
            this?.close()
        } catch (closeFailure: Throwable) {
            propagating?.addSuppressed(closeFailure)
        }
    }
}
