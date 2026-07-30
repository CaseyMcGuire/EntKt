package entkt.runtime

import entkt.runtime.query.EdgeState
import entkt.runtime.query.isLoaded
import entkt.runtime.query.loadedOrNull
import entkt.runtime.query.requireLoaded
import entkt.runtime.query.valueOrNull
import entkt.runtime.query.EdgeNotLoadedException
import entkt.runtime.result.EntException

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the EdgeState helper contract: `loadedOrNull()` preserves the
 * `Unloaded` vs `Loaded(null)` distinction by returning the wrapper,
 * `valueOrNull()` deliberately collapses it, and `requireLoaded()`
 * throws EdgeNotLoadedException only for `Unloaded` — a programming
 * error outside the EntException / EntError family.
 */
class EdgeStateTest {

    @Test
    fun `isLoaded is false for Unloaded and true for Loaded`() {
        assertFalse((EdgeState.Unloaded as EdgeState<String>).isLoaded)
        assertTrue(EdgeState.Loaded("post").isLoaded)
        assertTrue(EdgeState.Loaded(null).isLoaded)
        assertTrue(EdgeState.Loaded(emptyList<String>()).isLoaded)
    }

    @Test
    fun `loadedOrNull returns null for Unloaded`() {
        val state: EdgeState<List<String>> = EdgeState.Unloaded
        assertNull(state.loadedOrNull())
    }

    @Test
    fun `loadedOrNull returns the wrapper for a loaded null value`() {
        val state: EdgeState<String?> = EdgeState.Loaded(null)
        val loaded = state.loadedOrNull()
        assertEquals(EdgeState.Loaded(null), loaded)
        assertNull(loaded?.value)
    }

    @Test
    fun `loadedOrNull returns the wrapper for a loaded non-null value`() {
        val state: EdgeState<String?> = EdgeState.Loaded("profile")
        assertEquals(EdgeState.Loaded<String?>("profile"), state.loadedOrNull())
        assertEquals("profile", state.loadedOrNull()?.value)
    }

    @Test
    fun `valueOrNull returns the loaded value`() {
        val posts = listOf("a", "b")
        val state: EdgeState<List<String>> = EdgeState.Loaded(posts)
        assertSame(posts, state.valueOrNull())
    }

    @Test
    fun `valueOrNull deliberately collapses Unloaded and a loaded null`() {
        assertNull((EdgeState.Unloaded as EdgeState<String?>).valueOrNull())
        assertNull((EdgeState.Loaded(null) as EdgeState<String?>).valueOrNull())
    }

    @Test
    fun `requireLoaded returns non-null values and a loaded null`() {
        val posts = listOf("a")
        assertSame(posts, (EdgeState.Loaded(posts) as EdgeState<List<String>>).requireLoaded())
        assertEquals(emptyList(), (EdgeState.Loaded(emptyList<String>()) as EdgeState<List<String>>).requireLoaded())
        assertNull((EdgeState.Loaded(null) as EdgeState<String?>).requireLoaded())
    }

    @Test
    fun `requireLoaded throws EdgeNotLoadedException only for Unloaded`() {
        val state: EdgeState<List<String>> = EdgeState.Unloaded
        val exception = assertFailsWith<EdgeNotLoadedException> { state.requireLoaded() }
        assertEquals(
            "Edge was not loaded; eager-load it before calling requireLoaded()",
            exception.message,
        )
    }

    @Test
    fun `EdgeNotLoadedException is an IllegalStateException outside the EntException hierarchy`() {
        val exception: Throwable = EdgeNotLoadedException()
        assertFalse(exception is EntException)
        assertTrue(exception is IllegalStateException)
    }
}
