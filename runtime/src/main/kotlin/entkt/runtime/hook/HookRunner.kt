package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

/** Runs one immutable, ordered set of lifecycle hooks. */
@EntktInternal
class HookRunner<T>(hooks: List<BatchHook<T>>) {
    private val hooks = immutableListCopy(hooks)

    /** Run every hook against the same non-empty ordered batch. */
    fun run(elements: List<T>) {
        if (elements.isEmpty()) return

        val snapshot = immutableListCopy(elements)
        for (hook in hooks) {
            hook.runBatch(snapshot)
        }
    }

    /** Rebuild the hook values immediately before each hook runs. */
    fun runFresh(elements: () -> List<T>) {
        for (hook in hooks) {
            val snapshot = immutableListCopy(elements())
            if (snapshot.isNotEmpty()) {
                hook.runBatch(snapshot)
            }
        }
    }
}
