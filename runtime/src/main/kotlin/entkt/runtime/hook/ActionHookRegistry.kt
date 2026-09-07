package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

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
 * configuration. Generated clients snapshot each registry before exposing a
 * repository so later changes to a retained configuration scope cannot affect
 * an existing client.
 */
class ActionHookRegistry<T> @EntktInternal constructor() {
    private val registrations = mutableListOf<BatchActionHook<T>>()

    /** Register one scalar hook at the end of this lifecycle phase. */
    operator fun invoke(hook: (T) -> Unit) {
        registrations += ActionHook(hook)
    }

    /** Register one explicitly batch-aware hook at the end of this lifecycle phase. */
    operator fun invoke(hook: BatchActionHook<T>) {
        registrations += hook
    }

    /** Return an immutable ordered copy detached from subsequent configuration changes. */
    @EntktInternal
    fun snapshotForInternalUse(): List<BatchActionHook<T>> = immutableListCopy(registrations)
}
