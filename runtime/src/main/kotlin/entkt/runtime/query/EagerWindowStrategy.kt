package entkt.runtime.query

/** How a to-many edge applies each parent's configured limit and offset. */
enum class EagerWindowStrategy {
    /** Fetch every matching row and apply each parent's window after grouping in memory. */
    IN_MEMORY_EMULATED,

    /** Apply each parent's window in storage before returning rows to the runtime. */
    STORAGE_NATIVE,
}
