package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** Marker for immutable state transformed by before-create hooks. */
interface BeforeCreateHookState<Entity : EntEntity<*>>
