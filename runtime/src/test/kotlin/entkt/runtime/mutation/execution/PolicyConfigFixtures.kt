package entkt.runtime.mutation.execution

import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.EntityPrivacyConfig
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.EntityValidationConfig

/** Build a privacy configuration the way a policy registration would leave it. */
internal fun <
    LoadRule : BatchPrivacyRule<*, *, *>,
    CreateRule : BatchPrivacyRule<*, *, *>,
    UpdateRule : BatchPrivacyRule<*, *, *>,
    DeleteRule : BatchPrivacyRule<*, *, *>,
> privacyConfig(
    loadRules: List<LoadRule>,
    createRules: List<CreateRule>,
    updateRules: List<UpdateRule>,
    deleteRules: List<DeleteRule>,
    updateDerivesFromCreate: Boolean,
    deleteDerivesFromCreate: Boolean,
): EntityPrivacyConfig<LoadRule, CreateRule, UpdateRule, DeleteRule> {
    val config = EntityPrivacyConfig<LoadRule, CreateRule, UpdateRule, DeleteRule>()
    config.loadRules.addAll(loadRules)
    config.createRules.addAll(createRules)
    config.updateRules.addAll(updateRules)
    config.deleteRules.addAll(deleteRules)
    config.updateDerivesFromCreate = updateDerivesFromCreate
    config.deleteDerivesFromCreate = deleteDerivesFromCreate
    return config
}

/** Build a validation configuration the way a policy registration would leave it. */
internal fun <
    CreateRule : BatchValidationRule<*, *>,
    UpdateRule : BatchValidationRule<*, *>,
    DeleteRule : BatchValidationRule<*, *>,
> validationConfig(
    createRules: List<CreateRule>,
    updateRules: List<UpdateRule>,
    deleteRules: List<DeleteRule>,
    updateDerivesFromCreate: Boolean,
): EntityValidationConfig<CreateRule, UpdateRule, DeleteRule> {
    val config = EntityValidationConfig<CreateRule, UpdateRule, DeleteRule>()
    config.createRules.addAll(createRules)
    config.updateRules.addAll(updateRules)
    config.deleteRules.addAll(deleteRules)
    config.updateDerivesFromCreate = updateDerivesFromCreate
    return config
}
