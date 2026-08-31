package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

/** Runs one immutable, ordered set of lifecycle hooks. */
@EntktInternal
class HookRunner<T>(hooks: List<BatchHook<T>>) {
    private val hooks = immutableListCopy(hooks)

    /** Run every hook against the same non-empty ordered batch. */
    fun run(elements: List<T>) {
        runBatchHooksForInternalUse(elements, hooks)
    }

    /** Rebuild the hook values immediately before each hook runs. */
    fun runFresh(elements: () -> List<T>) {
        for (hook in hooks) {
            runBatchHooksForInternalUse(elements(), listOf(hook))
        }
    }
}
