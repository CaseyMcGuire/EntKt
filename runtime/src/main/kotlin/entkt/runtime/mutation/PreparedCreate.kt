package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.result.ValidationViolation

/**
 * The normalized handoff produced from one generated create draft.
 *
 * Generated scalar and batch create pipelines share this carrier so defaults,
 * inline field validation, row encoding, and write-candidate construction run
 * exactly once for each draft. The two item factories create detached views of
 * that candidate for individual privacy and validation rules. Lifecycle hooks,
 * rule evaluation, and driver I/O remain outside this type.
 *
 * This is generated-code infrastructure rather than application API. It is
 * public only because generated sources compile in the consuming application
 * module, outside the runtime module's Kotlin `internal` boundary.
 */
@EntktInternal
class PreparedCreate<out PrivacyItem, out ValidationItem>(
    val values: Map<String, Any?>,
    private val privacyItem: () -> PrivacyItem,
    private val validationItem: () -> ValidationItem,
) {
    /** Build a fresh detached item for each CREATE-privacy rule invocation. */
    @EntktInternal
    fun freshPrivacyItem(): PrivacyItem = privacyItem()

    /** Build a fresh detached item for each CREATE-validation rule invocation. */
    @EntktInternal
    fun freshValidationItem(): ValidationItem = validationItem()
}

/** Result of resolving one generated create draft before lifecycle rules run. */
@EntktInternal
sealed interface CreatePreparation<out PrivacyItem, out ValidationItem> {
    /** Normalization succeeded with stable storage values and fresh rule-item factories. */
    class Ready<PrivacyItem, ValidationItem>(
        val value: PreparedCreate<PrivacyItem, ValidationItem>,
    ) : CreatePreparation<PrivacyItem, ValidationItem>

    /** Required or field-level validation rejected the draft values. */
    class Invalid(violations: List<ValidationViolation>) :
        CreatePreparation<Nothing, Nothing> {
        val violations: List<ValidationViolation> = violations.toList()

        init {
            require(this.violations.isNotEmpty()) {
                "Invalid create preparation requires at least one violation"
            }
        }
    }
}
