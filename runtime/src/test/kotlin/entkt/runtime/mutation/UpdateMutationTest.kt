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
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpdateMutationTest {
    private val viewerContext = ViewerContext.privacyBypass_DANGEROUS("test")

    private class Draft(var title: String? = null)

    private data class Article(
        override val id: Long,
        val title: String,
    ) : EntEntity.LongId

    private class RecordingRepository : UpdateMutationRepository<Draft, Article> {
        var viewerContext: ViewerContext? = null
        var request: UpdateMutationRequest<Draft>? = null
        var appliedLoadPrivacy: Boolean? = null

        override fun executeUpdate(
            viewerContext: ViewerContext,
            request: UpdateMutationRequest<Draft>,
            applyLoadPrivacy: Boolean,
        ): MutationResult<Article> {
            this.viewerContext = viewerContext
            this.request = request
            appliedLoadPrivacy = applyLoadPrivacy
            return MutationResult.Success(Article(request.id as Long, checkNotNull(request.draft.title)))
        }
    }

    @Test
    fun `configuration and operation state reach saveAndLoad unchanged`() {
        val draft = Draft("first")
        val repository = RecordingRepository()
        val request = UpdateMutationRequest(
            id = 7L,
            draft = draft,
            consistency = UpdateConsistency.Pessimistic,
            relationshipLocking = RelationshipLocking.Canonical,
        )
        val mutation = UpdateMutation(request, repository)

        val returned = mutation.configure { title = title.orEmpty() + " second" }
        val result = mutation.saveAndLoad(viewerContext)

        assertSame(mutation, returned)
        assertEquals(MutationResult.Success(Article(7, "first second")), result)
        assertSame(viewerContext, repository.viewerContext)
        assertSame(request, repository.request)
        assertSame(draft, repository.request?.draft)
        assertEquals(UpdateConsistency.Pessimistic, repository.request?.consistency)
        assertEquals(RelationshipLocking.Canonical, repository.request?.relationshipLocking)
        assertTrue(repository.appliedLoadPrivacy == true)
    }

    @Test
    fun `save discards the internal entity and skips LOAD privacy`() {
        val repository = RecordingRepository()
        val mutation = UpdateMutation(
            request = UpdateMutationRequest(
                id = 3L,
                draft = Draft("saved"),
                consistency = UpdateConsistency.ReadCurrent,
                relationshipLocking = RelationshipLocking.OwnerOnly,
            ),
            repository = repository,
        )

        assertEquals(MutationResult.Success(Unit), mutation.save(viewerContext))
        assertFalse(checkNotNull(repository.appliedLoadPrivacy))
    }

    @Test
    fun `a failed configuration block retains its earlier assignments`() {
        val draft = Draft()
        val mutation = UpdateMutation(
            request = UpdateMutationRequest(
                id = 1L,
                draft = draft,
                consistency = UpdateConsistency.ReadCurrent,
                relationshipLocking = RelationshipLocking.OwnerOnly,
            ),
            repository = RecordingRepository(),
        )

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
        val mutation = UpdateMutation(
            request = UpdateMutationRequest(
                id = 1L,
                draft = Draft("saved"),
                consistency = UpdateConsistency.ReadCurrent,
                relationshipLocking = RelationshipLocking.OwnerOnly,
            ),
            repository = RecordingRepository(),
        )
        mutation.save(viewerContext)

        val configureFailure = assertFailsWith<EntMutationAlreadyConsumedException> {
            mutation.configure { title = "too late" }
        }
        assertEquals(EntOperation.UPDATE, configureFailure.operation)
        assertEquals("configure", configureFailure.attemptedAction)

        val terminalFailure = assertFailsWith<EntMutationAlreadyConsumedException> {
            mutation.saveAndLoad(viewerContext)
        }
        assertEquals("saveAndLoad", terminalFailure.attemptedAction)
    }
}
