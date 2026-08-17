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
