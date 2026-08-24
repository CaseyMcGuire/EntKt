package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.PrivacyContext

/** Created entities and operation state retained until returned-entity disclosure completes. */
@EntktInternal
class CreateMutationOutput<Entity : EntEntity<*>>(
    /** Materialized entities in input order. */
    val entities: List<Entity>,
    /** Privacy context captured once before CREATE authorization. */
    val privacyContext: PrivacyContext,
)
