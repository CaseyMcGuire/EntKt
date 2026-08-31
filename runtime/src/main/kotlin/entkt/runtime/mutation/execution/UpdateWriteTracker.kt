package entkt.runtime.mutation.execution

import entkt.query.EntktInternal

/** Receives positive acknowledgements from generated relationship writes. */
@EntktInternal
fun interface UpdateWriteTracker {
    fun markWritten()
}
