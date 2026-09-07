package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy
import entkt.runtime.result.EntBatchMutationHookContractException

/** Folds one immutable, ordered set of mutation hooks over mutation state. */
@EntktInternal
class MutationHookRunner<State>(
    lifecycle: String,
    hooks: List<BatchTransformingHook<State>>,
) {
    private val lifecycle: String = lifecycle.also {
        require(it.isNotBlank()) { "lifecycle must not be blank" }
    }
    private val hooks = immutableListCopy(hooks)

    /** Return the state produced by applying every hook in registration order. */
    fun run(state: State): State = runBatch(listOf(state)).single()

    /**
     * Return the correlated batch produced by applying every hook in
     * registration order. Empty batches do not invoke hooks.
     */
    fun runBatch(states: List<State>): MutationBatch<State> =
        runBatch(MutationBatch.from(states))

    /** Fold hooks over an existing correlated batch without changing its identity. */
    fun runBatch(states: MutationBatch<State>): MutationBatch<State> {
        var current = states
        if (current.isEmpty()) return current

        for (hook in hooks) {
            val transformed: MutationBatch<State>? = hook.transformBatch(current)
            current = transformed
                ?.let { current.validatedResultForInternalUse(lifecycle, it) }
                ?: throw EntBatchMutationHookContractException(
                    lifecycle = lifecycle,
                    expectedSize = current.size,
                    actualSize = null,
                )
        }
        return current
    }
}
