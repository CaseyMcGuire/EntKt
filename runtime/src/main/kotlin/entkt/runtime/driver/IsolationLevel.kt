package entkt.runtime.driver

/**
 * SQL-standard transaction isolation levels, interpreted by the database driver.
 * Databases may provide stronger guarantees or use different locking and
 * snapshot mechanisms for the same level. Selecting a level does not enable
 * automatic retries or change the lifetime of explicit transaction locks.
 */
enum class IsolationLevel {
    /**
     * Prevents reads of another transaction's uncommitted changes. Later
     * statements may observe changes committed by other transactions.
     */
    ReadCommitted,

    /**
     * Also prevents previously read row values from changing on repeated reads.
     * Newly matching rows may still appear, depending on the database; this
     * level does not universally imply a transaction-wide snapshot.
     */
    RepeatableRead,

    /**
     * Successfully committed serializable transactions have an outcome
     * equivalent to executing them one at a time. A database may reject a
     * conflicting transaction, requiring the application to retry it in full.
     */
    Serializable,
}
