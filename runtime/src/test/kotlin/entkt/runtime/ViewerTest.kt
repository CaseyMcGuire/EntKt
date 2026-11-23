package entkt.runtime

import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.intIdOrNull
import entkt.runtime.privacy.longIdOrNull
import entkt.runtime.privacy.stringIdOrNull
import entkt.runtime.privacy.userIdOrNull
import entkt.runtime.privacy.userOrNull
import entkt.runtime.privacy.uuidIdOrNull
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ViewerTest {

    @Test
    fun `PrivacyBypass requires a non-blank reason`() {
        assertFailsWith<IllegalArgumentException> { ViewerContext.privacyBypass_DANGEROUS("") }
        assertFailsWith<IllegalArgumentException> { ViewerContext.privacyBypass_DANGEROUS("   ") }
    }

    @Test
    fun `PrivacyBypass keeps its reason`() {
        assertEquals("migration backfill", ViewerContext.privacyBypass_DANGEROUS("migration backfill").reason)
    }

    @Test
    fun `userOrNull returns the authenticated user`() {
        val user = Viewer.User(42L)

        assertSame(user, user.userOrNull())
        assertNull(Viewer.Anonymous.userOrNull())
    }

    @Test
    fun `viewer id helpers return exact id types`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")

        assertEquals("abc", Viewer.User("abc").stringIdOrNull())
        assertEquals(7, Viewer.User(7).intIdOrNull())
        assertEquals(42L, Viewer.User(42L).longIdOrNull())
        assertEquals(uuid, Viewer.User(uuid).uuidIdOrNull())
        assertEquals(uuid, Viewer.User(uuid).userIdOrNull())

        assertNull(Viewer.User(7).longIdOrNull())
        assertNull(Viewer.User(42L).intIdOrNull())
        assertNull(Viewer.Anonymous.longIdOrNull())
    }

    @Test
    fun `privacy context id helpers delegate to viewer`() {
        val context = ViewerContext.AsViewer(Viewer.User(42L))

        assertEquals(42L, context.longIdOrNull())
        assertEquals(42L, context.userIdOrNull())
        assertSame(context.viewer.userOrNull(), context.userOrNull())
        assertNull(ViewerContext.AsViewer(Viewer.Anonymous).longIdOrNull())
    }

    @Test
    fun `a privacy bypass context has no user`() {
        val bypass = ViewerContext.privacyBypass_DANGEROUS("test")

        assertNull(bypass.userOrNull())
        assertNull(bypass.userIdOrNull())
        assertNull(bypass.longIdOrNull())
    }
}
