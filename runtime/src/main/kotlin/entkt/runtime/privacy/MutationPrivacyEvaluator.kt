@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.entity.EntityMapping

/**
 * Evaluates one entity's mutation privacy policy, including its optional CREATE-rule fallback.
 * The caller supplies the rule context per evaluation; no client or viewer is retained.
 */
@EntktInternal
class MutationPrivacyEvaluator<RuleClient, State>(
    entity: EntityMapping<*>,
    operation: PrivacyOperation,
    private val primary: PrivacyDecisionEvaluator<RuleClient, State, *>,
    private val fallback: PrivacyDecisionEvaluator<RuleClient, State, *>? = null,
) {
    /** Bind rules whose inputs already match the evaluated state, such as CREATE candidates. */
    constructor(
        entity: EntityMapping<*>,
        operation: PrivacyOperation,
        rules: List<BatchPrivacyRule<RuleClient, State>>,
        fallback: PrivacyDecisionEvaluator<RuleClient, State, *>? = null,
    ) : this(
        entity = entity,
        operation = operation,
        primary = PrivacyDecisionEvaluator(rules, freshItem = { state: State -> state }),
        fallback = fallback,
    )

    private val lifecycle = "${entity.entityName} ${operation.name} privacy"
    private val unresolvedReason = when (operation) {
        PrivacyOperation.CREATE -> "no create rule allowed access"
        PrivacyOperation.UPDATE -> "no update rule allowed access"
        PrivacyOperation.DELETE -> "no delete rule allowed access"
        PrivacyOperation.LOAD -> throw IllegalArgumentException("Use LoadPrivacyEvaluator for LOAD privacy")
    }

    fun evaluate(
        context: PrivacyRuleContext<RuleClient>,
        states: List<State>,
    ): PrivacyEvaluation<State> {
        val stateSnapshot = states.toList()
        val decisions = when {
            stateSnapshot.isEmpty() -> emptyList()
            context.viewerContext.viewer is Viewer.PrivacyBypass ->
                List(stateSnapshot.size) { PrivacyDecision.Allow }
            else -> evaluateDecisions(context, stateSnapshot)
        }
        return correlatePrivacyEvaluationForInternalUse(
            lifecycle = lifecycle,
            subjects = stateSnapshot,
            decisions = decisions,
            unresolvedReason = unresolvedReason,
        )
    }

    private fun evaluateDecisions(
        context: PrivacyRuleContext<RuleClient>,
        states: List<State>,
    ): List<PrivacyDecision> {
        val decisions = primary.evaluate(context, states, lifecycle)
        if (fallback == null) return decisions

        val unresolved = decisions.indices.filter { decisions[it] is PrivacyDecision.Continue }
        if (unresolved.isEmpty()) return decisions

        val fallbackDecisions = fallback.evaluate(context, unresolved.map(states::get), lifecycle)
        val finalDecisions = decisions.toMutableList()
        unresolved.forEachIndexed { resultIndex, stateIndex ->
            finalDecisions[stateIndex] = fallbackDecisions[resultIndex]
        }
        return finalDecisions
    }
}
