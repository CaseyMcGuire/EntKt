package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** Marker for schema-specific state prepared for update evaluation and persistence. */
interface PreparedUpdateState<Entity : EntEntity<*>>
