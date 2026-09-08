package entkt.runtime.validation

import entkt.runtime.rule.EntRuleClient
import kotlin.reflect.KProperty1

/** Validate a non-null string's UTF-16 length. Null values are left to required-field checks. */
fun <Item> minLength(
    property: KProperty1<Item, String?>,
    min: Int,
): ValidationRule<EntRuleClient, Item> {
    require(min >= 0) { "minLength must be non-negative, got $min" }
    return fieldRule(property, "value must be at least $min characters") { it.length >= min }
}

/** Validate a non-null string's UTF-16 length. Null values are left to required-field checks. */
fun <Item> maxLength(
    property: KProperty1<Item, String?>,
    max: Int,
): ValidationRule<EntRuleClient, Item> {
    require(max >= 0) { "maxLength must be non-negative, got $max" }
    return fieldRule(property, "value must be at most $max characters") { it.length <= max }
}

/** Reject an empty string, but allow null and whitespace-only strings. */
fun <Item> notEmpty(property: KProperty1<Item, String?>): ValidationRule<EntRuleClient, Item> =
    fieldRule(property, "value must not be empty") { it.isNotEmpty() }

/** Match the whole non-null string, preserving the supplied regex options. */
fun <Item> matches(
    property: KProperty1<Item, String?>,
    pattern: Regex,
): ValidationRule<EntRuleClient, Item> =
    fieldRule(property, "value must match pattern ${pattern.pattern}") { pattern.matches(it) }

/** Inclusive numeric minimum. The bound has the property's numeric type; null values pass. */
fun <Item, Value> min(
    property: KProperty1<Item, Value?>,
    min: Value,
): ValidationRule<EntRuleClient, Item> where Value : Number, Value : Comparable<Value> {
    requireFiniteBound("min", min)
    return fieldRule(property, "value must be at least $min") {
        // Native floating-point comparisons reject NaN and treat signed zeros equally.
        when (it) {
            is Double -> it.toDouble() >= min.toDouble()
            is Float -> it.toFloat() >= min.toFloat()
            else -> it >= min
        }
    }
}

/** Inclusive numeric maximum. The bound has the property's numeric type; null values pass. */
fun <Item, Value> max(
    property: KProperty1<Item, Value?>,
    max: Value,
): ValidationRule<EntRuleClient, Item> where Value : Number, Value : Comparable<Value> {
    requireFiniteBound("max", max)
    return fieldRule(property, "value must be at most $max") {
        when (it) {
            is Double -> it.toDouble() <= max.toDouble()
            is Float -> it.toFloat() <= max.toFloat()
            else -> it <= max
        }
    }
}

/** Require a positive Kotlin numeric field value. Null values pass; NaN does not. */
fun <Item> positive(property: KProperty1<Item, Number?>): ValidationRule<EntRuleClient, Item> =
    fieldRule(property, "value must be positive") { it.toDouble() > 0 }

/** Require a negative Kotlin numeric field value. Null values pass; NaN does not. */
fun <Item> negative(property: KProperty1<Item, Number?>): ValidationRule<EntRuleClient, Item> =
    fieldRule(property, "value must be negative") { it.toDouble() < 0 }

/** Require a non-negative Kotlin numeric field value. Null values pass; NaN does not. */
fun <Item> nonNegative(property: KProperty1<Item, Number?>): ValidationRule<EntRuleClient, Item> =
    fieldRule(property, "value must not be negative") { it.toDouble() >= 0 }

private fun requireFiniteBound(rule: String, bound: Number) {
    require(!((bound is Double && !bound.isFinite()) || (bound is Float && !bound.isFinite()))) {
        "$rule bound must be a finite number, got $bound"
    }
}

/** A helper is an ordinary scalar rule, including the existing scalar-to-batch behavior. */
private fun <Item, Value : Any> fieldRule(
    property: KProperty1<Item, Value?>,
    message: String,
    check: (Value) -> Boolean,
): ValidationRule<EntRuleClient, Item> = ValidationRule { _, item ->
    val value = property.get(item)
    if (value == null || check(value)) {
        ValidationDecision.Valid
    } else {
        ValidationDecision.Invalid(message, field = property.name)
    }
}
