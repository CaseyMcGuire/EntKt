package entkt.runtime.hook

import entkt.query.EntktInternal
import java.util.Collections

/**
 * A lifecycle hook that can process an ordered batch of hook values.
 *
 * Generated lifecycle evaluators never invoke a hook with an empty list. The
 * list order is the logical operation's encounter order.
 */
interface BatchHook<in T> {
    fun runBatch(elements: List<T>)
}

/**
 * A scalar lifecycle hook that processes one value.
 *
 * Scalar hooks are also [BatchHook]s: the default adapter visits values
 * serially in encounter order.
 */
fun interface Hook<in T> : BatchHook<T> {
    fun run(element: T)

    override fun runBatch(elements: List<T>) {
        elements.forEach { run(it) }
    }
}

/**
 * Ordered lifecycle-hook registrations used by generated configuration DSLs.
 *
 * Calling the registry preserves the generated hook DSL shape while keeping
 * registration storage and scalar-hook adaptation in runtime code:
 *
 * ```kotlin
 * beforeCreate { context -> /* ... */ }
 * beforeCreate(batchHook)
 * ```
 *
 * This registry is the mutable construction surface for an `EntClient`
 * configuration. Generated clients snapshot each registry before exposing a
 * repository so later changes to a retained configuration scope cannot affect
 * an existing client.
 */
class HookRegistry<T> @EntktInternal constructor() {
    private val registrations = mutableListOf<BatchHook<T>>()

    /** Register one scalar hook at the end of this lifecycle phase. */
    operator fun invoke(hook: (T) -> Unit) {
        registrations += Hook(hook)
    }

    /** Register one explicitly batch-aware hook at the end of this lifecycle phase. */
    operator fun invoke(hook: BatchHook<T>) {
        registrations += hook
    }

    /** Return an ordered copy detached from subsequent configuration changes. */
    @EntktInternal
    fun snapshotForInternalUse(): List<BatchHook<T>> = registrations.toList()

    /** Copy an existing registry while resolving generated client configuration. */
    @EntktInternal
    fun copyFromForInternalUse(source: HookRegistry<T>) {
        registrations.clear()
        registrations += source.registrations
    }
}

/** Construct an explicitly batch-aware lifecycle hook. */
fun <T> batchHook(
    block: (List<T>) -> Unit,
): BatchHook<T> = object : BatchHook<T> {
    override fun runBatch(elements: List<T>) = block(elements)
}

/** Invoke each registered hook once with the same non-empty ordered batch. */
@EntktInternal
fun <T> runBatchHooksForInternalUse(
    elements: List<T>,
    hooks: List<BatchHook<T>>,
) {
    if (elements.isEmpty()) return

    val elementSnapshot = elements.toList()
    for (hook in hooks.toList()) {
        hook.runBatch(Collections.unmodifiableList(ArrayList(elementSnapshot)))
    }
}
