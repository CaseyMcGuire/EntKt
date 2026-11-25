package entkt.runtime.validation

import entkt.query.EntktInternal

/** Mutable validation registrations for one entity. Generated code binds the rule types. */
class EntityValidationConfig<
    CreateRule : BatchValidationRule<*, *>,
    UpdateRule : BatchValidationRule<*, *>,
    DeleteRule : BatchValidationRule<*, *>,
> {
    /** CREATE rules in registration order, including repeated registrations. */
    val createRules: MutableList<CreateRule> = mutableListOf()

    /** UPDATE rules in registration order, including repeated registrations. */
    val updateRules: MutableList<UpdateRule> = mutableListOf()

    /** DELETE rules in registration order, including repeated registrations. */
    val deleteRules: MutableList<DeleteRule> = mutableListOf()

    /** Whether UPDATE also validates its candidate with the registered CREATE rules. */
    var updateDerivesFromCreate: Boolean = false

    /**
     * Copy the rule lists and the derivation setting into a new configuration.
     * Later registrations on this one do not reach the copy. The rules themselves are shared.
     */
    @EntktInternal
    fun deepCopy(): EntityValidationConfig<CreateRule, UpdateRule, DeleteRule> {
        val copy = EntityValidationConfig<CreateRule, UpdateRule, DeleteRule>()
        copy.createRules.addAll(createRules)
        copy.updateRules.addAll(updateRules)
        copy.deleteRules.addAll(deleteRules)
        copy.updateDerivesFromCreate = updateDerivesFromCreate
        return copy
    }
}
