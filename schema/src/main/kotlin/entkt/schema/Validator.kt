package entkt.schema

sealed interface ValidatorSpec {
    data class MinLen(val min: Int) : ValidatorSpec
    data class MaxLen(val max: Int) : ValidatorSpec
    data object NotEmpty : ValidatorSpec
    data class Match(val pattern: String, val options: Set<RegexOption> = emptySet()) : ValidatorSpec
    data class Min(val min: Number) : ValidatorSpec
    data class Max(val max: Number) : ValidatorSpec
    data object Positive : ValidatorSpec
    data object Negative : ValidatorSpec
    data object NonNegative : ValidatorSpec
}

data class Validator(
    val name: String,
    val message: String,
    val check: (Any?) -> Boolean,
    val spec: ValidatorSpec? = null,
)

object Validators {
    fun minLen(min: Int): Validator = Validator(
        name = "minLen($min)",
        message = "value must be at least $min characters",
        check = { (it as? String)?.length?.let { len -> len >= min } ?: false },
        spec = ValidatorSpec.MinLen(min),
    )

    fun maxLen(max: Int): Validator = Validator(
        name = "maxLen($max)",
        message = "value must be at most $max characters",
        check = { (it as? String)?.length?.let { len -> len <= max } ?: false },
        spec = ValidatorSpec.MaxLen(max),
    )

    fun notEmpty(): Validator = Validator(
        name = "notEmpty",
        message = "value must not be empty",
        check = { (it as? String)?.isNotEmpty() ?: false },
        spec = ValidatorSpec.NotEmpty,
    )

    fun match(pattern: Regex): Validator = Validator(
        name = "match(${pattern.pattern})",
        message = "value must match pattern ${pattern.pattern}",
        check = { (it as? String)?.let { s -> pattern.matches(s) } ?: false },
        spec = ValidatorSpec.Match(pattern.pattern, pattern.options),
    )

    fun min(min: Number): Validator = Validator(
        name = "min($min)",
        message = "value must be at least $min",
        check = { (it as? Number)?.toDouble()?.let { v -> v >= min.toDouble() } ?: false },
        spec = ValidatorSpec.Min(min),
    )

    fun max(max: Number): Validator = Validator(
        name = "max($max)",
        message = "value must be at most $max",
        check = { (it as? Number)?.toDouble()?.let { v -> v <= max.toDouble() } ?: false },
        spec = ValidatorSpec.Max(max),
    )

    fun positive(): Validator = Validator(
        name = "positive",
        message = "value must be positive",
        check = { (it as? Number)?.toDouble()?.let { v -> v > 0 } ?: false },
        spec = ValidatorSpec.Positive,
    )

    fun negative(): Validator = Validator(
        name = "negative",
        message = "value must be negative",
        check = { (it as? Number)?.toDouble()?.let { v -> v < 0 } ?: false },
        spec = ValidatorSpec.Negative,
    )

    fun nonNegative(): Validator = Validator(
        name = "nonNegative",
        message = "value must not be negative",
        check = { (it as? Number)?.toDouble()?.let { v -> v >= 0 } ?: false },
        spec = ValidatorSpec.NonNegative,
    )
}