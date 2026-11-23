@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient

internal fun <Client : EntRuleClient, Item> loadEvaluator(
    entityName: String,
    rules: List<BatchPrivacyRule<TestViewer, Client, Item>>,
): LoadPrivacyEvaluator<TestViewer, Client, Item> = LoadPrivacyEvaluator(entityName, rules)

/**
 * Project final outcomes for tests of rule composition and callback contracts.
 * The evaluator builds its own rule context from [context]'s viewer context and client.
 */
internal fun <Subject, Client : EntRuleClient, Item> evaluateLoadDecisions(
    entityName: String,
    items: List<Subject>,
    rules: List<BatchPrivacyRule<TestViewer, Client, Item>>,
    context: PrivacyRuleContext<TestViewer, Client>,
    freshItem: (Subject) -> Item,
): List<PrivacyDecision> = PrivacyBatchEvaluator(entityName, PrivacyOperation.LOAD)
    .evaluate(context.viewerContext, context.client, items, rules, freshItem)
    .map { outcome ->
        when (outcome) {
            is PrivacyOutcome.Allowed -> PrivacyDecision.Allow
            is PrivacyOutcome.Denied -> PrivacyDecision.Deny(outcome.reason)
        }
    }
