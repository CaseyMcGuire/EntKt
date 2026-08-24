package entkt.runtime.mutation.execution

import entkt.query.EntktInternal

/**
 * One create draft together with the generated views exposed to its before hooks.
 *
 * Keeping these values together preserves their positional relationship when a
 * batch lifecycle processes several drafts phase by phase.
 */
@EntktInternal
class CreateMutationInput<Draft, BeforeSave, BeforeCreate>(
    val draft: Draft,
    val beforeSave: BeforeSave,
    val beforeCreate: BeforeCreate,
)
