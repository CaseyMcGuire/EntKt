package entkt.runtime.hook

/**
 * A lifecycle hook that transforms an ordered batch of immutable mutation
 * states.
 *
 * Implementations transform states through [MutationBatch.mapStates] or
 * [MutationBatch.mapStatesIndexed]. Those operations retain the batch's
 * identity, size, and encounter order. A batch returned from a different hook
 * invocation is rejected by [MutationHookRunner].
 */
interface BatchMutationHook<State> {
    fun transformBatch(states: MutationBatch<State>): MutationBatch<State>
}

/** Construct an explicitly batch-aware mutation hook. */
fun <State> batchMutationHook(
    transform: (MutationBatch<State>) -> MutationBatch<State>,
): BatchMutationHook<State> = object : BatchMutationHook<State> {
    override fun transformBatch(states: MutationBatch<State>): MutationBatch<State> =
        transform(states)
}
