@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.TestRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class FieldValidationRulesTest {
    private data class Candidate(
        val name: String = "Ada",
        val nickname: String? = null,
        val age: Int? = null,
        val count: Long = 0,
        val ratio: Double = 0.0,
        val score: Float = 0f,
    )

    private val context = ValidationRuleContext(TestRuleClient())

    @Test
    fun `property helpers assign to concrete client rules and report property names`() {
        val rule: ValidationRule<TestRuleClient, Candidate> = minLength(Candidate::name, 4)

        assertEquals(
            ValidationDecision.Invalid("value must be at least 4 characters", field = "name"),
            rule.validate(context, Candidate()),
        )
        assertSame(ValidationDecision.Valid, rule.validate(context, Candidate(name = "Grace")))
    }

    @Test
    fun `string boundaries whole regex matching and regex options are respected`() {
        val candidate = Candidate(name = "Ada")
        assertSame(ValidationDecision.Valid, minLength(Candidate::name, 3).validate(context, candidate))
        assertSame(ValidationDecision.Valid, maxLength(Candidate::name, 3).validate(context, candidate))
        assertIs<ValidationDecision.Invalid>(maxLength(Candidate::name, 2).validate(context, candidate))
        assertIs<ValidationDecision.Invalid>(notEmpty(Candidate::name).validate(context, Candidate(name = "")))
        assertSame(ValidationDecision.Valid, notEmpty(Candidate::name).validate(context, Candidate(name = " ")))
        assertSame(
            ValidationDecision.Valid,
            matches(Candidate::name, Regex("ada", RegexOption.IGNORE_CASE)).validate(context, candidate),
        )
        assertIs<ValidationDecision.Invalid>(matches(Candidate::name, Regex("da")).validate(context, candidate))
        // Kotlin String.length counts UTF-16 code units, not code points.
        assertIs<ValidationDecision.Invalid>(maxLength(Candidate::name, 1).validate(context, Candidate(name = "\uD83D\uDE00")))
    }

    @Test
    fun `nullable properties pass without weakening checks on non-null values`() {
        val rules = listOf(
            minLength(Candidate::nickname, 2),
            maxLength(Candidate::nickname, 3),
            notEmpty(Candidate::nickname),
            matches(Candidate::nickname, Regex("[a-z]+")),
            min(Candidate::age, 1),
            max(Candidate::age, 150),
            positive(Candidate::age),
            negative(Candidate::age),
            nonNegative(Candidate::age),
        )

        rules.forEach { assertSame(ValidationDecision.Valid, it.validate(context, Candidate())) }
        assertIs<ValidationDecision.Invalid>(minLength(Candidate::nickname, 2).validate(context, Candidate(nickname = "")))
        assertIs<ValidationDecision.Invalid>(min(Candidate::age, 1).validate(context, Candidate(age = 0)))
    }

    @Test
    fun `numeric bounds are inclusive and retain long precision`() {
        val candidate = Candidate(age = 10, count = Long.MAX_VALUE - 1)
        assertSame(ValidationDecision.Valid, min(Candidate::age, 10).validate(context, candidate))
        assertSame(ValidationDecision.Valid, max(Candidate::age, 10).validate(context, candidate))
        assertIs<ValidationDecision.Invalid>(min(Candidate::count, Long.MAX_VALUE).validate(context, candidate))
        assertIs<ValidationDecision.Invalid>(
            max(Candidate::count, Long.MAX_VALUE - 1).validate(context, candidate.copy(count = Long.MAX_VALUE)),
        )
        assertIs<ValidationDecision.Invalid>(
            min(Candidate::count, Long.MIN_VALUE + 1).validate(context, candidate.copy(count = Long.MIN_VALUE)),
        )
    }

    @Test
    fun `sign rules support integral and floating point properties`() {
        assertSame(ValidationDecision.Valid, positive(Candidate::count).validate(context, Candidate(count = Long.MAX_VALUE)))
        assertSame(ValidationDecision.Valid, negative(Candidate::age).validate(context, Candidate(age = -1)))
        assertSame(ValidationDecision.Valid, nonNegative(Candidate::score).validate(context, Candidate(score = -0f)))
        assertSame(ValidationDecision.Valid, positive(Candidate::ratio).validate(context, Candidate(ratio = Double.MIN_VALUE)))
        assertIs<ValidationDecision.Invalid>(positive(Candidate::count).validate(context, Candidate()))
        assertIs<ValidationDecision.Invalid>(negative(Candidate::score).validate(context, Candidate()))
        assertIs<ValidationDecision.Invalid>(nonNegative(Candidate::ratio).validate(context, Candidate(ratio = -1.0)))
    }

    @Test
    fun `floating point bounds reject NaN values and treat signed zeros equally`() {
        val doubleRules = listOf(
            min(Candidate::ratio, 0.0), max(Candidate::ratio, 0.0),
            positive(Candidate::ratio), negative(Candidate::ratio), nonNegative(Candidate::ratio),
        )
        val floatRules = listOf(
            min(Candidate::score, 0f), max(Candidate::score, 0f),
            positive(Candidate::score), negative(Candidate::score), nonNegative(Candidate::score),
        )
        doubleRules.forEach { assertIs<ValidationDecision.Invalid>(it.validate(context, Candidate(ratio = Double.NaN))) }
        floatRules.forEach { assertIs<ValidationDecision.Invalid>(it.validate(context, Candidate(score = Float.NaN))) }
        assertSame(ValidationDecision.Valid, min(Candidate::ratio, 0.0).validate(context, Candidate(ratio = -0.0)))
        assertSame(ValidationDecision.Valid, max(Candidate::score, -0f).validate(context, Candidate(score = 0f)))
    }

    @Test
    fun `invalid bounds fail during rule configuration`() {
        assertFailsWith<IllegalArgumentException> { minLength(Candidate::name, -1) }
        assertFailsWith<IllegalArgumentException> { maxLength(Candidate::name, -1) }
        for (bound in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { min(Candidate::ratio, bound) }
            assertFailsWith<IllegalArgumentException> { max(Candidate::ratio, bound) }
        }
        for (bound in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { min(Candidate::score, bound) }
            assertFailsWith<IllegalArgumentException> { max(Candidate::score, bound) }
        }
    }

    @Test
    fun `helpers retain batch correlation and aggregate through the existing evaluator`() {
        val invalid = Candidate(name = "", age = -1)
        val valid = Candidate(age = 30)
        val rule = minLength(Candidate::name, 2)
        val batch = RuleBatch.from(listOf(invalid, valid, invalid))

        assertEquals(
            listOf(
                ValidationDecision.Invalid("value must be at least 2 characters", field = "name"),
                ValidationDecision.Valid,
                ValidationDecision.Invalid("value must be at least 2 characters", field = "name"),
            ),
            rule.validateBatch(context, batch),
        )

        val evaluator = MutationValidationEvaluator<TestRuleClient, Candidate>(
            lifecycle = "Candidate CREATE validation",
            rules = listOf(rule, min(Candidate::age, 0)),
        )
        val evaluated = evaluator.evaluate(context, listOf(invalid, valid))
        assertEquals(listOf("name", "age"), evaluated.firstInvalidOrNull()!!.violations.map { it.field })
        assertEquals(listOf(valid), evaluated.validSubjects())
    }
}
