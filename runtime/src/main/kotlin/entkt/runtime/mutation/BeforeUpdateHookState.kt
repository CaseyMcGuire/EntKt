package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** Marker for immutable state transformed by before-update hooks. */
interface BeforeUpdateHookState<Entity : EntEntity<*>>
