package entkt.gradle

import org.gradle.api.provider.Property

interface EntktExtension {
    val packageName: Property<String>

    /**
     * JSON mapper id stamped into generated JSON-column metadata —
     * `"kotlinx"` (default), `"jackson"`, or a third-party codec id. A plain
     * string (not an enum) deliberately: the plugin stays off entkt's
     * classloader, and codegen is open to third-party codecs. The driver's
     * register() cross-check catches any id no configured codec advertises.
     */
    val jsonMapper: Property<String>

    /**
     * Generate the opt-in ent-viewer bridge (`<Name>ViewerEntity` adapters +
     * `GeneratedEntViewerRegistry`). Default false: no viewer files are
     * emitted and the application needs no `io.entkt:ent-viewer` dependency.
     * When true, add `io.entkt:ent-viewer` to the implementation classpath.
     */
    val viewer: Property<Boolean>
}
