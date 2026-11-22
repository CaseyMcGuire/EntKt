package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.result.EntOperation
import entkt.runtime.rule.EntRuleClient
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions
import entkt.runtime.rule.decisionsForInternalUse

/** Runs one rule list over an operation's inputs and collects every violation. */
@EntktInternal
class ValidationEvaluator<Client : EntRuleClient, Input>(
    entityName: String,
    operation: EntOperation,
    private val rules: List<BatchValidationRule<Client, Input>>,
) {
    private val lifecycle = "$entityName ${operation.name} validation"

    /**
     * Returns one violation list per input, in input order; an empty list means that input is valid.
     * Every rule sees every input, and each input's violations keep rule registration order.
     */
    fun evaluate(
        context: ValidationRuleContext<Client>,
        inputs: List<Input>,
    ): List<List<ValidationDecision.Invalid>> {
        val violations = List(inputs.size) { mutableListOf<ValidationDecision.Invalid>() }
        if (inputs.isEmpty()) {
            return violations
        }
        for (rule in rules) {
            val batch = RuleBatch.from(inputs)
            val decisions = checkedDecisions(batch, rule.validateBatch(context, batch))
            decisions.forEachIndexed { index, decision ->
                when (decision) {
                    ValidationDecision.Valid -> Unit
                    is ValidationDecision.Invalid -> violations[index] += decision
                    else -> throw EntBatchRuleContractException(
                        lifecycle = lifecycle,
                        expectedSize = batch.size,
                        actualSize = decisions.size,
                        invalidDecisionIndex = index,
                    )
                }
            }
        }
        return violations
    }

    private fun checkedDecisions(
        batch: RuleBatch<*>,
        returned: RuleDecisions<ValidationDecision>?,
    ): List<*> {
        // Java callbacks can violate Kotlin's non-null result and element types.
        val decisions = returned?.let { batch.decisionsForInternalUse(lifecycle, it) }
            ?: throw EntBatchRuleContractException(lifecycle, batch.size, actualSize = null)
        if (decisions.size != batch.size) {
            throw EntBatchRuleContractException(lifecycle, batch.size, decisions.size)
        }
        return decisions
    }
}
