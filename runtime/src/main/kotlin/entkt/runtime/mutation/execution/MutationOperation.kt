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
}
