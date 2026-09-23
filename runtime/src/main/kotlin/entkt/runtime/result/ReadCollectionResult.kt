package entkt.runtime.result

import entkt.query.EntktInternal

/**
 * The result of a collection read, with a separate outcome for each selected root.
 *
 * [Completed] preserves the selected roots' order and count, including root privacy
 * denials. It does not mean every entry succeeded. An empty selection completes
 * with no entries. Execution failures, including required selected-edge denials,
 * produce [Failed] without exposing partially loaded entries.
 *
 * [getOrThrow], [Completed.entities], and [deniedAsNull] only inspect an existing result.
 * They perform no I/O, privacy evaluation, filtering, or pagination refilling.
 */
sealed interface ReadCollectionResult<out T> {
    /**
     * The query completed reliably. Framework-produced entries contain either a
     * readable root or that root's privacy denial, never the denied entity itself.
     */
    data class Completed<T>(
        val entries: List<ReadResult<T>>,
    ) : ReadCollectionResult<T> {
        /** Return the individual outcomes unchanged, without unwrapping or throwing. */
        fun entities(): List<ReadResult<T>> = entries
    }

    /** The query could not produce a reliable collection result. */
    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val exception: Exception,
    ) : ReadCollectionResult<Nothing>

    companion object {
        /** Construct a framework failure; also available to opt-in test fixtures. */
        @EntktInternal
        fun failedForInternalUse(exception: Exception): ReadCollectionResult<Nothing> =
            Failed(exception)
    }

    /**
     * Return all values, or throw on any failure. Root privacy denials are combined
     * into the existing aggregate exception format in encounter order, retaining
     * duplicate denials. Any other exception is rethrown directly, not turned into
     * a privacy denial. No partial list is returned.
     */
    fun getOrThrow(): List<T> {
        val entries = when (this) {
            is Completed -> entities()
            is Failed -> throw exception
        }
        val values = ArrayList<T>(entries.size)
        val denials = mutableListOf<PrivacyDenial>()

        for (entry in entries) {
            when (entry) {
                is ReadResult.Success -> values.add(entry.value)
                is ReadResult.Failed -> {
                    val exception = entry.exception
                    if (exception !is EntPrivacyDeniedException || exception.origin != LoadDenialOrigin.Root) {
                        throw exception
                    }
                    denials.addAll(exception.denials)
                }
            }
        }

        if (denials.isNotEmpty()) {
            throw EntPrivacyDeniedException(LoadDenialOrigin.Root, denials)
        }
        return values
    }
}

/**
 * Replace denied root entries with successful null entries, preserving all other
 * outcomes and the collection's order and length. Whole-query failures remain
 * failures, regardless of their exception type. No exception from rule execution,
 * storage, or required selected-edge loading is suppressed by this projection.
 *
 * Null slots reveal that hidden rows exist and their positions. This representation
 * is an explicit application choice, not automatically safe for untrusted clients.
 * Denied entity values are never exposed. This projection performs no I/O.
 */
fun <T> ReadCollectionResult<T>.deniedAsNull(): ReadCollectionResult<T?> = when (this) {
    is ReadCollectionResult.Completed -> ReadCollectionResult.Completed(
        entries.map { entry ->
            when (entry) {
                is ReadResult.Success -> entry
                is ReadResult.Failed -> {
                    val exception = entry.exception
                    if (exception is EntPrivacyDeniedException && exception.origin == LoadDenialOrigin.Root) {
                        ReadResult.Success(null)
                    } else {
                        entry
                    }
                }
            }
        },
    )
    is ReadCollectionResult.Failed -> this
}
