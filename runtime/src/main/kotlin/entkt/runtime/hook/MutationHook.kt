package entkt.runtime.hook

/**
 * A lifecycle hook that returns the immutable mutation state to pass to the
 * next hook.
 *
 * Scalar hooks are also [BatchMutationHook]s. The default adapter transforms
 * each state in encounter order while retaining its batch correspondence.
 */
fun interface MutationHook<State> : BatchMutationHook<State> {
    fun transform(state: State): State

    override fun transformBatch(states: MutationBatch<State>): MutationBatch<State> =
        states.mapStates(::transform)
}
