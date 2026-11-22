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
import entkt.runtime.mutation.DeleteRuleInput
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.mutation.UpdateRuleInput
import entkt.runtime.mutation.WriteCandidate
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.CreatePrivacyEvaluator
import entkt.runtime.privacy.DeletePrivacyEvaluator
import entkt.runtime.privacy.UpdatePrivacyEvaluator
import entkt.runtime.privacy.ResolvedEntityPrivacyConfig
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.EntOperation
import entkt.runtime.rule.EntRuleClient
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.ResolvedEntityValidationConfig
import entkt.runtime.validation.ValidationEvaluator

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
    where Converter : CreateMutationConverter<Draft, Candidate, Entity, BeforeCreateState>,
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
    converter: CreateMutationConverter<Draft, Candidate, Entity, BeforeCreateState>,
    privacy: ResolvedEntityPrivacyConfig<*, BatchPrivacyRule<RuleClient, Candidate>, *, *>,
    validation: ResolvedEntityValidationConfig<BatchValidationRule<RuleClient, Candidate>, *, *>,
    hookStateConverter: CreateMutationHookStateConverter<Draft, Entity, BeforeSaveState, BeforeCreateState>,
    beforeSave: List<BatchTransformingHook<BeforeSaveState>>,
    beforeCreate: List<BatchTransformingHook<BeforeCreateState>>,
    afterCreate: List<BatchActionHook<Entity>>,
): CreateManyMutationOperation<RuleClient, Draft, Candidate, Entity, BeforeSaveState, BeforeCreateState> {
    val privacyEvaluator = CreatePrivacyEvaluator(
        entityName = entity.entityName,
        rules = privacy.createRules,
    )

    val validationEvaluator = ValidationEvaluator(
        entityName = entity.entityName,
        operation = EntOperation.CREATE,
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

/** Bind UPDATE policy and hooks with one adapter serving preparation and hook conversion. */
@EntktInternal
fun <
    RuleClient : EntRuleClient,
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    Candidate : WriteCandidate<Entity>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeUpdateState : BeforeUpdateHookState<Entity>,
    RuleInput : UpdateRuleInput<Entity>,
    Adapter,
> buildUpdateOperation(
    entity: EntityMapping<Entity>,
    mutationRuntime: MutationRuntime,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, BatchPrivacyRule<RuleClient, RuleInput>, *,
    >,
    validation: ResolvedEntityValidationConfig<
        BatchValidationRule<RuleClient, Candidate>, BatchValidationRule<RuleClient, RuleInput>, *,
    >,
    adapter: Adapter,
    beforeSave: List<BatchTransformingHook<BeforeSaveState>>,
    beforeUpdate: List<BatchTransformingHook<BeforeUpdateState>>,
    afterUpdate: List<BatchActionHook<Entity>>,
): MutationOperation<RuleClient, UpdateMutationInput<Draft>, Entity>
    where Adapter : UpdateMutationAdapter<Draft, Entity, PendingEdges, Candidate, BeforeUpdateState, RuleInput>,
          Adapter : UpdateMutationHookStateConverter<Draft, Entity, PendingEdges, BeforeSaveState, BeforeUpdateState> {
    val privacyEvaluator = UpdatePrivacyEvaluator(
        entityName = entity.entityName,
        rules = privacy.updateRules,
        createRules = privacy.createRules,
        derivesFromCreate = privacy.updateDerivesFromCreate,
    )

    val validationEvaluator = ValidationEvaluator(
        entityName = entity.entityName,
        operation = EntOperation.UPDATE,
        rules = validation.updateRules,
    )
    // Derived CREATE rules validate an update, so their contract errors name UPDATE.
    val derivedCreateValidationEvaluator = if (validation.updateDerivesFromCreate) {
        ValidationEvaluator(
            entityName = entity.entityName,
            operation = EntOperation.UPDATE,
            rules = validation.createRules,
        )
    } else {
        null
    }

    val hooks = UpdateMutationHooks(
        converter = adapter,
        beforeSave = beforeSave,
        beforeUpdate = beforeUpdate,
        afterUpdate = afterUpdate,
    )

    return UpdateMutationOperation(
        entity = entity,
        mutationRuntime = mutationRuntime,
        privacyEvaluator = privacyEvaluator,
        validationEvaluator = validationEvaluator,
        derivedCreateValidationEvaluator = derivedCreateValidationEvaluator,
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
> buildDeleteMutationOperation(
    entity: EntityDescriptor<Entity, *>,
    converter: DeleteMutationConverter<Entity, Candidate>,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, *, BatchPrivacyRule<RuleClient, DeleteRuleInput<Entity, Candidate>>,
    >,
    validation: ResolvedEntityValidationConfig<
        *, *, BatchValidationRule<RuleClient, DeleteRuleInput<Entity, Candidate>>,
    >,
    beforeDelete: List<BatchActionHook<Entity>>,
    afterDelete: List<BatchActionHook<Entity>>,
): DeleteMutationOperation<RuleClient, Entity, Candidate> {
    val privacyEvaluator = DeletePrivacyEvaluator(
        entityName = entity.entityName,
        rules = privacy.deleteRules,
        createRules = privacy.createRules,
        derivesFromCreate = privacy.deleteDerivesFromCreate,
    )
    val validationEvaluator = ValidationEvaluator(
        entityName = entity.entityName,
        operation = EntOperation.DELETE,
        rules = validation.deleteRules,
    )

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
> buildDeleteManyMutationOperation(
    entity: EntityDescriptor<Entity, *>,
    converter: DeleteMutationConverter<Entity, Candidate>,
    privacy: ResolvedEntityPrivacyConfig<
        *, BatchPrivacyRule<RuleClient, Candidate>, *, BatchPrivacyRule<RuleClient, DeleteRuleInput<Entity, Candidate>>,
    >,
    validation: ResolvedEntityValidationConfig<
        *, *, BatchValidationRule<RuleClient, DeleteRuleInput<Entity, Candidate>>,
    >,
    driver: DatabaseDriver,
    readExecutionHost: ReadQueryExecutionHost,
    beforeDelete: List<BatchActionHook<Entity>>,
    afterDelete: List<BatchActionHook<Entity>>,
): DeleteManyMutationOperation<RuleClient, Entity, Candidate> {
    val privacyEvaluator = DeletePrivacyEvaluator(
        entityName = entity.entityName,
        rules = privacy.deleteRules,
        createRules = privacy.createRules,
        derivesFromCreate = privacy.deleteDerivesFromCreate,
    )
    val validationEvaluator = ValidationEvaluator(
        entityName = entity.entityName,
        operation = EntOperation.DELETE,
        rules = validation.deleteRules,
    )

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
