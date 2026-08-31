package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.mutation.RelationshipLockKey

/** Schema-specific relationship requirements consumed by the runtime UPDATE lifecycle. */
@EntktInternal
class UpdateRelationshipRequirements(
    val hasPendingWrites: Boolean,
    val requiresInsertIgnore: Boolean,
    canonicalLockKeys: List<RelationshipLockKey>,
) {
    val canonicalLockKeys: List<RelationshipLockKey> = canonicalLockKeys.toList()

    init {
        require(!requiresInsertIgnore || hasPendingWrites) {
            "insert-ignore support cannot be required without pending relationship writes"
        }
        require(hasPendingWrites || canonicalLockKeys.isEmpty()) {
            "canonical relationship locks cannot be requested without pending relationship writes"
        }
    }

    companion object {
        val None = UpdateRelationshipRequirements(
            hasPendingWrites = false,
            requiresInsertIgnore = false,
            canonicalLockKeys = emptyList(),
        )
    }
}
