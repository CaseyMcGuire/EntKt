package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.hook.BatchHook
import entkt.runtime.hook.runBatchHooksForInternalUse
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.evaluateBatchPrivacyRulesForInternalUse
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.ValidationRuleContext
import entkt.runtime.validation.evaluateBatchValidationRulesForInternalUse

/** Adapts one generated hook value type without leaking it into a mutation specification. */
@EntktInternal
fun interface MutationHookPhase<in Input> {
    fun run(viewerContext: ViewerContext, inputs: List<Input>)
}

/** Capture a typed hook list and its generated per-input adapter. */
@EntktInternal
fun <Input, HookValue> mutationHookPhaseForInternalUse(
    hooks: List<BatchHook<HookValue>>,
    value: (ViewerContext, Input) -> HookValue,
): MutationHookPhase<Input> {
    val hookSnapshot = hooks.toList()
    return MutationHookPhase { viewerContext, inputs ->
        runBatchHooksForInternalUse(
            elements = inputs.map { value(viewerContext, it) },
            hooks = hookSnapshot,
        )
    }
}

/** Evaluates a captured typed privacy-rule list against a shared candidate type. */
@EntktInternal
fun interface MutationPrivacyPhase<in RuleClient, in Candidate> {
    fun evaluate(
        viewerContext: ViewerContext,
        ruleClient: RuleClient,
        candidates: List<Candidate>,
    ): List<PrivacyDecision>
}

/** Hide a phase-local privacy item type behind a reusable typed adapter. */
@EntktInternal
fun <RuleClient, Candidate, Item> mutationPrivacyPhaseForInternalUse(
    lifecycle: String,
    rules: List<BatchPrivacyRule<RuleClient, Item>>,
    freshItem: (Candidate) -> Item,
): MutationPrivacyPhase<RuleClient, Candidate> {
    val ruleSnapshot = rules.toList()
    return MutationPrivacyPhase { viewerContext, ruleClient, candidates ->
        if (viewerContext.viewer is Viewer.PrivacyBypass) {
            List(candidates.size) { PrivacyDecision.Allow }
        } else {
            evaluateBatchPrivacyRulesForInternalUse(
                lifecycle = lifecycle,
                items = candidates,
                rules = ruleSnapshot,
                context = PrivacyRuleContext(viewerContext, ruleClient),
                freshItem = freshItem,
            )
        }
    }
}

/** Evaluate [fallback] only for candidates left unresolved by this phase. */
@EntktInternal
fun <RuleClient, Candidate> MutationPrivacyPhase<RuleClient, Candidate>.withPrivacyFallbackForInternalUse(
    fallback: MutationPrivacyPhase<RuleClient, Candidate>?,
): MutationPrivacyPhase<RuleClient, Candidate> {
    if (fallback == null) return this
    return MutationPrivacyPhase { viewerContext, ruleClient, candidates ->
        val decisions = evaluate(viewerContext, ruleClient, candidates).toMutableList()
        check(decisions.size == candidates.size) {
            "primary privacy phase returned ${decisions.size} decisions for ${candidates.size} candidates"
        }
        val unresolved = decisions.indices.filter { decisions[it] is PrivacyDecision.Continue }
        if (unresolved.isNotEmpty()) {
            val fallbackDecisions = fallback.evaluate(
                viewerContext,
                ruleClient,
                unresolved.map(candidates::get),
            )
            check(fallbackDecisions.size == unresolved.size) {
                "fallback privacy phase returned ${fallbackDecisions.size} decisions for " +
                    "${unresolved.size} candidates"
            }
            unresolved.forEachIndexed { resultIndex, candidateIndex ->
                decisions[candidateIndex] = fallbackDecisions[resultIndex]
            }
        }
        decisions.toList()
    }
}

/** Evaluates a captured typed validation-rule list against a shared candidate type. */
@EntktInternal
fun interface MutationValidationPhase<in RuleClient, in Candidate> {
    fun evaluate(
        ruleClient: RuleClient,
        candidates: List<Candidate>,
    ): List<List<ValidationDecision.Invalid>>
}

/** Hide a phase-local validation item type behind a reusable typed adapter. */
@EntktInternal
fun <RuleClient, Candidate, Item> mutationValidationPhaseForInternalUse(
    lifecycle: String,
    rules: List<BatchValidationRule<RuleClient, Item>>,
    freshItem: (Candidate) -> Item,
): MutationValidationPhase<RuleClient, Candidate> {
    val ruleSnapshot = rules.toList()
    return MutationValidationPhase { ruleClient, candidates ->
        evaluateBatchValidationRulesForInternalUse(
            lifecycle = lifecycle,
            items = candidates,
            rules = ruleSnapshot,
            context = ValidationRuleContext(ruleClient),
            freshItem = freshItem,
        )
    }
}
