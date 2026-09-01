@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.hook.Hook
import entkt.runtime.hook.HookRunner
import entkt.runtime.hook.MutationHook
import entkt.runtime.hook.MutationHookRunner
import entkt.runtime.mutation.BeforeSaveHookState
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UpdateMutationHooksTest {
    private data class Widget(
        override val id: Long,
        val description: String,
    ) : EntEntity.LongId

    private data class PendingEdges(val description: String) : UpdatePendingEdges<Widget>

    private data class BeforeSaveState(val description: String) : BeforeSaveHookState<Widget>

    private data class BeforeUpdateState(val description: String) : BeforeUpdateHookState<Widget>

    @Test
    fun `before phases convert and transform state in lifecycle order`() {
        val events = mutableListOf<String>()
        val expectedViewerContext = ViewerContext(Viewer.User(7L))
        val hooks = UpdateMutationHooks(
            converter = object :
                UpdateMutationHookStateConverter<
                    String,
                    Widget,
                    PendingEdges,
                    BeforeSaveState,
                    BeforeUpdateState,
                > {
                override fun toBeforeSaveState(draft: String): BeforeSaveState {
                    events += "convert-save:$draft"
                    return BeforeSaveState(draft)
                }

                override fun toBeforeUpdateState(
                    viewerContext: ViewerContext,
                    before: Widget,
                    pendingEdges: PendingEdges,
                    beforeSaveState: BeforeSaveState,
                ): BeforeUpdateState {
                    assertSame(expectedViewerContext, viewerContext)
                    events +=
                        "convert-update:${before.description}:${pendingEdges.description}:" +
                            beforeSaveState.description
                    return BeforeUpdateState(beforeSaveState.description)
                }
            },
            beforeSave = MutationHookRunner(
                lifecycle = "Test.beforeSave",
                hooks = listOf(
                    MutationHook { state ->
                        events += "before-save:${state.description}"
                        BeforeSaveState("${state.description}-save")
                    },
                ),
            ),
            beforeUpdate = MutationHookRunner(
                lifecycle = "Test.beforeUpdate",
                hooks = listOf(
                    MutationHook { state ->
                        events += "before-update:${state.description}"
                        BeforeUpdateState("${state.description}-update")
                    },
                ),
            ),
            afterUpdate = HookRunner(emptyList()),
        )

        val result = hooks.runBefore(
            viewerContext = expectedViewerContext,
            draft = "draft",
            before = Widget(1L, "entity"),
            pendingEdges = PendingEdges("edges"),
        )

        assertEquals("draft-save-update", result.description)
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
        val seen = mutableListOf<Widget>()
        val hooks = UpdateMutationHooks(
            converter = object :
                UpdateMutationHookStateConverter<
                    Unit,
                    Widget,
                    PendingEdges,
                    BeforeSaveState,
                    BeforeUpdateState,
                > {
                override fun toBeforeSaveState(draft: Unit) = BeforeSaveState("unused")

                override fun toBeforeUpdateState(
                    viewerContext: ViewerContext,
                    before: Widget,
                    pendingEdges: PendingEdges,
                    beforeSaveState: BeforeSaveState,
                ) = BeforeUpdateState("unused")
            },
            beforeSave = MutationHookRunner("Test.beforeSave", emptyList()),
            beforeUpdate = MutationHookRunner("Test.beforeUpdate", emptyList()),
            afterUpdate = HookRunner(listOf(Hook(seen::add))),
        )

        val updated = Widget(1L, "updated")
        hooks.runAfter(updated)

        assertEquals(listOf(updated), seen)
    }
}
