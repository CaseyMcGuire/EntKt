@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient

internal fun <Client : EntRuleClient, Item> loadEvaluator(
    entityName: String,
    rules: List<BatchPrivacyRule<Client, Item>>,
): LoadPrivacyEvaluator<Client, Item> = LoadPrivacyEvaluator(entityName, rules)

/** Project final outcomes for tests of rule composition and callback contracts. */
internal fun <Subject, Client : EntRuleClient, Item> evaluateLoadDecisions(
    entityName: String,
    items: List<Subject>,
    rules: List<BatchPrivacyRule<Client, Item>>,
    context: PrivacyRuleContext<Client>,
    freshItem: (Subject) -> Item,
): List<PrivacyDecision> = PrivacyBatchEvaluator(entityName, PrivacyOperation.LOAD)
    .evaluate(context, items, rules, freshItem)
    .map { outcome ->
        when (outcome) {
            is PrivacyOutcome.Allowed -> PrivacyDecision.Allow
            is PrivacyOutcome.Denied -> PrivacyDecision.Deny(outcome.reason)
        }
    }
