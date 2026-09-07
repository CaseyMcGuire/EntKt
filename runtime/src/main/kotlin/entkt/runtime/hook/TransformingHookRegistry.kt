package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

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

    /** Return an immutable ordered copy detached from subsequent configuration changes. */
    @EntktInternal
    fun snapshotForInternalUse(): List<BatchTransformingHook<State>> =
        immutableListCopy(registrations)
}
