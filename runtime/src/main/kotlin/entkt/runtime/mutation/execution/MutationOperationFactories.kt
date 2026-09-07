@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
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
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.rule.EntRuleClient
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.MutationValidationEvaluator
import entkt.runtime.validation.ResolvedEntityValidationConfig
import entkt.runtime.validation.ValidationDecisionEvaluator

/** Assemble scalar and bulk CREATE from one converter serving both preparation and hook conversion. */
@EntktInternal
fun <
    RuleClient : EntRuleClient,
    Draft : CreateMutationDraft<Entity>,
    Candidate : WriteCandidate<Entity>,
    Entity : EntEntity<*>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeCreateState : BeforeCreateHookState<Entity>,
    Converter,
> buildCreateOperations(
    entity: EntityMapping<Entity>,
    mutationRuntime: MutationRuntime,
    converter: Converter,
    privacy: ResolvedEntityPrivacyConfig<*, BatchPrivacyRule<RuleClient, Candidate>, *, *>,
    validation: ResolvedEntityValidationConfig<BatchValidationRule<RuleClient, Candidate>, *, *>,
    beforeSave: List<BatchTransformingHook<BeforeSaveState>>,
    beforeCreate: List<BatchTransformingHook<BeforeCreateState>>,
    afterCreate: List<BatchActionHook<Entity>>,
): CreateMutationOperations<RuleClient, Draft, Entity>
    where Converter : CreateMutationConverter<Draft, Candidate, Entity>,
          Converter : CreateMutationHookStateConverter<Draft, Entity, BeforeSaveState, BeforeCreateState> {
    val many = buildCreateManyMutationOperation(
        entity = entity,
        mutationRuntime = mutationRuntime,
        converter = converter,
        privacy = privacy,
        validation = validation,
        hookStateConverter = converter,
        beforeSave = beforeSave,
        beforeCreate = beforeCreate,
        afterCreate = afterCreate,
    )
    return CreateMutationOperations(
        single = CreateMutationOperation(many),
        many = many,
    )
}

/** Bind CREATE policy and schema-specific dependencies to the shared scalar/batch implementation. */
@EntktInternal
fun <
    RuleClient : EntRuleClient,
    Draft : CreateMutationDraft<Entity>,
    Candidate : WriteCandidate<Entity>,
    Entity : EntEntity<*>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeCreateState : BeforeCreateHookState<Entity>,
> buildCreateManyMutationOperation(
    entity: EntityMapping<Entity>,
    mutationRuntime: MutationRuntime,
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

/** Bind UPDATE policy and hooks without exposing lifecycle types on the repository. */
@EntktInternal
fun <
    RuleClient : EntRuleClient,
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    State : PreparedUpdateState<Entity>,
    Candidate : WriteCandidate<Entity>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeUpdateState : BeforeUpdateHookState<Entity>,
    RuleInput,
> buildUpdateOperation(
    entity: EntityMapping<Entity>,
    mutationRuntime: MutationRuntime,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, BatchPrivacyRule<RuleClient, RuleInput>, *,
    >,
    validation: ResolvedEntityValidationConfig<
        BatchValidationRule<RuleClient, Candidate>, BatchValidationRule<RuleClient, RuleInput>, *,
    >,
    ruleInput: (State) -> RuleInput,
    adapter: UpdateMutationAdapter<Draft, Entity, PendingEdges, State, Candidate, BeforeUpdateState>,
    hookStateConverter:
        UpdateMutationHookStateConverter<Draft, Entity, PendingEdges, BeforeSaveState, BeforeUpdateState>,
    beforeSave: List<BatchTransformingHook<BeforeSaveState>>,
    beforeUpdate: List<BatchTransformingHook<BeforeUpdateState>>,
    afterUpdate: List<BatchActionHook<Entity>>,
): MutationOperation<RuleClient, UpdateMutationInput<Draft>, Entity> {
    val updateRuleInput = { prepared: PreparedUpdate<State, Candidate> -> ruleInput(prepared.state) }

    val privacyEvaluator = MutationPrivacyEvaluator(
        entity = entity,
        operation = PrivacyOperation.UPDATE,
        primary = PrivacyDecisionEvaluator(privacy.updateRules, updateRuleInput),
        fallback = if (privacy.updateDerivesFromCreate) {
            PrivacyDecisionEvaluator(privacy.createRules, PreparedUpdate<State, Candidate>::candidate)
        } else {
            null
        },
    )

    val validationEvaluator = MutationValidationEvaluator(
        lifecycle = "${entity.entityName} UPDATE validation",
        primary = ValidationDecisionEvaluator(validation.updateRules, updateRuleInput),
        additional = if (validation.updateDerivesFromCreate) {
            ValidationDecisionEvaluator(validation.createRules, PreparedUpdate<State, Candidate>::candidate)
        } else {
            null
        },
    )

    val hooks = UpdateMutationHooks(
        converter = hookStateConverter,
        beforeSave = beforeSave,
        beforeUpdate = beforeUpdate,
        afterUpdate = afterUpdate,
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
    RuleClient : EntRuleClient,
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
    RuleClient : EntRuleClient,
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
    driver: DatabaseDriver,
    readExecutionHost: ReadQueryExecutionHost,
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
        readQueryExecutor = ReadQueryExecutor(driver, readExecutionHost),
        beforeDelete = beforeDelete,
        afterDelete = afterDelete,
    )
}

private fun <
    RuleClient : EntRuleClient,
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
    RuleClient : EntRuleClient,
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
