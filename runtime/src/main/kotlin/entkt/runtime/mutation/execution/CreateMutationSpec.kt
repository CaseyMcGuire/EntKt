package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchHook
import entkt.runtime.mutation.CreatePreparation
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ValidationViolation

/** Entity-specific values and transformations used by the generic create lifecycle. */
@EntktInternal
interface CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> {
    /** Generated identity, storage metadata, and row decoder for the created entity. */
    val entity: EntityMapping<Entity>

    /** Hooks applied to the general mutation view before create-specific processing. */
    val beforeSaveHooks: List<BatchHook<BeforeSave>>

    /** Hooks applied to the create-specific context before values are prepared. */
    val beforeCreateHooks: List<BatchHook<BeforeCreate>>

    /** Hooks applied to the entities returned by a successful database write. */
    val afterCreateHooks: List<BatchHook<Entity>>

    /** Expose the restricted mutation view supplied to before-save hooks. */
    fun beforeSaveHookValue(draft: Draft): BeforeSave

    /** Build the entity-specific context supplied to before-create hooks. */
    fun beforeCreateHookValue(draft: Draft): BeforeCreate

    /** Resolve the post-hook draft into stable storage values and a write candidate. */
    fun resolve(draft: Draft): CreatePreparation<Candidate>

    /** Return one CREATE-privacy denial reason per candidate. */
    fun createDenialReasons(
        privacy: PrivacyContext,
        candidates: List<Candidate>,
    ): List<String?>

    /** Return one collection of CREATE validation violations per candidate. */
    fun validationViolations(candidates: List<Candidate>): List<List<ValidationViolation>>

    /** Return one LOAD-privacy denial per persisted entity. */
    fun loadDenials(
        privacy: PrivacyContext,
        entities: List<Entity>,
    ): List<PrivacyDenial?>
}
