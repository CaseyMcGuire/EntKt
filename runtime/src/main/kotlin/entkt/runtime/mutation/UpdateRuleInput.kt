package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/**
 * Marker for schema-specific values evaluated by UPDATE rules for [Entity].
 *
 * UPDATE inputs carry per-entity patch and edge-change types, so each entity has its own generated class.
 * The runtime only passes that class between generated code and rules without reading it, so an empty
 * marker is enough to type it. Compare [DeleteRuleInput], which the runtime reads and which needs no
 * per-entity types.
 */
interface UpdateRuleInput<Entity : EntEntity<*>>
