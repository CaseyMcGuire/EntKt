@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchActionHook
import entkt.runtime.hook.BatchTransformingHook
import entkt.runtime.mutation.BeforeCreateHookState
import entkt.runtime.mutation.BeforeSaveHookState
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PreparedUpdateState
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.mutation.WriteCandidate
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.PrivacyDecisionEvaluator
import entkt.runtime.privacy.PrivacyOperation
import entkt.runtime.privacy.ResolvedEntityPrivacyConfig
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.MutationValidationEvaluator
import entkt.runtime.validation.ResolvedEntityValidationConfig
import entkt.runtime.validation.ValidationDecisionEvaluator

/** Bind CREATE policy and schema-specific dependencies to the shared scalar/batch implementation. */
@EntktInternal
fun <
    RuleClient,
    Draft : CreateMutationDraft<Entity>,
    Candidate : WriteCandidate<Entity>,
    Entity : EntEntity<*>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeCreateState : BeforeCreateHookState<Entity>,
> buildCreateManyMutationOperation(
    mutationRuntime: MutationRuntime,
    entity: EntityMapping<Entity>,
    converter: CreateMutationConverter<Draft, Candidate, Entity>,
    privacy: ResolvedEntityPrivacyConfig<*, BatchPrivacyRule<RuleClient, Candidate>, *, *>,
    validation: ResolvedEntityValidationConfig<BatchValidationRule<RuleClient, Candidate>, *, *>,
    hookStateConverter: CreateMutationHookStateConverter<Draft, Entity, BeforeSaveState, BeforeCreateState>,
    beforeSave: List<BatchTransformingHook<BeforeSaveState>>,
    beforeCreate: List<BatchTransformingHook<BeforeCreateState>>,
    afterCreate: List<BatchActionHook<Entity>>,
): CreateManyMutationOperation<RuleClient, Draft, Candidate, Entity, BeforeSaveState, BeforeCreateState> {
    val privacyEvaluator = MutationPrivacyEvaluator(
        entity = entity,
        operation = PrivacyOperation.CREATE,
        rules = privacy.createRules,
    )

    val validationEvaluator = MutationValidationEvaluator(
        lifecycle = "${entity.entityName} CREATE validation",
        rules = validation.createRules,
    )

    return CreateManyMutationOperation(
        mutationRuntime = mutationRuntime,
        entity = entity,
        converter = converter,
        privacyEvaluator = privacyEvaluator,
        validationEvaluator = validationEvaluator,
        hookStateConverter = hookStateConverter,
        beforeSave = beforeSave,
        beforeCreate = beforeCreate,
        afterCreate = afterCreate,
    )
}

/** Bind UPDATE policy, including CREATE fallback privacy and additional CREATE validation. */
@EntktInternal
fun <
    RuleClient,
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    State : PreparedUpdateState<Entity>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeUpdateState : BeforeUpdateHookState<Entity>,
    Candidate : WriteCandidate<Entity>,
    RuleInput,
> buildUpdateMutationOperation(
    entity: EntityMapping<Entity>,
    mutationRuntime: MutationRuntime,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, BatchPrivacyRule<RuleClient, RuleInput>, *,
    >,
    validation: ResolvedEntityValidationConfig<
        BatchValidationRule<RuleClient, Candidate>, BatchValidationRule<RuleClient, RuleInput>, *,
    >,
    ruleInput: (State) -> RuleInput,
    candidate: (State) -> Candidate,
    adapter: UpdateMutationAdapter<Draft, Entity, PendingEdges, State, BeforeUpdateState>,
    hooks: UpdateMutationHooks<Draft, Entity, PendingEdges, BeforeSaveState, BeforeUpdateState>,
): UpdateMutationOperation<RuleClient, Draft, Entity, PendingEdges, State, BeforeSaveState, BeforeUpdateState> {
    val privacyEvaluator = MutationPrivacyEvaluator(
        entity = entity,
        operation = PrivacyOperation.UPDATE,
        primary = PrivacyDecisionEvaluator(privacy.updateRules, ruleInput),
        fallback = if (privacy.updateDerivesFromCreate) {
            PrivacyDecisionEvaluator(privacy.createRules, candidate)
        } else {
            null
        },
    )

    val validationEvaluator = MutationValidationEvaluator(
        lifecycle = "${entity.entityName} UPDATE validation",
        primary = ValidationDecisionEvaluator(validation.updateRules, ruleInput),
        additional = if (validation.updateDerivesFromCreate) {
            ValidationDecisionEvaluator(validation.createRules, candidate)
        } else {
            null
        },
    )

    return UpdateMutationOperation(
        entity = entity,
        mutationRuntime = mutationRuntime,
        privacyEvaluator = privacyEvaluator,
        validationEvaluator = validationEvaluator,
        adapter = adapter,
        hooks = hooks,
    )
}

