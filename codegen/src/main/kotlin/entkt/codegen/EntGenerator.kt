package entkt.codegen

import com.squareup.kotlinpoet.FileSpec
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import java.nio.file.Path
import kotlin.reflect.KClass

data class SchemaInput(
    val name: String,
    val schema: EntSchema,
)

/**
 * Finalize all schemas in the list if they haven't been finalized yet,
 * then run every cross-schema validation check. This is a no-op for
 * finalization when schemas were already finalized (e.g. by
 * [scanForSchemas]), but the validation checks always run.
 */
internal fun ensureFinalized(schemas: List<SchemaInput>) {
    finalizeSchemas(schemas)
    validateEdgeTargetIdentity(schemas)
    validateUniqueNamesAndTables(schemas)
    val schemaNames = schemas.associate { it.schema to it.name }
    validateMemberNames(schemas, schemaNames)
    validateRelationNames(schemas, schemaNames)
}

/**
 * Reject duplicate schema classes, build the registry, and finalize
 * any schemas that haven't been finalized yet.
 */
private fun finalizeSchemas(schemas: List<SchemaInput>) {
    val byClass = schemas.groupBy { it.schema::class }
    for ((klass, group) in byClass) {
        if (group.size > 1) {
            error(
                "Multiple SchemaInput entries use the same class '${klass.simpleName}' — " +
                    "each schema class must appear exactly once",
            )
        }
    }
    if (schemas.any { !it.schema.isFinalized }) {
        val registry: Map<KClass<out EntSchema>, EntSchema> =
            schemas.associate { it.schema::class to it.schema }
        for (input in schemas) {
            if (!input.schema.isFinalized) {
                input.schema.finalize(registry)
            }
        }
    }
}

/**
 * Verify that all edge targets and M2M junction targets are instances
 * in the current schema set. Pre-finalized schemas may have been
 * resolved against a different registry whose instances are not the
 * same objects, causing identity-based lookups to silently miss.
 */
private fun validateEdgeTargetIdentity(schemas: List<SchemaInput>) {
    val instanceSet = schemas.map { it.schema }.toSet()
    for (input in schemas) {
        for (edge in input.schema.edges()) {
            if (edge.target !in instanceSet) {
                error(
                    "Edge '${edge.name}' on schema '${input.name}' resolved to a target " +
                        "instance not in the current schema set — this typically means a " +
                        "pre-finalized schema was mixed with freshly-constructed peers. " +
                        "Pass all schemas unfinalized and let ensureFinalized() resolve them together.",
                )
            }
            val m2m = edge.kind as? EdgeKind.ManyToMany
            if (m2m != null && m2m.through.target !in instanceSet) {
                error(
                    "Edge '${edge.name}' on schema '${input.name}' has a ManyToMany junction " +
                        "schema instance (table '${m2m.through.target.tableName}') not in the " +
                        "current schema set — this typically means a pre-finalized schema was " +
                        "mixed with a freshly-constructed junction. Pass all schemas unfinalized " +
                        "and let ensureFinalized() resolve them together.",
                )
            }
        }
    }
}

/** Reject duplicate schema class names and duplicate table names. */
private fun validateUniqueNamesAndTables(schemas: List<SchemaInput>) {
    val byName = schemas.groupBy { it.name }
    for ((name, group) in byName) {
        if (group.size > 1) {
            val tables = group.joinToString(", ") { it.schema.tableName }
            error("Multiple schemas share the name '$name' (tables: $tables) — schema class names must be unique")
        }
    }
    val byTable = schemas.groupBy { it.schema.tableName }
    for ((table, group) in byTable) {
        if (group.size > 1) {
            val names = group.joinToString(", ") { it.name }
            error("Multiple schemas map to table '$table': $names")
        }
    }
}

/**
 * Reject generated member-name collisions across fields, edge
 * convenience properties, and synthesized FK properties. Raw schema
 * names may differ but still derive to the same Kotlin identifier
 * (e.g. field "author_id" -> authorId, edge "author" FK -> authorId).
 */
