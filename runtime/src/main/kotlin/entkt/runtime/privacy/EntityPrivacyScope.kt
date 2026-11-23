package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient

/**
 * Shared registration DSL bound to an entity's client and lifecycle item types.
 * Context-only, scalar, and batch rules share typed rule lists and registration order.
 */
abstract class EntityPrivacyScope<
    Viewer : Any,
    RuleClient : EntRuleClient,
    LoadItem,
    CreateItem,
    UpdateItem,
    DeleteItem,
> protected constructor(
    private val config: EntityPrivacyConfig<
        BatchPrivacyRule<Viewer, RuleClient, LoadItem>,
        BatchPrivacyRule<Viewer, RuleClient, CreateItem>,
        BatchPrivacyRule<Viewer, RuleClient, UpdateItem>,
        BatchPrivacyRule<Viewer, RuleClient, DeleteItem>,
    >,
) {
    /** Append context-only, scalar, or batch LOAD rules in order without evaluating them. */
    fun load(vararg rules: BatchPrivacyRule<Viewer, RuleClient, LoadItem>) {
        config.loadRules.addAll(rules)
    }

    /** Append context-only, scalar, or batch CREATE rules in order without evaluating them. */
    fun create(vararg rules: BatchPrivacyRule<Viewer, RuleClient, CreateItem>) {
        config.createRules.addAll(rules)
    }

    /** Append context-only, scalar, or batch UPDATE rules in order without evaluating them. */
    fun update(vararg rules: BatchPrivacyRule<Viewer, RuleClient, UpdateItem>) {
        config.updateRules.addAll(rules)
    }

    /** Append context-only, scalar, or batch DELETE rules in order without evaluating them. */
    fun delete(vararg rules: BatchPrivacyRule<Viewer, RuleClient, DeleteItem>) {
        config.deleteRules.addAll(rules)
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
