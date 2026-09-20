package entkt.runtime.validation

import entkt.query.EntktInternal

/** Mutable validation registrations whose rule types are supplied by a generated entity subclass. */
abstract class EntityValidationConfig<
    CreateRule : BatchValidationRule<*, *>,
    UpdateRule : BatchValidationRule<*, *>,
    DeleteRule : BatchValidationRule<*, *>,
> protected constructor() {
    /** CREATE rules in registration order, including repeated registrations. */
    val createRules: MutableList<CreateRule> = mutableListOf()

    /** UPDATE rules in registration order, including repeated registrations. */
    val updateRules: MutableList<UpdateRule> = mutableListOf()

    /** DELETE rules in registration order, including repeated registrations. */
    val deleteRules: MutableList<DeleteRule> = mutableListOf()

    /** Whether UPDATE also validates its candidate with the registered CREATE rules. */
    var updateDerivesFromCreate: Boolean = false

    /** Capture detached, immutable rule lists and the current derivation setting for a client. */
    @EntktInternal
    fun resolveForInternalUse(): ResolvedEntityValidationConfig<CreateRule, UpdateRule, DeleteRule> =
        ResolvedEntityValidationConfig(
            createRules = createRules,
            updateRules = updateRules,
            deleteRules = deleteRules,
            updateDerivesFromCreate = updateDerivesFromCreate,
        )
}
