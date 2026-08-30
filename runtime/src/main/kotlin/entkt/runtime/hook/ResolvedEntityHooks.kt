package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

/** Immutable lifecycle-hook registrations used by a constructed client. */
@EntktInternal
public class ResolvedEntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> public constructor(
    beforeSave: List<BatchHook<BeforeSave>>,
    beforeCreate: List<BatchHook<BeforeCreate>>,
    afterCreate: List<BatchHook<Entity>>,
    beforeUpdate: List<BatchHook<BeforeUpdate>>,
    afterUpdate: List<BatchHook<Entity>>,
    beforeDelete: List<BatchHook<Entity>>,
    afterDelete: List<BatchHook<Entity>>,
) {
    public val beforeSave: List<BatchHook<BeforeSave>> = immutableListCopy(beforeSave)
    public val beforeCreate: List<BatchHook<BeforeCreate>> = immutableListCopy(beforeCreate)
    public val afterCreate: List<BatchHook<Entity>> = immutableListCopy(afterCreate)
    public val beforeUpdate: List<BatchHook<BeforeUpdate>> = immutableListCopy(beforeUpdate)
    public val afterUpdate: List<BatchHook<Entity>> = immutableListCopy(afterUpdate)
    public val beforeDelete: List<BatchHook<Entity>> = immutableListCopy(beforeDelete)
    public val afterDelete: List<BatchHook<Entity>> = immutableListCopy(afterDelete)
}
