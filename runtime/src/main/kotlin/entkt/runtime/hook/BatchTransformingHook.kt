package entkt.runtime.hook

/**
 * A lifecycle hook that transforms an ordered batch of immutable mutation
 * states.
 *
 * Implementations transform states through [MutationBatch.mapStates] or
 * [MutationBatch.mapStatesIndexed]. Those operations retain the batch's
 * identity, size, and encounter order. A batch returned from a different hook
 * invocation is rejected by [runTransformingHooks].
 */
interface BatchTransformingHook<State> {
    fun transformBatch(states: MutationBatch<State>): MutationBatch<State>
}

/** Construct an explicitly batch-aware transforming hook. */
fun <State> batchTransformingHook(
    transform: (MutationBatch<State>) -> MutationBatch<State>,
): BatchTransformingHook<State> = object : BatchTransformingHook<State> {
    override fun transformBatch(states: MutationBatch<State>): MutationBatch<State> =
        transform(states)
}
