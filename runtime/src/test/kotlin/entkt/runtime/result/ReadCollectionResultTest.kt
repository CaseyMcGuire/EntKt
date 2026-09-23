@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.result

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class ReadCollectionResultTest {
    @Test
    fun `strict projection preserves successful values order and duplicates`() {
        val first = Any()
        val second = Any()
        val result = ReadCollectionResult.Completed(
            listOf(ReadResult.Success(second), ReadResult.Success(first), ReadResult.Success(second)),
        )

        val values = result.getOrThrow()

        assertEquals(listOf(second, first, second), values)
        assertSame(second, values[0])
        assertSame(first, values[1])
    }

    @Test
    fun `empty collections remain empty for every projection`() {
        val result = ReadCollectionResult.Completed<String>(emptyList())

        assertEquals(emptyList(), result.getOrThrow())
        assertEquals(emptyList(), result.entities())
        assertEquals(emptyList(), result.deniedAsNull().getOrThrow())
    }

    @Test
    fun `strict projection aggregates root denials in encounter order including duplicates`() {
        val first = denied(9)
        val second = denied(2)
        val result = ReadCollectionResult.Completed(
            listOf(first, ReadResult.Success("visible"), second, first),
        )

        val exception = assertFailsWith<EntPrivacyDeniedException> { result.getOrThrow() }

        assertEquals(LoadDenialOrigin.Root, exception.origin)
        assertEquals(listOf(denial(9), denial(2), denial(9)), exception.denials)
        assertEquals("LOAD denied for 3 entities", exception.message)
    }

    @Test
    fun `strict projection preserves the singular denial diagnostic format`() {
        val result = ReadCollectionResult.Completed<String>(listOf(denied(7)))

        val exception = assertFailsWith<EntPrivacyDeniedException> { result.getOrThrow() }

        assertEquals(listOf(denial(7)), exception.denials)
        assertEquals("LOAD denied for 1 User", exception.message)
    }

    @Test
    fun `entities returns individual results without unwrapping or throwing`() {
        val allowed = ReadResult.Success("visible")
        val rejected = denied(7)
        val outcomes = listOf(allowed, rejected)
        val result = ReadCollectionResult.Completed(outcomes)

        val entries = result.entities()

        assertSame(outcomes, entries)
        assertSame(allowed, entries[0])
        assertSame(rejected, entries[1])
        assertSame(rejected.exception, assertFailsWith<EntPrivacyDeniedException> { entries[1].getOrThrow() })
    }

    @Test
    fun `deniedAsNull changes only denied slots and retains the collection result API`() {
        val first = ReadResult.Success("A")
        val third = ReadResult.Success("C")
        val result = ReadCollectionResult.Completed(listOf(first, denied(7), third))

        val projected: ReadCollectionResult<String?> = result.deniedAsNull()

        assertEquals(listOf("A", null, "C"), projected.getOrThrow())
        val entries = assertIs<ReadCollectionResult.Completed<String?>>(projected).entities()
        assertSame(first, entries[0])
        assertEquals(ReadResult.Success(null), entries[1])
        assertSame(third, entries[2])
    }

    @Test
    fun `all denied roots become null entries not an empty or null collection`() {
        val result = ReadCollectionResult.Completed<String>(listOf(denied(1), denied(2), denied(3)))

        assertEquals(listOf(null, null, null), result.deniedAsNull().getOrThrow())
    }

    @Test
    fun `nullable successes remain null without being treated as denials`() {
        val result = ReadCollectionResult.Completed(
            listOf(ReadResult.Success(null), ReadResult.Success("visible")),
        )

        val strict: List<String?> = result.getOrThrow()
        val projected: List<String?> = result.deniedAsNull().getOrThrow()

        assertEquals(listOf(null, "visible"), strict)
        assertEquals(strict, projected)
    }

    @Test
    fun `projection is repeatable and does not change the original outcomes`() {
        val rejected = denied(7)
        val result = ReadCollectionResult.Completed(listOf(ReadResult.Success("visible"), rejected))

        val projected = result.deniedAsNull()

        assertEquals(projected, result.deniedAsNull())
        assertEquals(projected, projected.deniedAsNull())
        assertSame(rejected, result.entities()[1])
        assertFailsWith<EntPrivacyDeniedException> { result.getOrThrow() }
    }

    @Test
    fun `whole-query failures remain failures for every projection regardless of exception type`() {
        val failures = listOf(
            IOException("database disconnected"),
            IllegalStateException("rule execution failed"),
            selectedEdgeDenial(),
            EntPrivacyDeniedException(LoadDenialOrigin.Root, listOf(denial(7))),
        )

        for (exception in failures) {
            val result: ReadCollectionResult<String> = ReadCollectionResult.failedForInternalUse(exception)

            assertSame(exception, assertIs<ReadCollectionResult.Failed>(result).exception)
            assertSame(exception, assertFailsWith<Exception> { result.getOrThrow() })
            assertSame(result, result.deniedAsNull())
            assertSame(exception, assertFailsWith<Exception> { result.deniedAsNull().getOrThrow() })
        }
    }

    @Test
    fun `non-root entry failures are never hidden by privacy denial or null projection`() {
        val failures = listOf(IOException("row decoding failed"), selectedEdgeDenial())

        for (exception in failures) {
            val failed = ReadResult.failedForInternalUse(exception)
            for (entries in listOf(listOf(denied(7), failed), listOf(failed, denied(7)))) {
                val result = ReadCollectionResult.Completed<String>(entries)

                assertSame(entries, result.entities())
                assertSame(exception, assertFailsWith<Exception> { result.getOrThrow() })
                assertSame(exception, assertFailsWith<Exception> { result.deniedAsNull().getOrThrow() })
            }
        }
    }

    private fun denial(id: Int) = PrivacyDenial("User", EntityKey("id", id), "not visible")

    private fun denied(id: Int): ReadResult.Failed =
        assertIs<ReadResult.Failed>(
            ReadResult.failedForInternalUse(EntPrivacyDeniedException(LoadDenialOrigin.Root, listOf(denial(id)))),
        )

    private fun selectedEdgeDenial() = EntPrivacyDeniedException(
        origin = LoadDenialOrigin.SelectedEdgePath(listOf(SelectedEdgeStep("User", "posts", "Post"))),
        denials = listOf(PrivacyDenial("Post", EntityKey("id", 9), "not visible")),
    )
}
