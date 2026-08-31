package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.hook.HookRunner
import entkt.runtime.privacy.ViewerContext

/** Adapts one generated hook value type without leaking it into a mutation specification. */
@EntktInternal
fun interface MutationHookPhase<in Input> {
    fun run(viewerContext: ViewerContext, inputs: List<Input>)
}

/** Capture a typed hook list and its generated per-input adapter. */
@EntktInternal
fun <Input, HookValue> mutationHookPhaseForInternalUse(
    runner: HookRunner<HookValue>,
    value: (ViewerContext, Input) -> HookValue,
): MutationHookPhase<Input> {
    return MutationHookPhase { viewerContext, inputs ->
        runner.run(inputs.map { value(viewerContext, it) })
    }
}
