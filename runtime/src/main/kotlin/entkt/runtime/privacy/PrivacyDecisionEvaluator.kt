@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.result.EntBatchRuleContractException

/** Intermediate rule decisions retained only while composing a final privacy evaluator. */
@EntktInternal
fun interface PrivacyDecisionEvaluator<Subject> {
    fun evaluate(
        viewerContext: ViewerContext,
        subjects: List<Subject>,
    ): List<PrivacyDecision>
}

/** Bind one typed privacy-rule list, read-client provider, and subject adapter. */
@EntktInternal
fun <RuleClient, Subject, Item> privacyDecisionEvaluatorForInternalUse(
    lifecycle: String,
    rules: List<BatchPrivacyRule<RuleClient, Item>>,
    ruleClientProvider: () -> RuleClient,
    freshItem: (Subject) -> Item,
): PrivacyDecisionEvaluator<Subject> {
    val ruleSnapshot = rules.toList()
    return PrivacyDecisionEvaluator { viewerContext, subjects ->
        when {
            subjects.isEmpty() -> emptyList()
            viewerContext.viewer is Viewer.PrivacyBypass ->
                List(subjects.size) { PrivacyDecision.Allow }
            else -> evaluateBatchPrivacyRulesForInternalUse(
                lifecycle = lifecycle,
                items = subjects,
                rules = ruleSnapshot,
                context = PrivacyRuleContext(viewerContext, ruleClientProvider()),
                freshItem = freshItem,
            )
        }
    }
}

/** Evaluate [fallback] only for subjects left unresolved by the primary evaluator. */
internal fun <Subject> PrivacyDecisionEvaluator<Subject>.withFallbackForInternalUse(
    lifecycle: String,
    fallback: PrivacyDecisionEvaluator<Subject>?,
): PrivacyDecisionEvaluator<Subject> {
    if (fallback == null) return this
    return PrivacyDecisionEvaluator { viewerContext, subjects ->
        val decisions = evaluate(viewerContext, subjects).toMutableList()
        if (decisions.size != subjects.size) {
            throw EntBatchRuleContractException(lifecycle, subjects.size, decisions.size)
        }
        val unresolved = decisions.indices.filter { decisions[it] is PrivacyDecision.Continue }
        if (unresolved.isNotEmpty()) {
            val fallbackDecisions = fallback.evaluate(
                viewerContext,
                unresolved.map(subjects::get),
            )
            if (fallbackDecisions.size != unresolved.size) {
                throw EntBatchRuleContractException(
                    lifecycle,
                    unresolved.size,
                    fallbackDecisions.size,
                )
            }
            unresolved.forEachIndexed { resultIndex, subjectIndex ->
                decisions[subjectIndex] = fallbackDecisions[resultIndex]
            }
        }
        decisions.toList()
    }
}
