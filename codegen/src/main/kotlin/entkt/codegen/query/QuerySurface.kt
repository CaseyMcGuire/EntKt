package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName

/** Names the two query surfaces; their configuration and execution implementations are shared. */
internal enum class QuerySurface(
    private val querySuffix: String,
    private val indexesSuffix: String,
) {
    Full("Query", "Indexes"),
    ReadOnly("ReadQuery", "ReadIndexes"),
    ;

    fun queryClass(packageName: String, schemaName: String): ClassName =
        ClassName(packageName, schemaName + querySuffix)

    fun indexesClass(packageName: String, schemaName: String): ClassName =
        ClassName(packageName, schemaName + indexesSuffix)
}
