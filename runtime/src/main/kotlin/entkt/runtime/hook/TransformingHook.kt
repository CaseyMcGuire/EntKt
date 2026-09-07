package entkt.runtime.hook

/**
 * A lifecycle callback that returns the mutation state to pass to the next hook.
 *
 * Returning replacement state does not imply purity: this callback may also
 * perform side effects or throw.
 *
 * Scalar hooks are also [BatchTransformingHook]s. The default adapter transforms
 * each state in encounter order while retaining its batch correspondence.
 */
fun interface TransformingHook<State> : BatchTransformingHook<State> {
    fun transform(state: State): State

    override fun transformBatch(states: MutationBatch<State>): MutationBatch<State> =
        states.mapStates(::transform)
}
