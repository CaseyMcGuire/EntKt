package entkt.runtime.mutation.execution

import entkt.query.EntktInternal

/** Stable output of generated patch lowering, consumed by the runtime update lifecycle. */
@EntktInternal
data class PreparedUpdate<State>(
    val state: State,
    val values: Map<String, Any?>,
    val isNoOp: Boolean,
) {
    init {
        require(!isNoOp || values.isEmpty()) {
            "a no-op update cannot carry owner-row values"
        }
    }
}
