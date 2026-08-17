package entkt.runtime.mutation

import entkt.query.EntktInternal

/**
 * The single normalized handoff produced by a generated create builder.
 *
 * Generated scalar and batch create pipelines share this carrier so defaults,
 * inline field validation, row encoding, and write-candidate construction run
 * exactly once for each builder. Lifecycle hooks, privacy, entity-level
 * validation, and driver I/O are deliberately outside this type and the
 * preparation step that creates it.
 *
 * This is generated-code infrastructure rather than application API. It is
 * public only because generated sources compile in the consuming application
 * module, outside the runtime module's Kotlin `internal` boundary.
 */
@EntktInternal
class PreparedCreate<out C>(
    val values: Map<String, Any?>,
    val candidate: C,
)
