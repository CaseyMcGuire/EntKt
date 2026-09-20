package entkt.runtime.privacy

import entkt.query.EntktInternal

/** Mutable privacy registrations whose rule types are supplied by a generated entity subclass. */
abstract class EntityPrivacyConfig<
    LoadRule : BatchPrivacyRule<*, *>,
    CreateRule : BatchPrivacyRule<*, *>,
    UpdateRule : BatchPrivacyRule<*, *>,
    DeleteRule : BatchPrivacyRule<*, *>,
> protected constructor() {
    /** LOAD rules in registration order, including repeated registrations. */
    val loadRules: MutableList<LoadRule> = mutableListOf()

    /** CREATE rules in registration order, including repeated registrations. */
    val createRules: MutableList<CreateRule> = mutableListOf()

    /** UPDATE rules in registration order, including repeated registrations. */
    val updateRules: MutableList<UpdateRule> = mutableListOf()

    /** DELETE rules in registration order, including repeated registrations. */
    val deleteRules: MutableList<DeleteRule> = mutableListOf()

    /** Whether undecided UPDATE items fall back to CREATE privacy rules for their candidates. */
    var updateDerivesFromCreate: Boolean = false

    /** Whether undecided DELETE items fall back to CREATE privacy rules for their candidates. */
    var deleteDerivesFromCreate: Boolean = false

    /** Capture detached, immutable rule lists and the current derivation settings for a client. */
    @EntktInternal
    fun resolveForInternalUse(): ResolvedEntityPrivacyConfig<LoadRule, CreateRule, UpdateRule, DeleteRule> =
        ResolvedEntityPrivacyConfig(
            loadRules = loadRules,
            createRules = createRules,
            updateRules = updateRules,
            deleteRules = deleteRules,
            updateDerivesFromCreate = updateDerivesFromCreate,
            deleteDerivesFromCreate = deleteDerivesFromCreate,
        )
}
