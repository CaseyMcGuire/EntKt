package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

/** Immutable validation-rule configuration used by a constructed client. */
@EntktInternal
public class ResolvedEntityValidationConfig<CreateRule, UpdateRule, DeleteRule> public constructor(
    createRules: List<CreateRule>,
    updateRules: List<UpdateRule>,
    deleteRules: List<DeleteRule>,
    public val updateDerivesFromCreate: Boolean,
) {
    public val createRules: List<CreateRule> = immutableListCopy(createRules)
    public val updateRules: List<UpdateRule> = immutableListCopy(updateRules)
    public val deleteRules: List<DeleteRule> = immutableListCopy(deleteRules)
}
