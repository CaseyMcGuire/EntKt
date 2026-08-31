package entkt.runtime.mutation.execution

import entkt.query.EntktInternal

/** Classifies only generated driver reads performed while lowering an update. */
@EntktInternal
interface UpdatePreparationScope {
    fun <Result> driverRead(block: () -> Result): Result
}
