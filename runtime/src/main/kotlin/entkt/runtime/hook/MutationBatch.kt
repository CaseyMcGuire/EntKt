package entkt.runtime.hook

import entkt.runtime.result.EntBatchMutationHookContractException

/**
 * An immutable, ordered batch of mutation states supplied to a batch hook.
 *
 * A hook cannot add, remove, or reorder positions. It returns replacement states
 * through [mapStates] or [mapStatesIndexed], which preserve correspondence with
 * the original logical operations without a parallel result list.
 */
sealed interface MutationBatch<out State> : List<State> {
    /** Transform every state while retaining this batch's correspondence. */
    fun <Result> mapStates(transform: (State) -> Result): MutationBatch<Result>

    /**
     * Transform every state in encounter order while exposing its stable
     * position within this batch.
     */
    fun <Result> mapStatesIndexed(
        transform: (index: Int, state: State) -> Result,
    ): MutationBatch<Result>

    companion object {
        /**
         * Create a mutation batch for directly testing or composing batch
         * hooks. Hook execution rejects this batch if a hook returns it for a
         * different invocation.
         */
        @JvmStatic
        fun <State> from(states: List<State>): MutationBatch<State> =
            MutationBatchImplementations.create(states)
    }
}

/** Private implementation and its invocation-provenance check. */
private object MutationBatchImplementations {
    fun <State> create(states: List<State>): MutationBatch<State> =
        DefaultMutationBatch(
            identity = Any(),
            states = states.toList(),
        )

    fun <State> validateResult(
        source: MutationBatch<State>,
        lifecycle: String,
        result: MutationBatch<State>,
    ): MutationBatch<State> {
        val sourceBatch = source as DefaultMutationBatch<State>
        val resultBatch = result as DefaultMutationBatch<State>
        if (resultBatch.identity !== sourceBatch.identity) {
            throw EntBatchMutationHookContractException(
                lifecycle = lifecycle,
                expectedSize = source.size,
                actualSize = result.size,
                foreignBatchResult = true,
            )
        }
        return result
    }

    private class DefaultMutationBatch<out State>(
        val identity: Any,
        private val states: List<State>,
    ) : AbstractList<State>(), MutationBatch<State> {
        override val size: Int
            get() = states.size

        override fun get(index: Int): State = states[index]

        override fun <Result> mapStates(
            transform: (State) -> Result,
        ): MutationBatch<Result> = DefaultMutationBatch(
            identity = identity,
            states = states.map(transform),
        )

        override fun <Result> mapStatesIndexed(
            transform: (index: Int, state: State) -> Result,
        ): MutationBatch<Result> = DefaultMutationBatch(
            identity = identity,
            states = states.mapIndexed(transform),
        )
    }
}

/** Validate that a hook returned states derived from the supplied batch. */
internal fun <State> MutationBatch<State>.validatedResultForInternalUse(
    lifecycle: String,
    result: MutationBatch<State>,
): MutationBatch<State> = MutationBatchImplementations.validateResult(
    source = this,
    lifecycle = lifecycle,
    result = result,
)
