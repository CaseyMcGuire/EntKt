package entkt.runtime.mutation.execution

import entkt.query.EntktInternal

/** Execution policy only; contains no lifecycle functions or services. */
@EntktInternal
data class MutationRequirements(
    val operationName: String,
    /** Logical request shape used to enforce the application's transaction requirement. */
    val multiWrite: Boolean = false,
    /** Reuse a transaction, or open an owned one after application policy has been checked. */
    val requiresAtomicTransaction: Boolean = false,
)
