package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy
import java.util.Collections

/** Immutable interceptor registrations used by a constructed client. */
@EntktInternal
public class ResolvedEntInterceptorsConfig<Viewer : Any> internal constructor(
    perEntity: Map<String, List<RegisteredInterceptor<Viewer, *>>>,
    globals: List<RegisteredGlobalInterceptor<Viewer>>,
) {
    private val perEntity: Map<String, List<RegisteredInterceptor<Viewer, *>>> =
        immutableInterceptorMap(perEntity)
    private val globalsList: List<RegisteredGlobalInterceptor<Viewer>> =
        immutableListCopy(globals)

    @Suppress("UNCHECKED_CAST")
    public fun <E : Any> entityInterceptorsFor(scopeKey: String): List<RegisteredInterceptor<Viewer, E>> =
        (perEntity[scopeKey] ?: emptyList()) as List<RegisteredInterceptor<Viewer, E>>

    public fun globals(): List<RegisteredGlobalInterceptor<Viewer>> = globalsList
}

private fun <Viewer : Any> immutableInterceptorMap(
    source: Map<String, List<RegisteredInterceptor<Viewer, *>>>,
): Map<String, List<RegisteredInterceptor<Viewer, *>>> {
    val copy = linkedMapOf<String, List<RegisteredInterceptor<Viewer, *>>>()
    for ((scope, registrations) in source) {
        copy[scope] = immutableListCopy(registrations)
    }
    return Collections.unmodifiableMap(copy)
}
