@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor

/**
 * Evaluates one entity type's bound LOAD-privacy policy with a per-call rule context.
 * Entity values are not defensively copied; rules must treat them and their nested state as read-only.
 */
@EntktInternal
class LoadPrivacyEvaluator<RuleClient, Entity : EntEntity<*>>(
    entity: EntityDescriptor<Entity, *>,
    rules: List<BatchPrivacyRule<RuleClient, Entity>>,
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
                freshItem = { it },
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
