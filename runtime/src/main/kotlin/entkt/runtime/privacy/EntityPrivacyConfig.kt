package entkt.runtime.privacy

import entkt.query.EntktInternal

/** Mutable privacy registrations for one entity. Generated code binds the rule types. */
class EntityPrivacyConfig<
    LoadRule : BatchPrivacyRule<*, *, *>,
    CreateRule : BatchPrivacyRule<*, *, *>,
    UpdateRule : BatchPrivacyRule<*, *, *>,
    DeleteRule : BatchPrivacyRule<*, *, *>,
> {
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

    /**
     * Copy the rule lists and derivation settings into a new configuration.
     * Later registrations on this one do not reach the copy. The rules themselves are shared.
     */
    @EntktInternal
    fun deepCopy(): EntityPrivacyConfig<LoadRule, CreateRule, UpdateRule, DeleteRule> {
        val copy = EntityPrivacyConfig<LoadRule, CreateRule, UpdateRule, DeleteRule>()
        copy.loadRules.addAll(loadRules)
        copy.createRules.addAll(createRules)
        copy.updateRules.addAll(updateRules)
        copy.deleteRules.addAll(deleteRules)
        copy.updateDerivesFromCreate = updateDerivesFromCreate
        copy.deleteDerivesFromCreate = deleteDerivesFromCreate
        return copy
    }
}
