package entkt.runtime

/**
 * The runtime entry point a generated [entkt.runtime] repo uses to talk
 * to a database. Currently a marker — the actual database operations
 * (execute, transact, etc.) will land when the runtime layer is built.
 *
 * Repos take a [Driver] in their constructor so the dependency
 * injection story is established up front: tests and production code
 * both inject a Driver into the generated `EntClient`, and there are no
 * static entry points to intercept.
 */
interface Driver

/**
 * A no-op [Driver] suitable for tests and demos that only construct
 * builders without executing them. Calling any operation that needs the
 * database will fail once the runtime exists; for now this just
 * satisfies the constructor.
 */
object StubDriver : Driver