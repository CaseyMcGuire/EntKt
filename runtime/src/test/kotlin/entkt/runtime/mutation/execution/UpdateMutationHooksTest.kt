@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.ActionHook
import entkt.runtime.hook.TransformingHook
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

    private val mapping = object : EntityMapping<Widget> {
        override val entityName = "Widget"
        override val clientName = "widgets"
        override val entityClass = Widget::class
        override val table = "widgets"
        override fun decode(row: Map<String, Any?>): Widget = error("No decoding in hook tests")
        override fun edgeByStorageName(storageName: String) = null
    }

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
            beforeSave = listOf(
                TransformingHook { state ->
                    events += "before-save:${state.description}"
                    BeforeSaveState("${state.description}-save")
                },
            ),
            beforeUpdate = listOf(
                TransformingHook { state ->
                    events += "before-update:${state.description}"
                    BeforeUpdateState("${state.description}-update")
                },
            ),
            afterUpdate = emptyList(),
        )

        val result = hooks.runBefore(
            entity = mapping,
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
    fun `after phase runs the supplied hooks with the updated entity`() {
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
            beforeSave = emptyList(),
            beforeUpdate = emptyList(),
            afterUpdate = listOf(ActionHook(seen::add)),
        )

        val updated = Widget(1L, "updated")
        hooks.runAfter(updated)

        assertEquals(listOf(updated), seen)
    }
}
