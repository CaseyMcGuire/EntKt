package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.schema.EntktDsl

/** Mutable lifecycle-hook registrations for one entity configuration DSL. */
@EntktDsl
@OptIn(EntktInternal::class)
public class EntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> {
    public val beforeSave: TransformingHookRegistry<BeforeSave> = TransformingHookRegistry()
    public val beforeCreate: TransformingHookRegistry<BeforeCreate> = TransformingHookRegistry()
    public val afterCreate: ActionHookRegistry<Entity> = ActionHookRegistry()
    public val beforeUpdate: TransformingHookRegistry<BeforeUpdate> = TransformingHookRegistry()
    public val afterUpdate: ActionHookRegistry<Entity> = ActionHookRegistry()
    public val beforeDelete: ActionHookRegistry<Entity> = ActionHookRegistry()
    public val afterDelete: ActionHookRegistry<Entity> = ActionHookRegistry()

    /**
     * Copy every registry into a new set of hooks.
     * Later registrations on this one do not reach the copy. The hooks themselves are shared.
     */
    @EntktInternal
    public fun deepCopy(): EntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> {
        val copy = EntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity>()
        copy.beforeSave.addAll(beforeSave)
        copy.beforeCreate.addAll(beforeCreate)
        copy.afterCreate.addAll(afterCreate)
        copy.beforeUpdate.addAll(beforeUpdate)
        copy.afterUpdate.addAll(afterUpdate)
        copy.beforeDelete.addAll(beforeDelete)
        copy.afterDelete.addAll(afterDelete)
        return copy
    }
}
