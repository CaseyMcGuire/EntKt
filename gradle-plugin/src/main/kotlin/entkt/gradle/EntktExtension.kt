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
}
