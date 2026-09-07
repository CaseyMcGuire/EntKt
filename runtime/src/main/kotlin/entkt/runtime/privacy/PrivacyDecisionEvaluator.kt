@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal

/**
 * A typed rule list and per-rule input converter, without a fail-closed terminal decision.
 * Used by [MutationPrivacyEvaluator] for its primary policy and optional CREATE-rule fallback.
 */
@EntktInternal
class PrivacyDecisionEvaluator<RuleClient, Subject, Item>(
    rules: List<BatchPrivacyRule<RuleClient, Item>>,
    private val freshItem: (Subject) -> Item,
) {
    private val rules = rules.toList()

    internal fun evaluate(
        context: PrivacyRuleContext<RuleClient>,
        subjects: List<Subject>,
        lifecycle: String,
    ): List<PrivacyDecision> = evaluateBatchPrivacyRulesForInternalUse(
        lifecycle = lifecycle,
        items = subjects,
        rules = rules,
        context = context,
        freshItem = freshItem,
    )
}