private fun validateMemberNames(
    schemas: List<SchemaInput>,
    schemaNames: Map<EntSchema, String>,
) {
    for (input in schemas) {
        // Seed with names that codegen emits as fixed properties on the
        // entity data class, create builder, and update builder.
        val memberSources = mutableMapOf(
            "id" to "primary key",
            "edges" to "entity edges inner class",
            "client" to "create/update builder",
            "driver" to "create/update builder",
            "entity" to "update builder",
            "dirtyFields" to "update builder",
            "beforeSaveHooks" to "create/update builder",
            "beforeCreateHooks" to "create builder",
            "afterCreateHooks" to "create builder",
            "beforeUpdateHooks" to "update builder",
            "afterUpdateHooks" to "update builder",
        )
        for (field in input.schema.fields()) {
            val prop = toCamelCase(field.name)
            val prev = memberSources.put(prop, "field '${field.name}'")
            if (prev != null) {
                error(
                    "Schema '${input.name}': $prev and field '${field.name}' both generate " +
                        "property '$prop'",
                )
            }
        }
        for (edge in input.schema.edges()) {
            val edgeProp = toCamelCase(edge.name)
            val prev = memberSources.put(edgeProp, "edge '${edge.name}'")
            if (prev != null) {
                error(
                    "Schema '${input.name}': $prev and edge '${edge.name}' both generate " +
                        "property '$edgeProp'",
                )
            }
        }
        for (fk in computeEdgeFks(input.schema, schemaNames)) {
            val prev = memberSources.put(fk.propertyName, "synthesized FK for edge '${fk.edgeName}'")
            if (prev != null) {
                error(
                    "Schema '${input.name}': $prev and synthesized FK for edge '${fk.edgeName}' " +
                        "both generate property '${fk.propertyName}'",
                )
            }
        }
    }
}

/**
 * PostgreSQL relation names (tables, indexes, sequences) share a
 * namespace. Collect every name that will become a Postgres relation
 * and reject collisions: table names, explicit index names, and
 * synthesized unique-column index names (idx_<table>_<col>_unique).
 */
private fun validateRelationNames(
    schemas: List<SchemaInput>,
    schemaNames: Map<EntSchema, String>,
) {
    val relationOwners = mutableMapOf<String, String>()
    for (input in schemas) {
        relationOwners[input.schema.tableName] = "table '${input.name}'"
    }
    for (input in schemas) {
        val table = input.schema.tableName
        // Explicit index names from index("name", ...)
        for (idx in input.schema.indexes()) {
            val prev = relationOwners.put(idx.name, "index '${idx.name}' on '${input.name}'")
            if (prev != null) {
                error(
                    "Index name '${idx.name}' on schema '${input.name}' collides with " +
                        "$prev — relation names must be globally unique",
                )
            }
        }
        // Synthesized unique-column index names. The Postgres driver
        // emits CREATE UNIQUE INDEX idx_<table>_<col>_unique for every
        // non-PK unique column — including fields that gain uniqueness
        // from a .unique() edge via .field(handle).
        val columns = columnMetadataFor(input.schema, schemaNames)
        for (col in columns) {
            if (!col.unique || col.primaryKey) continue
            val synth = "idx_${table}_${col.name}_unique"
            val prev = relationOwners.put(synth, "synthesized unique index '$synth' on '${input.name}'")
            if (prev != null) {
                error(
                    "Synthesized unique index '$synth' for column '${col.name}' on " +
                        "schema '${input.name}' collides with $prev — " +
                        "relation names must be globally unique",
                )
            }
        }
    }
}

class EntGenerator(
    private val packageName: String,
) {
    private val entityGenerator = EntityGenerator(packageName)
    private val mutationGenerator = MutationGenerator(packageName)
    private val createGenerator = CreateGenerator(packageName)
    private val updateGenerator = UpdateGenerator(packageName)
    private val queryGenerator = QueryGenerator(packageName)
    private val repoGenerator = RepoGenerator(packageName)
    private val privacyGenerator = PrivacyGenerator(packageName)
    private val validationGenerator = ValidationGenerator(packageName)
    private val clientGenerator = ClientGenerator(packageName)

    fun generate(schemas: List<SchemaInput>): List<FileSpec> {
        ensureFinalized(schemas)
        val schemaNames: Map<EntSchema, String> = schemas.associate { it.schema to it.name }
        val perSchema = schemas.flatMap { (name, schema) ->
            listOf(
                entityGenerator.generate(name, schema, schemaNames),
                mutationGenerator.generate(name, schema, schemaNames),
                createGenerator.generate(name, schema, schemaNames),
                updateGenerator.generate(name, schema, schemaNames),
                queryGenerator.generate(name, schema, schemaNames),
                repoGenerator.generate(name, schema, schemaNames),
                privacyGenerator.generate(name, schema, schemaNames),
                validationGenerator.generate(name, schema, schemaNames),
            )
        }
        return perSchema + clientGenerator.generate(schemas)
    }

    fun writeTo(outputDir: Path, schemas: List<SchemaInput>) {
        generate(schemas).forEach { fileSpec ->
            fileSpec.writeTo(outputDir)
        }
    }
}
