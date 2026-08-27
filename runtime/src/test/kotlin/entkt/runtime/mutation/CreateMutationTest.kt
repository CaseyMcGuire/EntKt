@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationAlreadyConsumedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CreateMutationTest {
    private val viewerContext = ViewerContext.privacyBypass_DANGEROUS("test")

    private class Draft(var title: String? = null)

    private data class Article(
        override val id: Long,
        val title: String,
    ) : EntEntity.LongId

    private class RecordingRepository : CreateMutationRepository<Draft, Article> {
        var savedDraft: Draft? = null
        var loadedDraft: Draft? = null

        override fun saveCreation(
            viewerContext: ViewerContext,
            draft: Draft,
        ): MutationResult<Unit> {
            savedDraft = draft
            return MutationResult.Success(Unit)
        }

        override fun saveAndLoadCreation(
            viewerContext: ViewerContext,
            draft: Draft,
        ): MutationResult<Article> {
            loadedDraft = draft
            return MutationResult.Success(Article(1, checkNotNull(draft.title)))
        }
    }

    @Test
    fun `configuration remains mutable until the first terminal`() {
        val draft = Draft("first")
        val repository = RecordingRepository()
        val mutation = CreateMutation(draft, repository)

        val returned = mutation.configure { title = title.orEmpty() + " second" }
        val result = mutation.saveAndLoad(viewerContext)

        assertSame(mutation, returned)
        assertEquals(MutationResult.Success(Article(1, "first second")), result)
        assertSame(draft, repository.loadedDraft)
    }

    @Test
    fun `a failed configuration block retains its earlier assignments`() {
        val draft = Draft()
        val mutation = CreateMutation(draft, RecordingRepository())

        assertFailsWith<IllegalArgumentException> {
            mutation.configure {
                title = "retained"
                throw IllegalArgumentException("stop")
            }
        }

        assertEquals("retained", draft.title)
        mutation.configure { title = title.orEmpty() + " value" }
        assertEquals(MutationResult.Success(Unit), mutation.save(viewerContext))
    }

    @Test
    fun `configuration and terminals fail after the mutation is consumed`() {
        val mutation = CreateMutation(Draft("saved"), RecordingRepository())
        mutation.save(viewerContext)

        val configureFailure = assertFailsWith<EntMutationAlreadyConsumedException> {
            mutation.configure { title = "too late" }
        }
        assertEquals(EntOperation.CREATE, configureFailure.operation)
        assertEquals("configure", configureFailure.attemptedAction)

        val terminalFailure = assertFailsWith<EntMutationAlreadyConsumedException> {
            mutation.saveAndLoad(viewerContext)
        }
        assertEquals("saveAndLoad", terminalFailure.attemptedAction)
    }
}
