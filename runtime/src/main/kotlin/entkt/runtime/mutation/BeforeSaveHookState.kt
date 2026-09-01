package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** Marker for immutable state transformed by before-save hooks. */
interface BeforeSaveHookState<Entity : EntEntity<*>>
