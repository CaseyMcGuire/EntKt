package entkt.runtime.hook

/**
 * A lifecycle callback that performs an action over an ordered batch without
 * returning replacement state.
 *
 * Runtime hook execution never invokes a hook with an empty list. The list
 * order is the logical operation's encounter order.
 */
interface BatchActionHook<in T> {
    fun runBatch(elements: List<T>)
}

/** Construct an explicitly batch-aware action hook. */
fun <T> batchActionHook(
    block: (List<T>) -> Unit,
): BatchActionHook<T> = object : BatchActionHook<T> {
    override fun runBatch(elements: List<T>) = block(elements)
}
