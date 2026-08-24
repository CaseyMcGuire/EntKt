package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchHook
import entkt.runtime.mutation.CreatePreparation
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.validation.BatchValidationRule

/** Immutable entity-specific inputs used by the generic create lifecycle. */
@EntktInternal
class CreateMutationSpec<
    Draft,
    BeforeSave,
    BeforeCreate,
    PrivacyItem,
    ValidationItem,
    Entity : EntEntity<*>,
    PrivacyClient,
    ValidationClient,
    >(
    /** Generated identity, storage metadata, and row decoder for the created entity. */
    val entity: EntityMapping<Entity>,

    /** Hooks applied to the general mutation view before create-specific processing. */
    val beforeSave: List<BatchHook<BeforeSave>>,

    /** Hooks applied to the create-specific context before values are prepared. */
    val beforeCreate: List<BatchHook<BeforeCreate>>,

    /** Hooks applied to the entities returned by a successful database write. */
    val afterCreate: List<BatchHook<Entity>>,

    /** Resolve the post-hook draft into stable storage values and a write candidate. */
    val resolveDraft: (Draft) -> CreatePreparation<PrivacyItem, ValidationItem>,

    /** CREATE-privacy rules evaluated against the resolved write candidates. */
    val privacyRules: List<BatchPrivacyRule<PrivacyClient, PrivacyItem>>,

    /** CREATE-validation rules evaluated against the resolved write candidates. */
    val validationRules: List<BatchValidationRule<ValidationClient, ValidationItem>>,
)
