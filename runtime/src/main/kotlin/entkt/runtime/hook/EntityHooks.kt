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

    /** Resolve this mutable construction value into a detached immutable value. */
    @EntktInternal
    public fun resolveForInternalUse(): ResolvedEntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> =
        ResolvedEntityHooks(
            beforeSave = beforeSave.snapshotForInternalUse(),
            beforeCreate = beforeCreate.snapshotForInternalUse(),
            afterCreate = afterCreate.snapshotForInternalUse(),
            beforeUpdate = beforeUpdate.snapshotForInternalUse(),
            afterUpdate = afterUpdate.snapshotForInternalUse(),
            beforeDelete = beforeDelete.snapshotForInternalUse(),
            afterDelete = afterDelete.snapshotForInternalUse(),
        )
}
