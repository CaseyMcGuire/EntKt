package entkt.runtime.hook

import entkt.query.EntktInternal

/** Immutable lifecycle-hook registrations used by a constructed client. */
@EntktInternal
public class ResolvedEntityHooks<BeforeSave, BeforeCreate, BeforeUpdate, Entity> public constructor(
    public val beforeSave: MutationHookRunner<BeforeSave>,
    public val beforeCreate: MutationHookRunner<BeforeCreate>,
    afterCreate: List<BatchActionHook<Entity>>,
    public val beforeUpdate: MutationHookRunner<BeforeUpdate>,
    afterUpdate: List<BatchActionHook<Entity>>,
    beforeDelete: List<BatchActionHook<Entity>>,
    afterDelete: List<BatchActionHook<Entity>>,
) {
    public val afterCreate: HookRunner<Entity> = HookRunner(afterCreate)
    public val afterUpdate: HookRunner<Entity> = HookRunner(afterUpdate)
    public val beforeDelete: HookRunner<Entity> = HookRunner(beforeDelete)
    public val afterDelete: HookRunner<Entity> = HookRunner(afterDelete)
}
