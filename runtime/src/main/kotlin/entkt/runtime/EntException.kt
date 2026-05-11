package entkt.runtime

/**
 * Base class for exceptions thrown by generated save/throwing code
 * paths. Each subclass wraps a specific [EntError] variant. Callers
 * that want structured failure handling can pattern-match on
 * `exception.error`; callers that just want to bubble up can catch
 * [EntException].
 *
 * Throwable subclasses can't be generic on the JVM, so each subclass
 * exposes a narrowed accessor for its variant (e.g.
 * [EntNotFoundException.notFound]) instead of overriding [error].
 */
abstract class EntException(val error: EntError) : RuntimeException(error.message)

class EntNotFoundException(val notFound: EntError.NotFound) : EntException(notFound)

class EntNoChangesException(val noChanges: EntError.NoChanges) : EntException(noChanges)
