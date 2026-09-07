package entkt.runtime.hook

/**
 * A lifecycle callback that performs an action without returning replacement state.
 *
 * The callback may perform side effects or throw. Scalar hooks are also
 * [BatchActionHook]s: the default adapter visits values serially in encounter order.
 */
fun interface ActionHook<in T> : BatchActionHook<T> {
    fun run(element: T)

    override fun runBatch(elements: List<T>) {
        elements.forEach { run(it) }
    }
}
