@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient

/**
 * Shared registration DSL bound to an entity's client and lifecycle item types.
 * Item types let context-only rules join each typed rule list without casts or generated adapters.
 */
abstract class EntityPrivacyScope<
    RuleClient : EntRuleClient,
    LoadItem,
    CreateItem,
    UpdateItem,
    DeleteItem,
> protected constructor(
    private val config: EntityPrivacyConfig<
        BatchPrivacyRule<RuleClient, LoadItem>,
        BatchPrivacyRule<RuleClient, CreateItem>,
        BatchPrivacyRule<RuleClient, UpdateItem>,
        BatchPrivacyRule<RuleClient, DeleteItem>,
    >,
) {
    /** Append scalar or batch LOAD rules in the supplied order without evaluating them. */
    fun load(vararg rules: BatchPrivacyRule<RuleClient, LoadItem>) {
        config.loadRules.addAll(rules)
    }

    /** Append a context-only LOAD rule, evaluated with each reached item's current context. */
    @JvmName("loadContextRule")
    fun load(rule: ContextPrivacyRule<RuleClient>) {
        config.loadRules.add(rule.asPrivacyRuleForInternalUse())
    }

    /** Append scalar or batch CREATE rules in the supplied order without evaluating them. */
    fun create(vararg rules: BatchPrivacyRule<RuleClient, CreateItem>) {
        config.createRules.addAll(rules)
    }

    /** Append a context-only CREATE rule, evaluated with each reached item's current context. */
    @JvmName("createContextRule")
    fun create(rule: ContextPrivacyRule<RuleClient>) {
        config.createRules.add(rule.asPrivacyRuleForInternalUse())
    }

    /** Append scalar or batch UPDATE rules in the supplied order without evaluating them. */
    fun update(vararg rules: BatchPrivacyRule<RuleClient, UpdateItem>) {
        config.updateRules.addAll(rules)
    }

    /** Append a context-only UPDATE rule, evaluated with each reached item's current context. */
    @JvmName("updateContextRule")
    fun update(rule: ContextPrivacyRule<RuleClient>) {
        config.updateRules.add(rule.asPrivacyRuleForInternalUse())
    }

    /** Append scalar or batch DELETE rules in the supplied order without evaluating them. */
    fun delete(vararg rules: BatchPrivacyRule<RuleClient, DeleteItem>) {
        config.deleteRules.addAll(rules)
    }

    /** Append a context-only DELETE rule, evaluated with each reached item's current context. */
    @JvmName("deleteContextRule")
    fun delete(rule: ContextPrivacyRule<RuleClient>) {
        config.deleteRules.add(rule.asPrivacyRuleForInternalUse())
    }

    /** Fall back to CREATE privacy for UPDATE candidates not decided by UPDATE rules. */
    fun updateDerivesFromCreate() {
        config.updateDerivesFromCreate = true
    }

    /** Fall back to CREATE privacy for DELETE candidates not decided by DELETE rules. */
    fun deleteDerivesFromCreate() {
        config.deleteDerivesFromCreate = true
    }
}
