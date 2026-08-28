@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.evaluateBatchPrivacyRulesForInternalUse
import entkt.runtime.result.EntityKey
import entkt.runtime.result.PrivacyDenial

/** Evaluates one generated entity's captured LOAD-privacy policy. */
@EntktInternal
fun interface LoadPrivacyPhase<Entity : EntEntity<*>> {
    /** Return one positionally aligned denial, or `null`, for each supplied entity. */
    fun denials(
        viewerContext: ViewerContext,
        entities: List<Entity>,
    ): List<PrivacyDenial?>
}

/**
 * Bind generated LOAD rules and their schema-specific item adapter to reusable runtime behavior.
 *
 * [ruleClientProvider] remains lazy so repository construction never forces the generated
 * read-only client while the root client and its repositories are still initializing.
 */
@EntktInternal
fun <Entity : EntEntity<*>, RuleClient, Item> loadPrivacyPhaseForInternalUse(
    entity: EntityMapping<Entity>,
    rules: List<BatchPrivacyRule<RuleClient, Item>>,
    ruleClientProvider: () -> RuleClient,
    freshItem: (Entity) -> Item,
): LoadPrivacyPhase<Entity> {
    val entityName = entity.entityName
    val ruleSnapshot = rules.toList()
    return LoadPrivacyPhase { viewerContext, entities ->
        if (entities.isEmpty()) {
            emptyList()
        } else {
            val entitySnapshot = entities.toList()
            if (viewerContext.viewer is Viewer.PrivacyBypass) {
                List(entitySnapshot.size) { null }
            } else {
                val decisions = evaluateBatchPrivacyRulesForInternalUse(
                    lifecycle = "$entityName LOAD privacy",
                    items = entitySnapshot,
                    rules = ruleSnapshot,
                    context = PrivacyRuleContext(viewerContext, ruleClientProvider()),
                    freshItem = freshItem,
                )
                entitySnapshot.mapIndexed { index, loadedEntity ->
                    when (val decision = decisions[index]) {
                        PrivacyDecision.Allow -> null
                        is PrivacyDecision.Deny -> PrivacyDenial(
                            entityName,
                            EntityKey("id", loadedEntity.id),
                            decision.reason,
                        )
                        PrivacyDecision.Continue -> PrivacyDenial(
                            entityName,
                            EntityKey("id", loadedEntity.id),
                            "no load rule allowed access",
                        )
                    }
                }
            }
        }
    }
}
