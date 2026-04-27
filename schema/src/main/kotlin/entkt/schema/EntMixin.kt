package entkt.schema

import kotlin.reflect.KClass

/**
 * Host-bound declaration bundle for reusable schema fields and indexes.
 *
 * Mixins are not schemas: they do not have a table name or id strategy.
 * Declarations created inside a mixin register directly on the including
 * schema through the provided [Scope]. Mixins intentionally support local
 * columns and indexes only; relationship edges stay on the host schema.
 */
abstract class EntMixin protected constructor(
    scope: Scope,
) {
    @PublishedApi
    internal val schema: Scope = scope

    protected fun string(name: String) = schema.string(name)
    protected fun text(name: String) = schema.text(name)
    protected fun bool(name: String) = schema.bool(name)
    protected fun int(name: String) = schema.int(name)
    protected fun long(name: String) = schema.long(name)
    protected fun float(name: String) = schema.float(name)
    protected fun double(name: String) = schema.double(name)
    protected fun time(name: String) = schema.time(name)
    protected fun uuid(name: String) = schema.uuid(name)
    protected fun bytes(name: String) = schema.bytes(name)

    protected inline fun <reified E : Enum<E>> enum(name: String) = schema.enum<E>(name)

    protected fun index(name: String, vararg fields: IndexableColumn) =
        schema.index(name, *fields)

    protected fun <M : EntMixin> include(factory: (Scope) -> M): M = schema.include(factory)

    class Scope internal constructor(
        @PublishedApi
        internal val host: EntSchema,
    ) {
        fun string(name: String) = host.stringForMixin(name)
        fun text(name: String) = host.textForMixin(name)
        fun bool(name: String) = host.boolForMixin(name)
        fun int(name: String) = host.intForMixin(name)
        fun long(name: String) = host.longForMixin(name)
        fun float(name: String) = host.floatForMixin(name)
        fun double(name: String) = host.doubleForMixin(name)
        fun time(name: String) = host.timeForMixin(name)
        fun uuid(name: String) = host.uuidForMixin(name)
        fun bytes(name: String) = host.bytesForMixin(name)

        @PublishedApi
        internal inline fun <reified E : Enum<E>> enum(name: String) = host.enumForMixin<E>(name)

        fun index(name: String, vararg fields: IndexableColumn) = host.indexForMixin(name, *fields)

        fun <M : EntMixin> include(factory: (Scope) -> M): M = host.includeForMixin(factory)
    }
}
