package entkt.runtime.hook

import entkt.query.EntktInternal

/**
 * Ordered action-hook registrations used by generated configuration DSLs.
 *
 * Calling the registry preserves the generated hook DSL shape while keeping
 * registration storage and scalar-hook adaptation in runtime code:
 *
 * ```kotlin
 * afterCreate { entity -> /* ... */ }
 * afterCreate(batchActionHook { entities -> /* ... */ })
 * ```
 *
 * This registry is the mutable construction surface for an `EntClient`
 * configuration. A generated client copies its hook configuration when it is
 * constructed, so later changes to a retained configuration scope cannot
 * affect an existing client.
 */
class ActionHookRegistry<T> @EntktInternal constructor() {
    private val registrations = mutableListOf<BatchActionHook<T>>()

    /** The registered hooks in registration order. */
    @EntktInternal
    val hooks: List<BatchActionHook<T>>
        get() = registrations

    /** Register one scalar hook at the end of this lifecycle phase. */
    operator fun invoke(hook: (T) -> Unit) {
        registrations += ActionHook(hook)
    }

    /** Register one explicitly batch-aware hook at the end of this lifecycle phase. */
    operator fun invoke(hook: BatchActionHook<T>) {
        registrations += hook
    }

    /** Append the hooks registered in [source], keeping their order. */
    internal fun addAll(source: ActionHookRegistry<T>) {
        registrations.addAll(source.registrations)
    }
}
