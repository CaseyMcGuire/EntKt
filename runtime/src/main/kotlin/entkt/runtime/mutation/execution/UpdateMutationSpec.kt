package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchHook
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.ValidationViolation

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

/** Result of generated update preparation before privacy and entity validation. */
@EntktInternal
sealed interface UpdatePreparation<out State> {
    data class Ready<State>(val value: PreparedUpdate<State>) : UpdatePreparation<State>
    data class Invalid(val violations: List<ValidationViolation>) : UpdatePreparation<Nothing> {
        init {
            require(violations.isNotEmpty()) {
                "an invalid update preparation requires at least one violation"
            }
        }
    }
}

/** Classifies only generated driver reads performed while lowering an update. */
@EntktInternal
interface UpdatePreparationScope {
    fun <Result> driverRead(block: () -> Result): Result
}

/** Receives positive acknowledgements from generated relationship writes. */
@EntktInternal
fun interface UpdateWriteTracker {
    fun markWritten()
}

/** Immutable schema-specific adapters consumed by [UpdateMutationExecutor]. */
@EntktInternal
class UpdateMutationSpec<
    State,
    Entity : EntEntity<*>,
    >(
    val entity: EntityMapping<Entity>,
    val id: Any,
    val preflight: () -> Unit,
    val loadRow: () -> Map<String, Any?>?,
    val begin: () -> Unit,
    val end: () -> Unit,
    val before: (ViewerContext, Entity) -> Unit,
    val prepare: (Entity, UpdatePreparationScope) -> UpdatePreparation<State>,
    val relationships: (State, UpdateWriteTracker) -> Unit,
    afterUpdate: List<BatchHook<Entity>>,
) {
    val afterUpdate: List<BatchHook<Entity>> = afterUpdate.toList()
}
