package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** A generated draft containing changes for creating one [Entity]. */
interface CreateMutationDraft<Entity : EntEntity<*>> : MutationDraft<Entity>
