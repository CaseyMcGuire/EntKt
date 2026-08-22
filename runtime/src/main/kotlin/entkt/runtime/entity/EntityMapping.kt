package entkt.runtime.entity

import entkt.query.EntktInternal
import entkt.runtime.query.EdgeMapping
import kotlin.reflect.KClass

/** Generated entity identity, storage metadata, and row decoding. */
@EntktInternal
interface EntityMapping<Entity : EntEntity<*>> {
    /** Declaration-derived entity name used in lifecycle diagnostics. */
    val entityName: String

    /** Declared client property name used to find this entity's registered lifecycle rules. */
    val clientName: String

    /** Runtime entity type supplied to interceptor contexts and graph paths. */
    val entityClass: KClass<Entity>

    /** Database table queried for this entity. */
    val table: String

    /** Decode one driver row whose keys are raw database column names. */
    fun decode(row: Map<String, Any?>): Entity

    /** Resolve an outgoing edge from the storage identifier carried by predicates. */
    fun edgeByStorageName(storageName: String): EdgeMapping<Entity, *>?
}
