package entkt.runtime

import entkt.runtime.hook.BatchHook
import entkt.runtime.hook.Hook
import entkt.runtime.hook.batchHook
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRule
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.result.EntException
import entkt.runtime.result.EntMutationException
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.ValidationRule
import entkt.runtime.validation.batchValidationRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class BatchLifecycleContractsTest {

    private class RecordingPrivacyRule(
        private val visited: MutableList<Int>,
    ) : PrivacyRule<Int> {
        override fun run(ctx: Int): PrivacyDecision {
            visited += ctx
            return if (ctx % 2 == 0) PrivacyDecision.Allow else PrivacyDecision.Continue
        }
    }

    private class RecordingValidationRule(
        private val visited: MutableList<String>,
    ) : ValidationRule<String> {
        override fun validate(ctx: String): ValidationDecision {
            visited += ctx
            return if (ctx.isBlank()) {
                ValidationDecision.Invalid("blank")
            } else {
                ValidationDecision.Valid
            }
        }
    }

    @Test
    fun `existing scalar privacy rule classes inherit ordered batch adaptation`() {
        val visited = mutableListOf<Int>()
        val scalar = RecordingPrivacyRule(visited)
        val batch: BatchPrivacyRule<Int> = scalar

        assertEquals(
            listOf(
                PrivacyDecision.Continue,
                PrivacyDecision.Allow,
                PrivacyDecision.Continue,
            ),
            batch.runBatch(listOf(1, 2, 3)),
        )
        assertEquals(listOf(1, 2, 3), visited)
    }

    @Test
    fun `batch privacy factory receives the complete ordered list once`() {
        var calls = 0
        val rule = batchPrivacyRule<Int> { contexts ->
            calls++
            contexts.map { PrivacyDecision.Deny("denied $it") }
        }

        val decisions = rule.runBatch(listOf(3, 1, 2))

        assertEquals(1, calls)
        assertEquals(
            listOf("denied 3", "denied 1", "denied 2"),
            decisions.map { assertIs<PrivacyDecision.Deny>(it).reason },
        )
    }

    @Test
    fun `scalar contracts support list-valued contexts without JVM signature collisions`() {
        val privacy = PrivacyRule<List<Int>> { PrivacyDecision.Deny(it.joinToString()) }
        val validation = ValidationRule<List<Int>> { ValidationDecision.Valid }
        val visited = mutableListOf<List<Int>>()
        val hook = Hook<List<Int>> { visited += it }

        assertEquals(
            listOf(PrivacyDecision.Deny("1, 2")),
            privacy.runBatch(listOf(listOf(1, 2))),
        )
        assertEquals(
            listOf(ValidationDecision.Valid),
            validation.validateBatch(listOf(listOf(3, 4))),
        )
        hook.runBatch(listOf(listOf(5, 6)))
        assertEquals(listOf(listOf(5, 6)), visited)
    }

    @Test
    fun `batch contracts preserve contravariant assignment`() {
        val privacyForAny = batchPrivacyRule<Any?> { contexts ->
            contexts.map { PrivacyDecision.Allow }
        }
        val validationForAny = batchValidationRule<Any?> { contexts ->
            contexts.map { ValidationDecision.Valid }
        }
        val hookValues = mutableListOf<Any?>()
        val hookForAny = batchHook<Any?> { hookValues.addAll(it) }

        val privacyForStrings: BatchPrivacyRule<String> = privacyForAny
        val validationForStrings: BatchValidationRule<String> = validationForAny
        val hookForStrings: BatchHook<String> = hookForAny

        assertEquals(
            listOf(PrivacyDecision.Allow),
            privacyForStrings.runBatch(listOf("value")),
        )
        assertEquals(
            listOf(ValidationDecision.Valid),
            validationForStrings.validateBatch(listOf("value")),
        )
        hookForStrings.runBatch(listOf("value"))
        assertEquals(listOf<Any?>("value"), hookValues)
    }

    @Test
    fun `existing scalar validation rule classes inherit ordered batch adaptation`() {
        val visited = mutableListOf<String>()
        val scalar = RecordingValidationRule(visited)
        val batch: BatchValidationRule<String> = scalar

        assertEquals(
            listOf(ValidationDecision.Valid, ValidationDecision.Invalid("blank")),
            batch.validateBatch(listOf("value", "")),
        )
        assertEquals(listOf("value", ""), visited)
    }

    @Test
    fun `batch validation factory receives the complete ordered list once`() {
        var calls = 0
        val rule = batchValidationRule<Int> { contexts ->
            calls++
            contexts.map { ValidationDecision.Valid }
        }

        assertEquals(
            listOf(ValidationDecision.Valid, ValidationDecision.Valid),
            rule.validateBatch(listOf(8, 5)),
        )
        assertEquals(1, calls)
    }

    @Test
    fun `scalar hooks adapt to ordered batches`() {
        val visited = mutableListOf<Int>()
        val scalar = Hook<Int> { visited += it }
        val batch: BatchHook<Int> = scalar

        batch.runBatch(listOf(4, 2, 9))

        assertEquals(listOf(4, 2, 9), visited)
    }

    @Test
    fun `batch hook factory receives the complete ordered list once`() {
        val invocations = mutableListOf<List<String>>()
        val hook = batchHook<String> { invocations += it }

        hook.runBatch(listOf("a", "b"))

        assertEquals(listOf(listOf("a", "b")), invocations)
    }

    @Test
    fun `batch rule contract exception carries positional diagnostics`() {
        val exception = EntBatchRuleContractException(
            lifecycle = "User LOAD privacy",
            expectedSize = 3,
            actualSize = 2,
        )

        assertEquals("User LOAD privacy", exception.lifecycle)
        assertEquals(3, exception.expectedSize)
        assertEquals(2, exception.actualSize)
        assertEquals(
            "Batch rule contract violation for User LOAD privacy: expected 3 decisions but received 2",
            exception.message,
        )
        val frameworkException: EntException = exception
        assertFalse(frameworkException is EntMutationException)
    }
}
