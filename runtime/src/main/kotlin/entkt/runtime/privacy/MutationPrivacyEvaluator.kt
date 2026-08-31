@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal

/** Evaluates one mutation lifecycle's bound privacy policy. */
@EntktInternal
fun interface MutationPrivacyEvaluator<State> {
    fun evaluate(
        viewerContext: ViewerContext,
        states: List<State>,
    ): PrivacyEvaluation<State>
}

/** Bind mutation rules and an optional unresolved fallback to one final evaluator. */
@EntktInternal
fun <RuleClient, State, Item> mutationPrivacyEvaluatorForInternalUse(
    lifecycle: String,
    unresolvedReason: String,
    rules: List<BatchPrivacyRule<RuleClient, Item>>,
    ruleClientProvider: () -> RuleClient,
    freshItem: (State) -> Item,
    fallback: PrivacyDecisionEvaluator<State>? = null,
): MutationPrivacyEvaluator<State> {
    val decisions = privacyDecisionEvaluatorForInternalUse(
        lifecycle = lifecycle,
        rules = rules,
        ruleClientProvider = ruleClientProvider,
        freshItem = freshItem,
    ).withFallbackForInternalUse(lifecycle, fallback)
    return MutationPrivacyEvaluator { viewerContext, states ->
        val stateSnapshot = states.toList()
        correlatePrivacyEvaluationForInternalUse(
            lifecycle = lifecycle,
            subjects = stateSnapshot,
            decisions = decisions.evaluate(viewerContext, stateSnapshot),
            unresolvedReason = unresolvedReason,
        )
    }
}
