@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal

/** One reusable mutation algorithm executed with typed invocation input. */
@EntktInternal
interface MutationOperation<in RuleClient, in Input, out Result> {
    /** Describe the request before any draft construction, hooks, or I/O. */
    fun requirements(input: Input): MutationRequirements

    fun run(
        execution: MutationExecution,
        ruleClient: RuleClient,
        input: Input,
    ): MutationCompletion<Result>

    /** Project an available result inside execution, before an owned transaction can commit. */
    fun <Mapped> mapResult(transform: (Result) -> Mapped): MutationOperation<RuleClient, Input, Mapped> =
        MappedMutationOperation(this, transform)
}

private class MappedMutationOperation<RuleClient, Input, Result, Mapped>(
    private val operation: MutationOperation<RuleClient, Input, Result>,
    private val transform: (Result) -> Mapped,
) : MutationOperation<RuleClient, Input, Mapped> {
    override fun requirements(input: Input): MutationRequirements = operation.requirements(input)

    override fun run(
        execution: MutationExecution,
        ruleClient: RuleClient,
        input: Input,
    ): MutationCompletion<Mapped> =
        when (val completion = operation.run(execution, ruleClient, input)) {
            is MutationCompletion.Ready -> MutationCompletion.Ready(transform(completion.value))
            is MutationCompletion.ReturnDenied -> completion
            is MutationCompletion.ReturnFailed -> completion
        }
}
