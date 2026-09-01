package entkt.runtime.hook

import entkt.query.EntktInternal
import entkt.schema.EntktDsl

/** Mutable lifecycle-hook registrations for one entity configuration DSL. */
@EntktDsl
@OptIn(EntktInternal::class)
public class EntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> {
    public val beforeSave: MutationHookRegistry<BeforeSave> = MutationHookRegistry()
    public val beforeCreate: MutationHookRegistry<BeforeCreate> = MutationHookRegistry()
    public val afterCreate: HookRegistry<Entity> = HookRegistry()
    public val beforeUpdate: MutationHookRegistry<BeforeUpdate> = MutationHookRegistry()
    public val afterUpdate: HookRegistry<Entity> = HookRegistry()
    public val beforeDelete: HookRegistry<Entity> = HookRegistry()
    public val afterDelete: HookRegistry<Entity> = HookRegistry()

    /** Resolve this mutable construction value into a detached immutable value. */
    @EntktInternal
    public fun resolveForInternalUse(
        entityName: String,
    ): ResolvedEntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> =
        ResolvedEntityHooks(
            beforeSave = beforeSave.runnerForInternalUse("$entityName.beforeSave"),
            beforeCreate = beforeCreate.runnerForInternalUse("$entityName.beforeCreate"),
            afterCreate = afterCreate.snapshotForInternalUse(),
            beforeUpdate = beforeUpdate.runnerForInternalUse("$entityName.beforeUpdate"),
            afterUpdate = afterUpdate.snapshotForInternalUse(),
            beforeDelete = beforeDelete.snapshotForInternalUse(),
            afterDelete = afterDelete.snapshotForInternalUse(),
        )
}
