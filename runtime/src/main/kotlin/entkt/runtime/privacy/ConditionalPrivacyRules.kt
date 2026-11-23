package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient

/**
 * Allow when [predicate] is true; otherwise continue to the next privacy rule.
 *
 * The predicate receives the same context and item as an ordinary [PrivacyRule]
 * and runs once per reached item, including in a batch. Exceptions propagate
 * unchanged. An Allow stops evaluation, including any later denial checks.
 */
fun <Viewer : Any, Client : EntRuleClient, Item> allowIf(
    predicate: (context: PrivacyRuleContext<Viewer, Client>, item: Item) -> Boolean,
): PrivacyRule<Viewer, Client, Item> = PrivacyRule { context, item ->
    if (predicate(context, item)) {
        PrivacyDecision.Allow
    } else {
        PrivacyDecision.Continue
    }
}

/**
 * Deny with [reason] when [predicate] is true; otherwise continue, not allow.
 *
 * The predicate receives the same context and item as an ordinary [PrivacyRule]
 * and runs once per reached item, including in a batch. Exceptions propagate
 * unchanged. Put mandatory denial checks before rules that can allow access.
 */
fun <Viewer : Any, Client : EntRuleClient, Item> denyIf(
    reason: String,
    predicate: (context: PrivacyRuleContext<Viewer, Client>, item: Item) -> Boolean,
): PrivacyRule<Viewer, Client, Item> = PrivacyRule { context, item ->
    if (predicate(context, item)) {
        PrivacyDecision.Deny(reason)
    } else {
        PrivacyDecision.Continue
    }
}
