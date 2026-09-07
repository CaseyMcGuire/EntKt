package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

/** Runs one immutable, ordered set of lifecycle hooks. */
@EntktInternal
class HookRunner<T>(hooks: List<BatchActionHook<T>>) {
    private val hooks = immutableListCopy(hooks)

    /** Run every hook against the same non-empty ordered batch. */
    fun run(elements: List<T>) {
        if (elements.isEmpty()) return

        val snapshot = immutableListCopy(elements)
        for (hook in hooks) {
            hook.runBatch(snapshot)
        }
    }
}
