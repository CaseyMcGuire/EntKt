package entkt.gradle

import org.gradle.api.provider.Property

interface EntktExtension {
    val packageName: Property<String>

    /**
     * Fully qualified name of the application's viewer type, such as
     * `"com.example.auth.AppViewer"`. Required. It must be a public,
     * top-level, non-generic class or interface that the generated sources
     * can reference: the generated API is public and exposes it. To keep an
     * implementation private, name a public interface and implement it with
     * an internal class. The type cannot be named after a Kotlin built-in
     * such as `String` or `Any`. Generated terminals take
     * `ViewerContext<ThatType>`, and rules, hooks, and interceptors read it
     * as a typed value.
     */
    val viewerType: Property<String>

    /**
     * JSON mapper id stamped into generated JSON-column metadata —
     * `"kotlinx"` (default), `"jackson"`, or a third-party codec id. A plain
     * string (not an enum) deliberately: the plugin stays off entkt's
     * classloader, and codegen is open to third-party codecs. The driver's
     * register() cross-check catches any id no configured codec advertises.
     */
    val jsonMapper: Property<String>

    /**
     * Generate the Ent Explorer adapters (one `<Name>ExplorerEntity` per
     * entity) and `GeneratedEntExplorerRegistry`. Default false: no explorer
     * files are emitted and the application needs no
     * `io.entkt:ent-explorer-core` dependency. When true, add
     * `io.entkt:ent-explorer-core` to the implementation classpath.
     */
    val generateEntExplorer: Property<Boolean>
}
