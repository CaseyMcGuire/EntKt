package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/** Marker for schema-specific writable values evaluated by mutation rules for [Entity]. */
interface WriteCandidate<Entity : EntEntity<*>>
