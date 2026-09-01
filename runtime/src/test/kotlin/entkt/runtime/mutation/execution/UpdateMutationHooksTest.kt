@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.hook.Hook
import entkt.runtime.hook.HookRunner
import entkt.runtime.hook.MutationHook
import entkt.runtime.hook.MutationHookRunner
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UpdateMutationHooksTest {
    @Test
    fun `before phases convert and transform state in lifecycle order`() {
        val events = mutableListOf<String>()
        val expectedViewerContext = ViewerContext(Viewer.User(7L))
        val hooks = UpdateMutationHooks(
            converter = object :
                UpdateMutationHookStateConverter<String, String, String, String, String> {
                override fun toBeforeSaveState(draft: String): String {
                    events += "convert-save:$draft"
                    return draft
                }

                override fun toBeforeUpdateState(
                    viewerContext: ViewerContext,
                    before: String,
                    pendingEdges: String,
                    beforeSaveState: String,
                ): String {
                    assertSame(expectedViewerContext, viewerContext)
                    events += "convert-update:$before:$pendingEdges:$beforeSaveState"
                    return beforeSaveState
                }
            },
            beforeSave = MutationHookRunner(
                lifecycle = "Test.beforeSave",
                hooks = listOf(
                    MutationHook { state ->
                        events += "before-save:$state"
                        "$state-save"
                    },
                ),
            ),
            beforeUpdate = MutationHookRunner(
                lifecycle = "Test.beforeUpdate",
                hooks = listOf(
                    MutationHook { state ->
                        events += "before-update:$state"
                        "$state-update"
                    },
                ),
            ),
            afterUpdate = HookRunner(emptyList()),
        )

        val result = hooks.runBefore(
            viewerContext = expectedViewerContext,
            draft = "draft",
            before = "entity",
            pendingEdges = "edges",
        )

        assertEquals("draft-save-update", result)
        assertEquals(
            listOf(
                "convert-save:draft",
                "before-save:draft",
                "convert-update:entity:edges:draft-save",
                "before-update:draft-save",
            ),
            events,
        )
    }

    @Test
    fun `after phase delegates the updated entity to its runner`() {
        val seen = mutableListOf<String>()
        val hooks = UpdateMutationHooks(
            converter = object :
                UpdateMutationHookStateConverter<Unit, String, Unit, Unit, Unit> {
                override fun toBeforeSaveState(draft: Unit) = Unit

                override fun toBeforeUpdateState(
                    viewerContext: ViewerContext,
                    before: String,
                    pendingEdges: Unit,
                    beforeSaveState: Unit,
                ) = Unit
            },
            beforeSave = MutationHookRunner("Test.beforeSave", emptyList()),
            beforeUpdate = MutationHookRunner("Test.beforeUpdate", emptyList()),
            afterUpdate = HookRunner(listOf(Hook(seen::add))),
        )

        hooks.runAfter("updated")

        assertEquals(listOf("updated"), seen)
    }
}
