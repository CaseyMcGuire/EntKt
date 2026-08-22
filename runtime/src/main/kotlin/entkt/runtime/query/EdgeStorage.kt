package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping

/** Storage alternatives that require different relationship-loading strategies. */
@EntktInternal
sealed interface EdgeStorage<
    Source : EntEntity<*>,
    Target : EntEntity<*>,
> {
    /** The foreign key is read from each decoded source entity. */
    data class ForeignKeyOnSource<
        Source : EntEntity<*>,
        Target : EntEntity<*>,
        Key : Any,
    >(
        val sourceColumn: String,
        val targetColumn: String,
        val sourceForeignKey: (Source) -> Key?,
        val targetKey: (Target) -> Key,
    ) : EdgeStorage<Source, Target> {
        init {
            require(sourceColumn.isNotBlank()) { "Source foreign-key column must not be blank" }
            require(targetColumn.isNotBlank()) { "Target key column must not be blank" }
        }
    }

    /** The foreign key is returned with or extracted from each target row. */
    data class ForeignKeyOnTarget<
        Source : EntEntity<*>,
        Target : EntEntity<*>,
        Key : Any,
    >(
        val sourceColumn: String,
        val targetColumn: String,
        val sourceKey: (Source) -> Key,
        val targetForeignKey: (Target) -> Key?,
    ) : EdgeStorage<Source, Target> {
        init {
            require(sourceColumn.isNotBlank()) { "Source key column must not be blank" }
            require(targetColumn.isNotBlank()) { "Target foreign-key column must not be blank" }
        }
    }

    /** Source and target keys are correlated through an intermediate table. */
    data class Junction<
        Source : EntEntity<*>,
        Target : EntEntity<*>,
        JunctionEntity : EntEntity<*>,
        SourceKey : Any,
        TargetKey : Any,
    >(
        val table: String,
        val sourceColumn: String,
        val targetColumn: String,
        val junctionEntity: EntityMapping<JunctionEntity>,
        val sourceKey: (Source) -> SourceKey,
        val targetKey: (Target) -> TargetKey,
    ) : EdgeStorage<Source, Target> {
        init {
            require(table.isNotBlank()) { "Junction table must not be blank" }
            require(sourceColumn.isNotBlank()) { "Junction source column must not be blank" }
            require(targetColumn.isNotBlank()) { "Junction target column must not be blank" }
        }
    }
}
