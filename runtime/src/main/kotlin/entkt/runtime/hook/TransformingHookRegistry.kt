package entkt.runtime.hook

import entkt.query.EntktInternal

/** Ordered transforming-hook registrations for a generated configuration DSL. */
class TransformingHookRegistry<State> @EntktInternal constructor() {
    private val registrations = mutableListOf<BatchTransformingHook<State>>()

    /** The registered hooks in registration order. */
    @EntktInternal
    val hooks: List<BatchTransformingHook<State>>
        get() = registrations

    /** Register one immutable state transformation at the end of this phase. */
    operator fun invoke(transform: (State) -> State) {
        registrations += TransformingHook(transform)
    }

    /** Register one explicitly batch-aware state transformation. */
    operator fun invoke(hook: BatchTransformingHook<State>) {
        registrations += hook
    }

    /** Append the hooks registered in [source], keeping their order. */
    internal fun addAll(source: TransformingHookRegistry<State>) {
        registrations.addAll(source.registrations)
    }
}
