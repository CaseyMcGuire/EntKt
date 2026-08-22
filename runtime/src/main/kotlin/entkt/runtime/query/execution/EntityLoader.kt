package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.query.FrozenQuerySpec

/** Executes one prepared storage query and decodes rows of a single entity type. */
@EntktInternal
class EntityLoader(
    private val driver: DatabaseDriver,
) {
    /** Load [entity] rows using the exact storage bounds supplied by the caller. */
    fun <Entity : EntEntity<*>> load(
        entity: EntityMapping<Entity>,
        query: FrozenQuerySpec<Entity>,
        limit: Int?,
        offset: Int?,
        maximumEntities: Int? = null,
    ): List<Entity> {
        val rows = driver.query(
            query.table,
            query.predicates,
            query.orderBy,
            limit,
            offset,
        )
        val boundedRows = maximumEntities?.let(rows::take) ?: rows
        return boundedRows.map { decode(entity, it) }
    }

    /** Decode one driver row using the generated mapping for [entity]. */
    fun <Entity : EntEntity<*>> decode(
        entity: EntityMapping<Entity>,
        row: Map<String, Any?>,
    ): Entity = entity.decode(row)
}
