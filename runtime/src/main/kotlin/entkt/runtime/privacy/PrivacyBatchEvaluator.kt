@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.rule.EntRuleClient
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions
import entkt.runtime.rule.decisionsForInternalUse

/** Reusable batch execution with fixed diagnostics and per-call evaluation state. */
internal class PrivacyBatchEvaluator(
    entityName: String,
    operation: PrivacyOperation,
) {
    private val lifecycle = "$entityName ${operation.name} privacy"
    private val unresolvedReason = "no ${operation.name.lowercase()} rule allowed access"

    fun <Viewer : Any, Client : EntRuleClient, Subject> evaluate(
        viewerContext: ViewerContext<Viewer>,
        client: Client,
        subjects: List<Subject>,
        rules: List<BatchPrivacyRule<Viewer, Client, Subject>>,
    ): PrivacyEvaluation<Subject> = evaluate(viewerContext, client, subjects, rules) { it }

    fun <Viewer : Any, Client : EntRuleClient, Subject, Input> evaluate(
        viewerContext: ViewerContext<Viewer>,
        client: Client,
        subjects: List<Subject>,
        rules: List<BatchPrivacyRule<Viewer, Client, Input>>,
        toRuleInput: (Subject) -> Input,
    ): PrivacyEvaluation<Subject> =
        evaluatePolicy(viewerContext, client, subjects) { context, decisions ->
            applyRules(context, subjects, rules, toRuleInput, decisions)
        }

    /** Run the second rule list only for subjects left unresolved by the first. */
    fun <Viewer : Any, Client : EntRuleClient, Subject, Input, ContinuationInput> evaluate(
        viewerContext: ViewerContext<Viewer>,
        client: Client,
        subjects: List<Subject>,
        rules: List<BatchPrivacyRule<Viewer, Client, Input>>,
        toRuleInput: (Subject) -> Input,
        continuationRules: List<BatchPrivacyRule<Viewer, Client, ContinuationInput>>,
        toContinuationInput: (Subject) -> ContinuationInput,
    ): PrivacyEvaluation<Subject> =
        evaluatePolicy(viewerContext, client, subjects) { context, decisions ->
            applyRules(context, subjects, rules, toRuleInput, decisions)
            applyRules(context, subjects, continuationRules, toContinuationInput, decisions)
        }

    /**
     * A privacy bypass allows every subject without building a rule context or
     * running a rule. Otherwise one rule context is shared by the whole phase.
     */
    private fun <Viewer : Any, Client : EntRuleClient, Subject> evaluatePolicy(
        viewerContext: ViewerContext<Viewer>,
        client: Client,
        subjects: List<Subject>,
        applyPolicy: (PrivacyRuleContext<Viewer, Client>, MutableList<PrivacyDecision>) -> Unit,
    ): PrivacyEvaluation<Subject> =
        when (viewerContext) {
            is ViewerContext.PrivacyBypass -> {
                val allowed = List<PrivacyDecision>(subjects.size) { PrivacyDecision.Allow }
                result(subjects, allowed)
            }
            is ViewerContext.AsViewer -> {
                val decisions = MutableList<PrivacyDecision>(subjects.size) { PrivacyDecision.Continue }
                if (subjects.isNotEmpty()) {
                    applyPolicy(PrivacyRuleContext(viewerContext, client), decisions)
                }
                result(subjects, decisions)
            }
        }

    /** Each rule receives only unresolved subjects, converted afresh at invocation. */
    private fun <Viewer : Any, Client : EntRuleClient, Subject, Input> applyRules(
        context: PrivacyRuleContext<Viewer, Client>,
        subjects: List<Subject>,
        rules: List<BatchPrivacyRule<Viewer, Client, Input>>,
        toRuleInput: (Subject) -> Input,
        decisions: MutableList<PrivacyDecision>,
    ) {
        var activeIndexes = subjects.indices.filter { decisions[it] == PrivacyDecision.Continue }
        for (rule in rules) {
            if (activeIndexes.isEmpty()) {
                return
            }
            val batch = RuleBatch.from(activeIndexes.map { toRuleInput(subjects[it]) })
            val returned = checkedDecisions(batch, rule.runBatch(context, batch))
            val remainingIndexes = ArrayList<Int>(activeIndexes.size)
            activeIndexes.forEachIndexed { batchIndex, subjectIndex ->
                when (val decision = returned[batchIndex]) {
                    PrivacyDecision.Allow -> decisions[subjectIndex] = PrivacyDecision.Allow
                    is PrivacyDecision.Deny -> decisions[subjectIndex] = decision
                    PrivacyDecision.Continue -> remainingIndexes += subjectIndex
                    else -> throw EntBatchRuleContractException(
                        lifecycle = lifecycle,
                        expectedSize = batch.size,
                        actualSize = returned.size,
                        invalidDecisionIndex = batchIndex,
                    )
                }
            }
            activeIndexes = remainingIndexes
        }
    }

    private fun checkedDecisions(
        batch: RuleBatch<*>,
        returned: RuleDecisions<PrivacyDecision>?,
    ): List<*> {
        // Java callbacks can violate Kotlin's non-null result and element types.
        val decisions = returned?.let { batch.decisionsForInternalUse(lifecycle, it) }
            ?: throw EntBatchRuleContractException(lifecycle, batch.size, actualSize = null)
        if (decisions.size != batch.size) {
            throw EntBatchRuleContractException(lifecycle, batch.size, decisions.size)
        }
        return decisions
    }

    private fun <Subject> result(
        subjects: List<Subject>,
        decisions: List<PrivacyDecision>,
    ): PrivacyEvaluation<Subject> = PrivacyEvaluation(
        subjects.mapIndexed { index, subject ->
            when (val decision = decisions[index]) {
                PrivacyDecision.Allow -> PrivacyOutcome.Allowed(subject)
                is PrivacyDecision.Deny -> PrivacyOutcome.Denied(subject, decision.reason)
                PrivacyDecision.Continue -> PrivacyOutcome.Denied(subject, unresolvedReason)
            }
        },
    )
}
