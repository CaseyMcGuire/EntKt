package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.CreateMutationDraft

/** Scalar CREATE reuses the batch operation while retaining scalar storage and transaction policy. */
@EntktInternal
class CreateMutationOperation<
    Draft : CreateMutationDraft<Entity>,
    Candidate,
    Entity : EntEntity<*>,
    BeforeSaveState,
    BeforeCreateState,
    >(
    private val createManyOperation: CreateManyMutationOperation<Draft, Candidate, Entity, BeforeSaveState, BeforeCreateState>,
) : MutationOperation<CreateMutationInput<Draft>, Entity> {
    override fun requirements(input: CreateMutationInput<Draft>): MutationRequirements =
        MutationRequirements("${createManyOperation.entityName} create")

    override fun run(
        execution: MutationExecution,
        input: CreateMutationInput<Draft>,
    ): MutationCompletion<Entity> {
        val completion = createManyOperation.createBatch(
            execution = execution,
            viewerContext = input.viewerContext,
            drafts = listOf(input.draft),
            persistence = CreatePersistence.One,
            checkReturnedEntityPrivacy = input.checkReturnedEntityPrivacy,
        )
        return when (completion) {
            is MutationCompletion.Ready -> MutationCompletion.Ready(completion.value.single())
            is MutationCompletion.ReturnDenied -> completion
            is MutationCompletion.ReturnFailed -> completion
        }
    }
}
