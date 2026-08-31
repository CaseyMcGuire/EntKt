package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchHook
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.result.ValidationViolation

/** Immutable entity-specific inputs used by the generic create lifecycle. */
@EntktInternal
class CreateMutationSpec<
    Draft : CreateMutationDraft<Entity>,
    Candidate,
    Entity : EntEntity<*>,
    >(
    /** Generated identity, storage metadata, and row decoder for the created entity. */
    val entity: EntityMapping<Entity>,

    /** Hooks applied to the general mutation view before create-specific processing. */
    val beforeSave: MutationHookPhase<Draft>,

    /** Hooks applied to the create-specific context before values are prepared. */
    val beforeCreate: MutationHookPhase<Draft>,

    /** Hooks applied to the entities returned by a successful database write. */
    val afterCreate: List<BatchHook<Entity>>,

    /** Report missing required draft inputs before values are resolved. */
    val requiredInputViolations: (Draft) -> List<ValidationViolation>,

    /** Resolve the post-hook draft into stable storage values and a write candidate. */
    val resolveDraft: (Draft) -> PreparedCreate<Candidate>,

    /** Report schema-field violations on a stable resolved write candidate. */
    val fieldViolations: (Candidate) -> List<ValidationViolation>,

)
