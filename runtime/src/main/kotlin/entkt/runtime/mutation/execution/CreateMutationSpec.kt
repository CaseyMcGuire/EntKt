package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchHook
import entkt.runtime.mutation.CreatePreparation

/** Immutable entity-specific inputs used by the generic create lifecycle. */
@EntktInternal
class CreateMutationSpec<
    Draft,
    Candidate,
    Entity : EntEntity<*>,
    RuleClient,
    >(
    /** Generated identity, storage metadata, and row decoder for the created entity. */
    val entity: EntityMapping<Entity>,

    /** Hooks applied to the general mutation view before create-specific processing. */
    val beforeSave: MutationHookPhase<Draft>,

    /** Hooks applied to the create-specific context before values are prepared. */
    val beforeCreate: MutationHookPhase<Draft>,

    /** Hooks applied to the entities returned by a successful database write. */
    val afterCreate: List<BatchHook<Entity>>,

    /** Resolve the post-hook draft into stable storage values and a write candidate. */
    val resolveDraft: (Draft) -> CreatePreparation<Candidate>,

    /** CREATE-privacy rules evaluated against the resolved write candidates. */
    val privacy: MutationPrivacyPhase<RuleClient, Candidate>,

    /** CREATE-validation rules evaluated against the resolved write candidates. */
    val validation: MutationValidationPhase<RuleClient, Candidate>,
)
