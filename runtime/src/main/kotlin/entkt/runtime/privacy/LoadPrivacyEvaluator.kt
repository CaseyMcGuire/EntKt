@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal

/** Evaluates one entity type's bound LOAD-privacy policy. */
@EntktInternal
fun interface LoadPrivacyEvaluator<Entity> {
    fun evaluate(
        viewerContext: ViewerContext,
        entities: List<Entity>,
    ): PrivacyEvaluation<Entity>
}

/** Bind LOAD rules, their read-client provider, and their entity-item adapter. */
@EntktInternal
fun <RuleClient, Entity, Item> loadPrivacyEvaluatorForInternalUse(
    lifecycle: String,
    unresolvedReason: String,
    rules: List<BatchPrivacyRule<RuleClient, Item>>,
    ruleClientProvider: () -> RuleClient,
    freshItem: (Entity) -> Item,
): LoadPrivacyEvaluator<Entity> {
    val decisions = privacyDecisionEvaluatorForInternalUse(
        lifecycle = lifecycle,
        rules = rules,
        ruleClientProvider = ruleClientProvider,
        freshItem = freshItem,
    )
    return LoadPrivacyEvaluator { viewerContext, entities ->
        val entitySnapshot = entities.toList()
        correlatePrivacyEvaluationForInternalUse(
            lifecycle = lifecycle,
            subjects = entitySnapshot,
            decisions = decisions.evaluate(viewerContext, entitySnapshot),
            unresolvedReason = unresolvedReason,
        )
    }
}
