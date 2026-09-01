package entkt.runtime.hook

import entkt.query.EntktInternal

/** Ordered mutation-state hook registrations for a generated configuration DSL. */
class MutationHookRegistry<State> @EntktInternal constructor() {
    private val registrations = mutableListOf<BatchMutationHook<State>>()

    /** Register one immutable state transformation at the end of this phase. */
    operator fun invoke(transform: (State) -> State) {
        registrations += MutationHook(transform)
    }

    /** Register one explicitly batch-aware state transformation. */
    operator fun invoke(hook: BatchMutationHook<State>) {
        registrations += hook
    }

    /** Resolve this mutable registry into a detached ordered runner. */
    @EntktInternal
    fun runnerForInternalUse(lifecycle: String): MutationHookRunner<State> =
        MutationHookRunner(lifecycle, registrations)
}
