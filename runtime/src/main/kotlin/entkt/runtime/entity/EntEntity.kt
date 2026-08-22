package entkt.runtime.entity

import java.util.UUID

/**
 * Common identity contract implemented by every generated EntKt entity.
 *
 * The nested interfaces mirror the four ID types accepted by `EntSchema`. Keeping the base
 * sealed prevents other ID specializations while allowing generated entities to implement the
 * appropriate nested interface from an application module.
 */
sealed interface EntEntity<out Id : Any> {
    /** Stable primary-key value for this entity. */
    val id: Id

    /** Entity whose primary key is an [Int]. */
    interface IntId : EntEntity<Int>

    /** Entity whose primary key is a [Long]. */
    interface LongId : EntEntity<Long>

    /** Entity whose primary key is a [UUID]. */
    interface UuidId : EntEntity<UUID>

    /** Entity whose primary key is a [String]. */
    interface StringId : EntEntity<String>
}
