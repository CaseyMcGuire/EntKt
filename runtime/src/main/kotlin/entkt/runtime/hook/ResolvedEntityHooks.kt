package entkt.runtime.hook

import entkt.query.EntktInternal

/** Immutable registry snapshots produced by [EntityHooks.resolveForInternalUse]. */
@EntktInternal
public class ResolvedEntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> internal constructor(
    public val beforeSave: List<BatchTransformingHook<BeforeSave>>,
    public val beforeCreate: List<BatchTransformingHook<BeforeCreate>>,
    public val afterCreate: List<BatchActionHook<Entity>>,
    public val beforeUpdate: List<BatchTransformingHook<BeforeUpdate>>,
    public val afterUpdate: List<BatchActionHook<Entity>>,
    public val beforeDelete: List<BatchActionHook<Entity>>,
    public val afterDelete: List<BatchActionHook<Entity>>,
)
