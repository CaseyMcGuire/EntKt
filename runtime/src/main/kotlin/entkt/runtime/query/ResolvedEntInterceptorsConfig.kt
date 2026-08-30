package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy
import java.util.Collections

/** Immutable interceptor registrations used by a constructed client. */
@EntktInternal
public class ResolvedEntInterceptorsConfig internal constructor(
    perEntity: Map<String, List<RegisteredInterceptor<*>>>,
    globals: List<RegisteredGlobalInterceptor>,
) {
    private val perEntity: Map<String, List<RegisteredInterceptor<*>>> =
        immutableInterceptorMap(perEntity)
    private val globalsList: List<RegisteredGlobalInterceptor> =
        immutableListCopy(globals)

    @Suppress("UNCHECKED_CAST")
    public fun <E : Any> entityInterceptorsFor(scopeKey: String): List<RegisteredInterceptor<E>> =
        (perEntity[scopeKey] ?: emptyList()) as List<RegisteredInterceptor<E>>

    public fun globals(): List<RegisteredGlobalInterceptor> = globalsList
}

private fun immutableInterceptorMap(
    source: Map<String, List<RegisteredInterceptor<*>>>,
): Map<String, List<RegisteredInterceptor<*>>> {
    val copy = linkedMapOf<String, List<RegisteredInterceptor<*>>>()
    for ((scope, registrations) in source) {
        copy[scope] = immutableListCopy(registrations)
    }
    return Collections.unmodifiableMap(copy)
}
