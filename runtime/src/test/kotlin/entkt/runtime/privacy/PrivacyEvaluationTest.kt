@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.TestRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class PrivacyEvaluationTest {
    private data class Subject(val value: Int)
    private val context = PrivacyRuleContext(ViewerContext.AsViewer(TestViewer.User(7L)), TestRuleClient())

    @Test
    fun `evaluation attaches final decisions to the exact subjects`() {
        val first = Subject(1)
        val equalButDistinct = Subject(1)
        val evaluator = loadEvaluator("Subject", listOf(batchPrivacyRule<TestViewer, TestRuleClient, Subject> { _, batch ->
            batch.decideEachIndexed { index, _ ->
                if (index == 0) PrivacyDecision.Allow else PrivacyDecision.Deny("hidden")
            }
        }))

        val evaluation = evaluator.evaluate(context.viewerContext, context.client, listOf(first, equalButDistinct))

        assertEquals(2, evaluation.size)
        assertSame(first, evaluation.allowedSubjects().single())
        val denied = evaluation.firstDeniedOrNull()
        assertIs<PrivacyOutcome.Denied<Subject>>(denied)
        assertSame(equalButDistinct, denied.subject)
        assertEquals("hidden", denied.reason)
    }

    @Test
    fun `evaluation finalizes unresolved privacy as a denial`() {
        val subject = Subject(1)
        val evaluator = loadEvaluator("Subject", listOf(PrivacyRule<TestViewer, TestRuleClient, Subject> { _, _ -> PrivacyDecision.Continue }))

        val evaluation = evaluator.evaluate(context.viewerContext, context.client, listOf(subject))

        val denied = evaluation.firstDeniedOrNull()
        assertIs<PrivacyOutcome.Denied<Subject>>(denied)
        assertSame(subject, denied.subject)
        assertEquals("no load rule allowed access", denied.reason)
    }
}
