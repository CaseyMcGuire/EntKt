@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor

/** Evaluates one entity type's bound LOAD-privacy policy with a per-call rule context. */
@EntktInternal
class LoadPrivacyEvaluator<RuleClient, Entity : EntEntity<*>, Item>(
    entity: EntityDescriptor<Entity, *>,
    rules: List<BatchPrivacyRule<RuleClient, Item>>,
    private val freshItem: (Entity) -> Item,
) {
    private val lifecycle = "${entity.entityName} LOAD privacy"
    private val rules = rules.toList()

    fun evaluate(
        context: PrivacyRuleContext<RuleClient>,
        entities: List<Entity>,
    ): PrivacyEvaluation<Entity> {
        val entitySnapshot = entities.toList()
        val decisions = if (context.viewerContext.viewer is Viewer.PrivacyBypass) {
            List(entitySnapshot.size) { PrivacyDecision.Allow }
        } else {
            evaluateBatchPrivacyRulesForInternalUse(
                lifecycle = lifecycle,
                items = entitySnapshot,
                rules = rules,
                context = context,
                freshItem = freshItem,
            )
        }
        return correlatePrivacyEvaluationForInternalUse(
            lifecycle = lifecycle,
            subjects = entitySnapshot,
            decisions = decisions,
            unresolvedReason = "no load rule allowed access",
        )
    }
}
