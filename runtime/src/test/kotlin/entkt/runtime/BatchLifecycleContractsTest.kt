package entkt.runtime

import entkt.runtime.hook.BatchHook
import entkt.runtime.hook.Hook
import entkt.runtime.hook.batchHook
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRule
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.result.EntException
import entkt.runtime.result.EntMutationException
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.ValidationRule
import entkt.runtime.validation.ValidationRuleContext
import entkt.runtime.validation.batchValidationRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class BatchLifecycleContractsTest {

    private val viewerContext = PrivacyRuleContext(
        viewerContext = ViewerContext(Viewer.Anonymous),
        client = "privacy-client",
    )
    private val validationContext = ValidationRuleContext(client = "validation-client")

    private fun <C, D> decisionsFor(
        contexts: List<C>,
        run: (RuleBatch<C>) -> RuleDecisions<D>,
    ): List<D> = run(RuleBatch.from(contexts))

    private class RecordingPrivacyRule(
        private val visited: MutableList<Int>,
    ) : PrivacyRule<String, Int> {
        override fun run(
            context: PrivacyRuleContext<String>,
            item: Int,
        ): PrivacyDecision {
            visited += item
            return if (item % 2 == 0) PrivacyDecision.Allow else PrivacyDecision.Continue
        }
    }

    private class RecordingValidationRule(
        private val visited: MutableList<String>,
    ) : ValidationRule<String, String> {
        override fun validate(
            context: ValidationRuleContext<String>,
            item: String,
        ): ValidationDecision {
            visited += item
            return if (item.isBlank()) {
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
        val batch: BatchPrivacyRule<String, Int> = scalar

        assertEquals(
            listOf(
                PrivacyDecision.Continue,
                PrivacyDecision.Allow,
                PrivacyDecision.Continue,
            ),
            decisionsFor(listOf(1, 2, 3)) { batch.runBatch(viewerContext, it) },
        )
        assertEquals(listOf(1, 2, 3), visited)
    }

    @Test
    fun `batch privacy factory receives the complete ordered list once`() {
        var calls = 0
        val rule = batchPrivacyRule<String, Int> { context, batch ->
            calls++
            assertEquals("privacy-client", context.client)
            batch.decideEach { PrivacyDecision.Deny("denied $it") }
        }

        val decisions = decisionsFor(listOf(3, 1, 2)) { rule.runBatch(viewerContext, it) }

        assertEquals(1, calls)
        assertEquals(
            listOf("denied 3", "denied 1", "denied 2"),
            decisions.map { assertIs<PrivacyDecision.Deny>(it).reason },
        )
    }

    @Test
    fun `scalar contracts support list-valued items without JVM signature collisions`() {
        val privacy = PrivacyRule<String, List<Int>> { _, item ->
            PrivacyDecision.Deny(item.joinToString())
        }
        val validation = ValidationRule<String, List<Int>> { _, _ -> ValidationDecision.Valid }
        val visited = mutableListOf<List<Int>>()
        val hook = Hook<List<Int>> { visited += it }

        assertEquals(
            listOf(PrivacyDecision.Deny("1, 2")),
            decisionsFor(listOf(listOf(1, 2))) { privacy.runBatch(viewerContext, it) },
        )
        assertEquals(
            listOf(ValidationDecision.Valid),
            decisionsFor(listOf(listOf(3, 4))) { validation.validateBatch(validationContext, it) },
        )
        hook.runBatch(listOf(listOf(5, 6)))
        assertEquals(listOf(listOf(5, 6)), visited)
    }

    @Test
    fun `batch contracts preserve contravariant assignment`() {
        val privacyForAny = batchPrivacyRule<Any?, Any?> { _, batch ->
            batch.decideEach { PrivacyDecision.Allow }
        }
        val validationForAny = batchValidationRule<Any?, Any?> { _, batch ->
            batch.decideEach { ValidationDecision.Valid }
        }
        val hookValues = mutableListOf<Any?>()
        val hookForAny = batchHook<Any?> { hookValues.addAll(it) }

        val privacyForStrings: BatchPrivacyRule<String, String> = privacyForAny
        val validationForStrings: BatchValidationRule<String, String> = validationForAny
        val hookForStrings: BatchHook<String> = hookForAny

        assertEquals(
            listOf(PrivacyDecision.Allow),
            decisionsFor(listOf("value")) { privacyForStrings.runBatch(viewerContext, it) },
        )
        assertEquals(
            listOf(ValidationDecision.Valid),
            decisionsFor(listOf("value")) {
                validationForStrings.validateBatch(validationContext, it)
            },
        )
        hookForStrings.runBatch(listOf("value"))
        assertEquals(listOf<Any?>("value"), hookValues)
    }

    @Test
    fun `existing scalar validation rule classes inherit ordered batch adaptation`() {
        val visited = mutableListOf<String>()
        val scalar = RecordingValidationRule(visited)
        val batch: BatchValidationRule<String, String> = scalar

        assertEquals(
            listOf(ValidationDecision.Valid, ValidationDecision.Invalid("blank")),
            decisionsFor(listOf("value", "")) { batch.validateBatch(validationContext, it) },
        )
        assertEquals(listOf("value", ""), visited)
    }

    @Test
    fun `batch validation factory receives the complete ordered list once`() {
        var calls = 0
        val rule = batchValidationRule<String, Int> { context, batch ->
            calls++
            assertEquals("validation-client", context.client)
            batch.decideEach { ValidationDecision.Valid }
        }

        assertEquals(
            listOf(ValidationDecision.Valid, ValidationDecision.Valid),
            decisionsFor(listOf(8, 5)) { rule.validateBatch(validationContext, it) },
        )
        assertEquals(1, calls)
    }

    @Test
    fun `batch rules can be tested directly with an empty item batch`() {
        var seenViewerContext: PrivacyRuleContext<String>? = null
        val privacy = batchPrivacyRule<String, Int> { context, batch ->
            seenViewerContext = context
            batch.decideEach { PrivacyDecision.Allow }
        }
        var seenValidationContext: ValidationRuleContext<String>? = null
        val validation = batchValidationRule<String, Int> { context, batch ->
            seenValidationContext = context
            batch.decideEach { ValidationDecision.Valid }
        }

        assertEquals(
            emptyList(),
            decisionsFor(emptyList<Int>()) { privacy.runBatch(viewerContext, it) },
        )
        assertEquals(
            emptyList(),
            decisionsFor(emptyList<Int>()) { validation.validateBatch(validationContext, it) },
        )
        assertSame(viewerContext, seenViewerContext)
        assertSame(validationContext, seenValidationContext)
    }

    @Test
    fun `public batch factory supports direct rule tests and snapshots its input`() {
        val contexts = mutableListOf("b", "a")
        val batch = RuleBatch.from(contexts)
        contexts.clear()

        val decisions = BatchLifecycleJavaCompatibility.PRIVACY_BATCH_CLASS.runBatch(
            BatchLifecycleJavaCompatibility.VIEWER_CONTEXT,
            batch,
        )
        val decorated = decisions.mapDecisions { decision ->
            if (decision == PrivacyDecision.Allow) PrivacyDecision.Continue else decision
        }

        assertEquals(listOf("b", "a"), batch)
        assertEquals(
            listOf<PrivacyDecision>(PrivacyDecision.Allow, PrivacyDecision.Allow),
            decisions,
        )
        assertEquals(
            listOf<PrivacyDecision>(PrivacyDecision.Continue, PrivacyDecision.Continue),
            decorated,
        )
        assertFailsWith<UnsupportedOperationException> {
            BatchLifecycleJavaCompatibility.clearRuleBatch(batch)
        }
        assertFailsWith<UnsupportedOperationException> {
            BatchLifecycleJavaCompatibility.clearRuleDecisions(decisions)
        }
    }

    @Test
    fun `named Java batch implementations use natural invariant batch parameters`() {
        assertEquals(
            listOf<PrivacyDecision>(PrivacyDecision.Allow),
            BatchLifecycleJavaCompatibility.PRIVACY_BATCH_CLASS.runBatch(
                BatchLifecycleJavaCompatibility.VIEWER_CONTEXT,
                RuleBatch.from(listOf("value")),
            ),
        )
        assertEquals(
            listOf<ValidationDecision>(ValidationDecision.Valid),
            BatchLifecycleJavaCompatibility.VALIDATION_BATCH_CLASS.validateBatch(
                BatchLifecycleJavaCompatibility.VALIDATION_CONTEXT,
                RuleBatch.from(listOf("value")),
            ),
        )
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
    fun `batch rule contract exception carries validated diagnostics`() {
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

        assertFailsWith<IllegalArgumentException> {
            EntBatchRuleContractException(
                lifecycle = "User LOAD privacy",
                expectedSize = 1,
                actualSize = null,
                invalidDecisionIndex = 0,
                foreignBatchResult = true,
            )
        }
    }
}
