package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.rule.EntRuleClient

/**
 * A privacy rule that depends on the viewer and client, but not on an operation item.
 * The same rule can be registered for different entities and operations that supply
 * a compatible [Client], without declaring an item type or an unused parameter.
 *
 * Like [PrivacyRule], this runs once per reached item, serially in encounter order.
 * Decisions are not cached or shared across items. Earlier Allow/Deny decisions skip
 * this rule for that item, and empty phases do not invoke it. Registration never
 * evaluates the rule or captures a viewer/client; each invocation receives the
 * current phase's [PrivacyRuleContext].
 */
fun interface ContextPrivacyRule<in Client : EntRuleClient> {
    @JvmSuppressWildcards
    fun run(context: PrivacyRuleContext<Client>): PrivacyDecision
}

/** Bind a context-only rule to a generated operation's existing item-aware evaluator. */
@EntktInternal
fun <Client : EntRuleClient, Item> ContextPrivacyRule<Client>.asPrivacyRuleForInternalUse(): PrivacyRule<Client, Item> =
    PrivacyRule { context, _ -> run(context) }
