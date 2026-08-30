package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

/** Immutable privacy-rule configuration used by a constructed client. */
@EntktInternal
public class ResolvedEntityPrivacyConfig<LoadRule, CreateRule, UpdateRule, DeleteRule> public constructor(
    loadRules: List<LoadRule>,
    createRules: List<CreateRule>,
    updateRules: List<UpdateRule>,
    deleteRules: List<DeleteRule>,
    public val updateDerivesFromCreate: Boolean,
    public val deleteDerivesFromCreate: Boolean,
) {
    public val loadRules: List<LoadRule> = immutableListCopy(loadRules)
    public val createRules: List<CreateRule> = immutableListCopy(createRules)
    public val updateRules: List<UpdateRule> = immutableListCopy(updateRules)
    public val deleteRules: List<DeleteRule> = immutableListCopy(deleteRules)
}
