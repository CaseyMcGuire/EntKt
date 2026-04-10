package entkt.schema

/**
 * Scope marker for entkt-generated DSL builders. Annotates generated
 * create/update scope classes so Kotlin's scope-control prevents
 * accidental leakage between nested DSL blocks.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class EntktDsl
