@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.DeleteRuleInput
import entkt.runtime.mutation.WriteCandidate
import entkt.runtime.privacy.DeletePrivacyEvaluator
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.toValidationViolation
import entkt.runtime.rule.EntRuleClient
import entkt.runtime.validation.MutationValidationEvaluator
import entkt.runtime.validation.ValidationRuleContext

/** Apply the same privacy-before-validation checks to one candidate or a complete DELETE batch. */
internal fun <RuleClient : EntRuleClient, Entity : EntEntity<*>, Candidate : WriteCandidate<Entity>> evaluateDeleteRules(
    execution: MutationExecution,
    ruleClient: RuleClient,
    entityName: String,
    viewerContext: ViewerContext,
    ruleInputs: List<DeleteRuleInput<Entity, Candidate>>,
    privacyEvaluator: DeletePrivacyEvaluator<RuleClient, Candidate, DeleteRuleInput<Entity, Candidate>>,
    validationEvaluator: MutationValidationEvaluator<RuleClient, DeleteRuleInput<Entity, Candidate>>,
) {
    val denial = privacyEvaluator.evaluate(
        context = PrivacyRuleContext(viewerContext, ruleClient),
        subjects = ruleInputs,
        toRuleInput = { it },
        toCandidate = { it.candidate },
    ).firstDeniedOrNull()
    if (denial != null) {
        execution.reject(
            EntMutationPrivacyDeniedException(
                writeState = MutationWriteState.NotPersisted,
                entityType = entityName,
                operation = EntOperation.DELETE,
                entityKey = EntityKey("id", denial.subject.entity.id),
                reason = denial.reason,
            ),
        )
    }
    validationEvaluator.evaluate(ValidationRuleContext(ruleClient), ruleInputs).firstInvalidOrNull()?.let { invalid ->
        execution.reject(
            EntValidationException(
                entityType = entityName,
                operation = EntOperation.DELETE,
                violations = invalid.violations.map { it.toValidationViolation() },
            ),
        )
    }
}
