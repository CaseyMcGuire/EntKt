package entkt.runtime.result

import entkt.runtime.validation.ValidationDecision

/**
 * A single validation violation surfaced through
 * [EntValidationException]. The canonical shape callers branch on; the
 * [ValidationDecision.Invalid] type stays in place for the
 * validation-rule DSL side. Use
 * [ValidationDecision.Invalid.toValidationViolation] to convert between
 * them.
 */
public data class ValidationViolation(
    val message: String,
    val field: String? = null,
    val code: String? = null,
)

/**
 * Project a [ValidationDecision.Invalid] (the validation-rule DSL's
 * native shape) onto the [ValidationViolation] (the consumer shape).
 * The two types carry the same three fields; the split keeps the
 * rule-side and the result-side surfaces independently versionable.
 */
public fun ValidationDecision.Invalid.toValidationViolation(): ValidationViolation =
    ValidationViolation(message = message, field = field, code = code)
