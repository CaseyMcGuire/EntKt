package entkt.runtime.validation

/** Shared registration DSL whose lifecycle rule types are fixed by a generated entity subclass. */
abstract class EntityValidationScope<
    CreateRule : BatchValidationRule<*, *>,
    UpdateRule : BatchValidationRule<*, *>,
    DeleteRule : BatchValidationRule<*, *>,
> protected constructor(
    private val config: EntityValidationConfig<CreateRule, UpdateRule, DeleteRule>,
) {
    /** Append scalar or batch CREATE validators in the supplied order without evaluating them. */
    fun create(vararg rules: CreateRule) {
        config.createRules.addAll(rules)
    }

    /** Append scalar or batch UPDATE validators in the supplied order without evaluating them. */
    fun update(vararg rules: UpdateRule) {
        config.updateRules.addAll(rules)
    }

    /** Append scalar or batch DELETE validators in the supplied order without evaluating them. */
    fun delete(vararg rules: DeleteRule) {
        config.deleteRules.addAll(rules)
    }

    /** Also apply CREATE validators to the UPDATE candidate, in addition to UPDATE validators. */
    fun updateDerivesFromCreate() {
        config.updateDerivesFromCreate = true
    }
}
