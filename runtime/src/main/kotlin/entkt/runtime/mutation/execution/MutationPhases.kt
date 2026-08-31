package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.hook.BatchHook
import entkt.runtime.hook.runBatchHooksForInternalUse
import entkt.runtime.privacy.ViewerContext

/** Adapts one generated hook value type without leaking it into a mutation specification. */
@EntktInternal
fun interface MutationHookPhase<in Input> {
    fun run(viewerContext: ViewerContext, inputs: List<Input>)
}

/** Capture a typed hook list and its generated per-input adapter. */
@EntktInternal
fun <Input, HookValue> mutationHookPhaseForInternalUse(
    hooks: List<BatchHook<HookValue>>,
    value: (ViewerContext, Input) -> HookValue,
): MutationHookPhase<Input> {
    val hookSnapshot = hooks.toList()
    return MutationHookPhase { viewerContext, inputs ->
        runBatchHooksForInternalUse(
            elements = inputs.map { value(viewerContext, it) },
            hooks = hookSnapshot,
        )
    }
}
