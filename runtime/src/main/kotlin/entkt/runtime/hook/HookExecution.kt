package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.result.EntBatchMutationHookContractException

/**
 * Invoke hooks in registration order, passing each the supplied batch as is.
 * Empty batches do not invoke hooks. Hook registrations must remain unchanged
 * during execution.
 */
@EntktInternal
fun <T> runActionHooks(
    elements: List<T>,
    hooks: List<BatchActionHook<T>>,
) {
    if (elements.isEmpty()) {
        return
    }

    for (hook in hooks) {
        hook.runBatch(elements)
    }
}

/**
 * Apply hooks in registration order, passing each result to the next hook
 * while preserving the supplied batch's identity and order.
 * Empty batches do not invoke hooks. Hook registrations must remain unchanged
 * during execution.
 */
@EntktInternal
fun <State> runTransformingHooks(
    lifecycle: String,
    states: MutationBatch<State>,
    hooks: List<BatchTransformingHook<State>>,
): MutationBatch<State> {
    require(lifecycle.isNotBlank()) { "lifecycle must not be blank" }
    if (states.isEmpty()) return states

    var current = states
    for (hook in hooks) {
        val transformed: MutationBatch<State>? = hook.transformBatch(current)
        if (transformed == null) {
            throw EntBatchMutationHookContractException(
                lifecycle = lifecycle,
                expectedSize = current.size,
                actualSize = null,
            )
        }
        current = current.validatedResultForInternalUse(lifecycle, transformed)
    }
    return current
}
