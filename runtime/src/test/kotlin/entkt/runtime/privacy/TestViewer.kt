package entkt.runtime.privacy

/**
 * The viewer type these tests run operations as. The runtime carries a viewer
 * and never inspects it, so this shape matters only to the rules the tests
 * write.
 */
internal sealed interface TestViewer {
    data object Anonymous : TestViewer

    data class User(val id: Any) : TestViewer
}
