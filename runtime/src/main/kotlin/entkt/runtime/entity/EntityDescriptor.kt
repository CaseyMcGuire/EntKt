package entkt.runtime.entity

import entkt.query.EntktInternal
import entkt.runtime.driver.EntitySchema
import entkt.runtime.query.EdgeMapping

/**
 * Canonical generated identity and runtime metadata for one entity type.
 *
 * Unlike query builders, a descriptor is immutable and shared by reads,
 * mutations, privacy dispatch, and relationship mappings.
 */
@EntktInternal
interface EntityDescriptor<
    Entity : EntEntity<ID>,
    ID : Any,
> : EntityMapping<Entity> {
    /** Driver-facing schema registered for this entity. */
    val schema: EntitySchema

    /** Primary-key storage column declared by [schema]. */
    val idColumn: String
        get() = schema.idColumn

    /** Outgoing typed relationship mappings keyed by their storage names. */
    val edgesByStorageName: Map<String, EdgeMapping<Entity, *>>

    override val table: String
        get() = schema.table

    override fun edgeByStorageName(
        storageName: String,
    ): EdgeMapping<Entity, *>? = edgesByStorageName[storageName]
}
