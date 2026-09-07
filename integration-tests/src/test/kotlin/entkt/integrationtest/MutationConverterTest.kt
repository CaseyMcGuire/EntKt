@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.ArticleCreateConverter
import entkt.integrationtest.ent.ArticleCreateDraft
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Note
import entkt.integrationtest.ent.NoteCreateConverter
import entkt.integrationtest.ent.NoteCreateDraft
import entkt.integrationtest.ent.NoteDeleteConverter
import entkt.integrationtest.ent.Reminder
import entkt.integrationtest.ent.ReminderCreateConverter
import entkt.integrationtest.ent.ReminderCreateDraft
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercise actual generated conversions without allowing any storage I/O. */
class MutationConverterTest {
    private val client = EntClient(NoopDriver)
    private val viewerContext = ViewerContext(Viewer.User(7L))

    @Test
    fun `hook conversion preserves its scope and maps required field-backed relationships`() {
        val converter = NoteCreateConverter(NoopDriver, client.hookClientScopeForInternalUse)
        val original = NoteCreateDraft().apply {
            body = "draft"
            writer = 7L
        }
        val beforeSave = converter.toBeforeSaveState(original).setBody("edited")
        val beforeCreate = converter.toBeforeCreateState(viewerContext, original, beforeSave).setWriter(9L)
        val preparation = converter.toPreparationDraft(original, beforeCreate)

        assertSame(viewerContext, beforeCreate.viewerContext)
        assertSame(client.hookClientScopeForInternalUse, beforeCreate.client)
        assertNotSame(original, preparation)
        assertEquals("draft", original.body)
        assertEquals(7L, original.writer)
        assertTrue(converter.requiredInputViolations(preparation).isEmpty())
        val prepared = converter.resolve(preparation)
        assertEquals(mapOf("body" to "edited", "author_id" to 9L), prepared.values)
        assertEquals("edited", prepared.candidate.body)
        assertEquals(9L, prepared.candidate.writer)
        assertTrue(converter.fieldViolations(prepared.candidate).isEmpty())
    }

    @Test
    fun `nullable relationship conversion preserves unset versus explicit null`() {
        val converter = ReminderCreateConverter(NoopDriver, client.hookClientScopeForInternalUse)
        val original = ReminderCreateDraft().apply { body = "reminder" }
        val state = converter.toBeforeCreateState(viewerContext, original, converter.toBeforeSaveState(original))

        val unset = converter.toPreparationDraft(original, state)
        val cleared = converter.toPreparationDraft(original, state.setAssigneeId(null))

        assertFalse(unset.isSet(Reminder.assigneeId))
        assertTrue(cleared.isSet(Reminder.assigneeId))
        assertNull(cleared.assigneeId)
        assertNull(converter.resolve(cleared).values["assignee_id"])
    }

    @Test
    fun `preparation uses the injected codec and retains mutable field isolation`() {
        val codecCalls = mutableListOf<Pair<String, String>>()
        val driver = object : DatabaseDriver by NoopDriver {
            override fun <T> copyJsonValue(table: String, column: String, value: T): T {
                codecCalls += table to column
                return value
            }
        }
        val converter = ArticleCreateConverter(driver, client.hookClientScopeForInternalUse)
        val bytes = byteArrayOf(1, 2)
        val draft = ArticleCreateDraft().apply {
            title = "article"
            published = false
            authorId = 7L
            payload = bytes
        }

        assertTrue(converter.requiredInputViolations(draft).isEmpty())
        val prepared = converter.resolve(draft)
        bytes[0] = 9

        assertContentEquals(byteArrayOf(1, 2), prepared.candidate.payload)
        assertSame(prepared.candidate.payload, prepared.values["payload"])
        assertEquals(listOf("articles" to "metadata", "articles" to "rects"), codecCalls)
    }

    @Test
    fun `delete converter maps loaded fields without repository or driver access`() {
        val entity = Note(id = 1L, body = "note", writer = 7L)

        val candidate = NoteDeleteConverter.toCandidate(entity)

        assertEquals(entity.body, candidate.body)
        assertEquals(entity.writer, candidate.writer)
    }
}
