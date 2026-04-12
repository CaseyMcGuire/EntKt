package entkt.codegen

import entkt.runtime.ColumnMetadata
import entkt.runtime.EdgeMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.ForeignKeyRef
import entkt.runtime.IdStrategy
import entkt.runtime.IndexMetadata

/**
 * Builds [EntitySchema] objects directly from [SchemaInput] definitions,
 * using the same logic as the codegen emitter but producing runtime
 * objects instead of KotlinPoet code.
 *
 * This is used by the Gradle plugin's `planMigration` task so it can
 * compute schema diffs without needing the compiled generated code.
 */
fun buildEntitySchemas(inputs: List<SchemaInput>): List<EntitySchema> {
    val schemaNames = inputs.associate { it.schema to it.name }
    return inputs.map { input ->
        buildEntitySchema(input.name, input.schema, schemaNames)
    }
}

private fun buildEntitySchema(
    name: String,
    schema: entkt.schema.EntSchema,
    schemaNames: Map<entkt.schema.EntSchema, String>,
): EntitySchema {
    val table = tableNameFor(name)
    val columns = columnMetadataFor(schema, schemaNames)
    val schemaIndexes = schema.indexes() + schema.mixins().flatMap { it.indexes() }

    return EntitySchema(
        table = table,
        idColumn = "id",
        idStrategy = IdStrategy.valueOf(idStrategyName(schema)),
        columns = columns.map { col ->
            ColumnMetadata(
                name = col.name,
                type = col.type,
                nullable = col.nullable,
                primaryKey = col.primaryKey,
                unique = col.unique,
                references = col.references?.let { (t, c) -> ForeignKeyRef(t, c) },
            )
        },
        edges = buildEdgeMap(schema, schemaNames),
        indexes = schemaIndexes.map { idx ->
            IndexMetadata(
                columns = idx.fields,
                unique = idx.unique,
                storageKey = idx.storageKey,
            )
        },
    )
}

private fun buildEdgeMap(
    schema: entkt.schema.EntSchema,
    schemaNames: Map<entkt.schema.EntSchema, String>,
): Map<String, EdgeMetadata> {
    val forwardEntries = schema.edges().mapNotNull { edge ->
        val targetName = schemaNames[edge.target] ?: return@mapNotNull null
        val join = if (edge.through != null) {
            resolveM2MEdgeJoin(edge, schema, schemaNames)
        } else {
            resolveEdgeJoin(edge, schema)
        } ?: return@mapNotNull null
        edge.name to EdgeMetadata(
            targetTable = tableNameFor(targetName),
            sourceColumn = join.sourceColumn,
            targetColumn = join.targetColumn,
            junctionTable = join.junctionTable,
            junctionSourceColumn = join.junctionSourceColumn,
            junctionTargetColumn = join.junctionTargetColumn,
        )
    }

    val reverseEntries = reverseM2MEdgeEntries(schema, schemaNames).map { (edgeName, targetTable, join) ->
        edgeName to EdgeMetadata(
            targetTable = targetTable,
            sourceColumn = join.sourceColumn,
            targetColumn = join.targetColumn,
            junctionTable = join.junctionTable,
            junctionSourceColumn = join.junctionSourceColumn,
            junctionTargetColumn = join.junctionTargetColumn,
        )
    }

    return (forwardEntries + reverseEntries).toMap()
}
