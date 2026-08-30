package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.schema.EntktDsl

/** Mutable lifecycle-hook registrations for one entity configuration DSL. */
@EntktDsl
@OptIn(EntktInternal::class)
public class EntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> {
    public val beforeSave: HookRegistry<BeforeSave> = HookRegistry()
    public val beforeCreate: HookRegistry<BeforeCreate> = HookRegistry()
    public val afterCreate: HookRegistry<Entity> = HookRegistry()
    public val beforeUpdate: HookRegistry<BeforeUpdate> = HookRegistry()
    public val afterUpdate: HookRegistry<Entity> = HookRegistry()
    public val beforeDelete: HookRegistry<Entity> = HookRegistry()
    public val afterDelete: HookRegistry<Entity> = HookRegistry()

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
