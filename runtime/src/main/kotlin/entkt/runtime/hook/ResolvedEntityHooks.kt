package entkt.runtime.hook

import entkt.query.EntktInternal

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
    public val beforeSave: HookRunner<BeforeSave> = HookRunner(beforeSave)
    public val beforeCreate: HookRunner<BeforeCreate> = HookRunner(beforeCreate)
    public val afterCreate: HookRunner<Entity> = HookRunner(afterCreate)
    public val beforeUpdate: HookRunner<BeforeUpdate> = HookRunner(beforeUpdate)
    public val afterUpdate: HookRunner<Entity> = HookRunner(afterUpdate)
    public val beforeDelete: HookRunner<Entity> = HookRunner(beforeDelete)
    public val afterDelete: HookRunner<Entity> = HookRunner(afterDelete)
}
