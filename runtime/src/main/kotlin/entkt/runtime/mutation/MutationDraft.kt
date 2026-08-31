package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** Common entity association implemented by every generated mutation draft. */
interface MutationDraft<Entity : EntEntity<*>>
