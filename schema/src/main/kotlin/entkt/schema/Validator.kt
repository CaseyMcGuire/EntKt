package entkt.schema

data class Validator(
    val name: String,
    val message: String,
    val check: (Any?) -> Boolean,
)

object Validators {
    fun minLen(min: Int): Validator = Validator(
        name = "minLen($min)",
        message = "value must be at least $min characters",
        check = { (it as? String)?.length?.let { len -> len >= min } ?: false },
    )

    fun maxLen(max: Int): Validator = Validator(
        name = "maxLen($max)",
        message = "value must be at most $max characters",
        check = { (it as? String)?.length?.let { len -> len <= max } ?: false },
    )

    fun notEmpty(): Validator = Validator(
        name = "notEmpty",
        message = "value must not be empty",
        check = { (it as? String)?.isNotEmpty() ?: false },
    )

    fun match(pattern: Regex): Validator = Validator(
        name = "match(${pattern.pattern})",
        message = "value must match pattern ${pattern.pattern}",
        check = { (it as? String)?.let { s -> pattern.matches(s) } ?: false },
    )

    fun min(min: Number): Validator = Validator(
        name = "min($min)",
        message = "value must be at least $min",
        check = { (it as? Number)?.toDouble()?.let { v -> v >= min.toDouble() } ?: false },
    )

    fun max(max: Number): Validator = Validator(
        name = "max($max)",
        message = "value must be at most $max",
        check = { (it as? Number)?.toDouble()?.let { v -> v <= max.toDouble() } ?: false },
    )

    fun positive(): Validator = Validator(
        name = "positive",
        message = "value must be positive",
        check = { (it as? Number)?.toDouble()?.let { v -> v > 0 } ?: false },
    )

    fun negative(): Validator = Validator(
        name = "negative",
        message = "value must be negative",
        check = { (it as? Number)?.toDouble()?.let { v -> v < 0 } ?: false },
    )

    fun nonNegative(): Validator = Validator(
        name = "nonNegative",
        message = "value must not be negative",
        check = { (it as? Number)?.toDouble()?.let { v -> v >= 0 } ?: false },
    )
}