/** Construct one scalar DELETE operation with its own bound evaluators. */
@EntktInternal
fun <
    RuleClient,
    Entity : EntEntity<*>,
    Candidate : WriteCandidate<Entity>,
    RuleInput,
> buildDeleteMutationOperation(
    entity: EntityDescriptor<Entity, *>,
    converter: DeleteMutationConverter<Entity, Candidate>,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, *, BatchPrivacyRule<RuleClient, RuleInput>,
    >,
    validation: ResolvedEntityValidationConfig<*, *, BatchValidationRule<RuleClient, RuleInput>>,
    ruleInput: (Entity, Candidate) -> RuleInput,
    beforeDelete: List<BatchActionHook<Entity>>,
    afterDelete: List<BatchActionHook<Entity>>,
): DeleteMutationOperation<RuleClient, Entity, Candidate> {
    val privacyEvaluator = buildDeletePrivacyEvaluator(entity, privacy, ruleInput)
    val validationEvaluator = buildDeleteValidationEvaluator(entity, validation, ruleInput)

    return DeleteMutationOperation(
        entity = entity,
        converter = converter,
        privacyEvaluator = privacyEvaluator,
        validationEvaluator = validationEvaluator,
        beforeDelete = beforeDelete,
        afterDelete = afterDelete,
    )
}

/** Construct one bulk DELETE operation with its own bound evaluators and candidate query executor. */
@EntktInternal
fun <
    RuleClient,
    Entity : EntEntity<*>,
    Candidate : WriteCandidate<Entity>,
    RuleInput,
> buildDeleteManyMutationOperation(
    entity: EntityDescriptor<Entity, *>,
    converter: DeleteMutationConverter<Entity, Candidate>,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, *, BatchPrivacyRule<RuleClient, RuleInput>,
    >,
    validation: ResolvedEntityValidationConfig<*, *, BatchValidationRule<RuleClient, RuleInput>>,
    ruleInput: (Entity, Candidate) -> RuleInput,
    readQueryExecutor: ReadQueryExecutor<Entity>,
    beforeDelete: List<BatchActionHook<Entity>>,
    afterDelete: List<BatchActionHook<Entity>>,
): DeleteManyMutationOperation<RuleClient, Entity, Candidate> {
    val privacyEvaluator = buildDeletePrivacyEvaluator(entity, privacy, ruleInput)
    val validationEvaluator = buildDeleteValidationEvaluator(entity, validation, ruleInput)

    return DeleteManyMutationOperation(
        entity = entity,
        converter = converter,
        privacyEvaluator = privacyEvaluator,
        validationEvaluator = validationEvaluator,
        readQueryExecutor = readQueryExecutor,
        beforeDelete = beforeDelete,
        afterDelete = afterDelete,
    )
}

private fun <
    RuleClient,
    Entity : EntEntity<*>,
    Candidate : WriteCandidate<Entity>,
    RuleInput,
> buildDeletePrivacyEvaluator(
    entity: EntityMapping<Entity>,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, *, BatchPrivacyRule<RuleClient, RuleInput>,
    >,
    ruleInput: (Entity, Candidate) -> RuleInput,
): MutationPrivacyEvaluator<RuleClient, DeleteRuleCandidate<Entity, Candidate>> {
    val deleteRuleInput = { state: DeleteRuleCandidate<Entity, Candidate> ->
        ruleInput(state.entity, state.candidate)
    }

    return MutationPrivacyEvaluator(
        entity = entity,
        operation = PrivacyOperation.DELETE,
        primary = PrivacyDecisionEvaluator(privacy.deleteRules, deleteRuleInput),
        fallback = if (privacy.deleteDerivesFromCreate) {
            PrivacyDecisionEvaluator(
                rules = privacy.createRules,
                freshItem = { state: DeleteRuleCandidate<Entity, Candidate> -> state.candidate },
            )
        } else {
            null
        },
    )
}

private fun <
    RuleClient,
    Entity : EntEntity<*>,
    Candidate : WriteCandidate<Entity>,
    RuleInput,
> buildDeleteValidationEvaluator(
    entity: EntityMapping<Entity>,
    validation: ResolvedEntityValidationConfig<*, *, BatchValidationRule<RuleClient, RuleInput>>,
    ruleInput: (Entity, Candidate) -> RuleInput,
): MutationValidationEvaluator<RuleClient, DeleteRuleCandidate<Entity, Candidate>> {
    val deleteRuleInput = { state: DeleteRuleCandidate<Entity, Candidate> ->
        ruleInput(state.entity, state.candidate)
    }

    return MutationValidationEvaluator(
        lifecycle = "${entity.entityName} DELETE validation",
        primary = ValidationDecisionEvaluator(validation.deleteRules, deleteRuleInput),
    )
}
