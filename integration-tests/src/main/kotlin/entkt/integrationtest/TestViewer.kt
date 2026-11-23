package entkt.integrationtest

/**
 * The viewer type both integration-test clients are generated for. It is the
 * `viewerType` argument of the code generation tasks in `build.gradle.kts`,
 * and it lives in the main source set because the generated sources compile
 * against it.
 *
 * The runtime carries a viewer and never inspects it, so this shape matters
 * only to the rules the tests write. `id` is untyped because tests identify a
 * user by an entity id in some places and by a label in others.
 */
sealed interface TestViewer {
    data object Anonymous : TestViewer

    data class User(val id: Any) : TestViewer
}
