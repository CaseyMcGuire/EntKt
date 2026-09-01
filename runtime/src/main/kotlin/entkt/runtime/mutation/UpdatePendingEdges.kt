package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** Marker for a schema-specific snapshot of pending edge operations. */
interface UpdatePendingEdges<Entity : EntEntity<*>>
