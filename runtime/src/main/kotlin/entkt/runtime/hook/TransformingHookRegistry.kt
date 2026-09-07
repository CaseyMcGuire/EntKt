package entkt.runtime.hook

import entkt.query.EntktInternal

/** Ordered transforming-hook registrations for a generated configuration DSL. */
class TransformingHookRegistry<State> @EntktInternal constructor() {
    private val registrations = mutableListOf<BatchTransformingHook<State>>()

    /** Register one immutable state transformation at the end of this phase. */
    operator fun invoke(transform: (State) -> State) {
        registrations += TransformingHook(transform)
    }

    /** Register one explicitly batch-aware state transformation. */
    operator fun invoke(hook: BatchTransformingHook<State>) {
        registrations += hook
    }

    /** Resolve this mutable registry into a detached ordered runner. */
    @EntktInternal
    fun runnerForInternalUse(lifecycle: String): MutationHookRunner<State> =
        MutationHookRunner(lifecycle, registrations)
}
