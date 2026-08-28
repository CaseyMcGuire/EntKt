package entkt.runtime.mutation

import entkt.query.EntktInternal

/**
 * The normalized handoff produced from one generated create draft.
 *
 * Generated scalar and batch create pipelines share this carrier so defaults,
 * row encoding, and write-candidate construction run exactly once for each
 * draft. Phase adapters validate [candidate] and create detached privacy and
 * validation views for every reached rule. Lifecycle hooks, rule evaluation,
 * and driver I/O remain outside this type.
 *
 * This is generated-code infrastructure rather than application API. It is
 * public only because generated sources compile in the consuming application
 * module, outside the runtime module's Kotlin `internal` boundary.
 */
@EntktInternal
class PreparedCreate<out Candidate>(
    val values: Map<String, Any?>,
    val candidate: Candidate,
)
