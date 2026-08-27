package entkt.codegen.entity

import entkt.codegen.apiName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import entkt.codegen.SchemaInput
import entkt.codegen.columnName
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.objectType
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.ColumnDescriptor
import entkt.codegen.metadata.FIELD_TYPE
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.metadata.columnMetadataFor
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.resolveEdgeJoin
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.FieldType

private val VIEWER_ENTITY = ClassName("entkt.viewer", "EntViewerEntity")
private val VIEWER_COLUMN = ClassName("entkt.viewer", "EntViewerColumn")
private val VIEWER_EDGE = ClassName("entkt.viewer", "EntViewerEdge")
private val VIEWER_ROW = ClassName("entkt.viewer", "EntViewerRow")
private val VIEWER_VALUE = ClassName("entkt.viewer", "EntViewerValue")
private val VIEWER_LIST_REQUEST = ClassName("entkt.viewer", "EntViewerListRequest")
private val VIEWER_LIST_RESULT = ClassName("entkt.viewer", "EntViewerListResult")
private val VIEWER_FILTER = ClassName("entkt.viewer", "EntViewerFilter")
private val VIEWER_FILTERS = ClassName("entkt.viewer", "EntViewerFilters")
private val VIEWER_BAD_REQUEST = ClassName("entkt.viewer", "EntViewerBadRequestException")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val ORDER_FIELD = ClassName("entkt.query", "OrderField")
private val ORDER_DIRECTION = ClassName("entkt.query", "OrderDirection")
private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val ENT_PRIVACY_DENIED = ClassName("entkt.runtime.result", "EntPrivacyDeniedException")
private val LOAD_DENIAL_ORIGIN = ClassName("entkt.runtime.result", "LoadDenialOrigin")
private val VISIBLE_OR_NULL = com.squareup.kotlinpoet.MemberName("entkt.runtime.result", "visibleOrNull")

/**
 * Emits the opt-in viewer bridge (`entkt { viewer.set(true) }`): one
 * `<Name>ViewerEntity` object per entity plus `GeneratedEntViewerRegistry`.
 * Adapters go through the generated typed repos
 * (`client.<repo>.query { ... }.all()`, `findById(...).visibleOrNull()`) so the
 * viewer inherits read privacy, read interceptors, soft-delete filters, and
 * result decoding — never `DatabaseDriver.query(...)`.
 *
 * When the flag is off (the default) no viewer files are emitted and the
 * application needs no `io.entkt:ent-viewer` dependency.
 */
internal class ViewerGenerator(private val packageName: String) {

    fun generate(schemas: List<SchemaInput>, schemaNames: Map<EntSchema, String>): List<FileSpec> {
        val files = schemas.map { generateAdapter(it, schemaNames) }
        return files + generateRegistry(schemas)
    }


    private fun generateAdapter(input: SchemaInput, schemaNames: Map<EntSchema, String>): FileSpec {
        val name = input.name
        val schema = input.schema
        val entityClass = ClassName(packageName, name)
        val clientClass = ClassName(packageName, "EntClient")
        val objectName = "${name}ViewerEntity"
        val repoProp = input.clientName

        val columns = columnMetadataFor(schema, schemaNames)

        val type = objectType(objectName) {
            addSuperinterface(VIEWER_ENTITY.parameterizedBy(clientClass))
            addKdoc(
                "Generated viewer adapter for [%T]. Reads go through the generated\n" +
                    "typed repo, so privacy, interceptors, and soft-delete filters apply.\n",
                entityClass,
            )
            property("schema", ClassName("entkt.runtime.driver", "EntitySchema")) {
                addModifiers(KModifier.OVERRIDE)
                initializer("%T.SCHEMA", entityClass)
            }
            property("displayName", String::class.asTypeName()) {
                addModifiers(KModifier.OVERRIDE)
                initializer("%S", name)
            }
            // The viewer route is the schema's declared client name.
            property("routeName", String::class.asTypeName()) {
                addModifiers(KModifier.OVERRIDE)
                initializer("%S", schema.clientName)
            }
            addProperty(buildColumnsProperty(columns, schema))
            addProperty(buildEdgesProperty(schema, schemaNames))
            property(
                "columnsByName",
                ClassName("kotlin.collections", "Map").parameterizedBy(
                    ClassName("kotlin", "String"), VIEWER_COLUMN,
                ),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer("columns.associateBy { it.name }")
            }
            addProperty(buildEnumNamesProperty(schema))
            addFunction(buildListFunction(input, clientClass, entityClass, repoProp))
            addFunction(buildGetFunction(input, clientClass, entityClass, repoProp))
            addFunction(buildToRowFunction(input, entityClass, schemaNames))
            addFunction(buildPredicateFunction(entityClass))
        }

        return kotlinFile(packageName, objectName) { addType(type) }
    }

    private fun buildColumnsProperty(columns: List<ColumnDescriptor>, schema: EntSchema): PropertySpec {
        val fieldsByColumn = schema.fields().associateBy { it.columnName }
        val entries = columns.map { col ->
            val filterable = !col.sensitive && supportsFilters(col.type)
            CodeBlock.of(
                "%T(name = %S, type = %T.%L, nullable = %L, unique = %L, sensitive = %L, filterable = %L, orderable = %L, entType = %S)",
                VIEWER_COLUMN, col.name, FIELD_TYPE, col.type.name,
                col.nullable, col.unique, col.sensitive, filterable, filterable,
                entTypeDisplay(col, fieldsByColumn[col.name]),
            )
        }
        return property(
            "columns",
            ClassName("kotlin.collections", "List").parameterizedBy(VIEWER_COLUMN),
        ) {
            addModifiers(KModifier.OVERRIDE)
            initializer(entries.joinToCodeList())
        }
    }

    /** Kotlin-facing display type: scalars fixed, enums/JSON from the schema field. */
    private fun entTypeDisplay(col: ColumnDescriptor, field: entkt.schema.Field?): String = when (col.type) {
        FieldType.STRING, FieldType.TEXT -> "String"
        FieldType.BOOL -> "Boolean"
        FieldType.INT -> "Int"
        FieldType.LONG -> "Long"
        FieldType.FLOAT -> "Float"
        FieldType.DOUBLE -> "Double"
        FieldType.TIME -> "Instant"
        FieldType.UUID -> "UUID"
        FieldType.BYTES -> "ByteArray"
        FieldType.ENUM -> field?.enumClass?.simpleName ?: "enum"
        FieldType.JSON -> field?.jsonType?.let { simpleTypeName(it) } ?: "json"
        FieldType.PGVECTOR -> "PgVector"
    }

    /** `kotlin.collections.List<com.x.Rect>` -> `List<Rect>` for display. */
    private fun simpleTypeName(type: kotlin.reflect.KType): String = buildString {
        append((type.classifier as? kotlin.reflect.KClass<*>)?.simpleName ?: type.toString())
        if (type.arguments.isNotEmpty()) {
            append('<')
            append(type.arguments.joinToString(", ") { it.type?.let(::simpleTypeName) ?: "*" })
            append('>')
        }
        if (type.isMarkedNullable) append('?')
    }

    private fun supportsFilters(type: FieldType): Boolean = when (type) {
        FieldType.BYTES, FieldType.JSON, FieldType.PGVECTOR -> false
        else -> true
    }

    private fun buildEdgesProperty(schema: EntSchema, schemaNames: Map<EntSchema, String>): PropertySpec {
        val entries = schema.edges().map { edge ->
            val targetName = schemaNames[edge.target]
            // Route to the target's declared client name, so viewer links
            // agree with the routes those entities register under.
            val targetRoute = targetName?.let { edge.target.clientName }
            val join = if (edge.kind is EdgeKind.ManyToMany) null else resolveEdgeJoin(edge, schema)
            val (cardinality, localFk, targetFilter) = when (edge.kind) {
                is EdgeKind.BelongsTo -> Triple("to-one", join?.sourceColumn, null)
                is EdgeKind.HasOne -> Triple("to-one", null, join?.targetColumn)
                is EdgeKind.HasMany -> Triple("to-many", null, join?.targetColumn)
                is EdgeKind.ManyToMany -> Triple("many-to-many", null, null)
            }
            CodeBlock.of(
                "%T(name = %S, targetRouteName = %L, cardinality = %S, localFkColumn = %L, targetFilterColumn = %L)",
                VIEWER_EDGE, edge.apiName,
                targetRoute?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
                cardinality,
                localFk?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
                targetFilter?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
            )
        }
        return property(
            "edges",
            ClassName("kotlin.collections", "List").parameterizedBy(VIEWER_EDGE),
        ) {
            addModifiers(KModifier.OVERRIDE)
            initializer(entries.joinToCodeList())
        }
    }

    private fun buildEnumNamesProperty(schema: EntSchema): PropertySpec {
        val enumFields = schema.fields().filter { it.type == FieldType.ENUM }
        val initializer = if (enumFields.isEmpty()) {
            CodeBlock.of("emptyMap()")
        } else {
            val entries = enumFields.map { field ->
                val constants = field.enumClass!!.java.enumConstants.joinToString(", ") { "\"${(it as Enum<*>).name}\"" }
                CodeBlock.of("%S to setOf(%L)", field.columnName, constants)
            }
            codeBlock {
                add("mapOf(\n")
                entries.forEach { add("  %L,\n", it) }
                add(")")
            }
        }
        return property(
            "enumNames",
            ClassName("kotlin.collections", "Map").parameterizedBy(
                ClassName("kotlin", "String"),
                ClassName("kotlin.collections", "Set").parameterizedBy(ClassName("kotlin", "String")),
            ),
        ) {
            addModifiers(KModifier.PRIVATE)
            initializer(initializer)
        }
    }

    private fun buildListFunction(
        input: SchemaInput,
        clientClass: ClassName,
        entityClass: ClassName,
        repoProp: String,
    ): FunSpec {
        // Canonical strict pagination: the window is fetched with a
        // pageSize+1 probe for an exact hasNext — sound because the
        // canonical all() is all-or-nothing, so a successful page was
        // never filtered. A LOAD-denied row anywhere in the probed
        // window fails the whole read; the viewer reports that as an
        // explicitly privacy-filtered empty page rather than showing a
        // partial window. Debug listings that must see every row run
        // the viewer with a privacy-bypass-scoped client, where every
        // page succeeds and pagination is exact.
        val body = codeBlock {
            statement("val fetchLimit = request.pageSize + 1")
            beginControlFlow("val result = client.%L.query", repoProp)
            statement("for (filter in request.filters) `where`(predicateFor(filter))")
            statement("val order = request.order")
            beginControlFlow("if (order != null)")
            statement(
                "val column = columnsByName[order.column] ?: throw %T(%P)",
                VIEWER_BAD_REQUEST, "Unknown order column '\${order.column}'.",
            )
            statement(
                "if (!column.orderable) throw %T(%P)",
                VIEWER_BAD_REQUEST, "Column '\${column.name}' is not orderable.",
            )
            statement(
                "orderBy(%T(order.column, if (order.descending) %T.DESC else %T.ASC))",
                ORDER_FIELD.parameterizedBy(entityClass), ORDER_DIRECTION, ORDER_DIRECTION,
            )
            endControlFlow()
            statement("limit(fetchLimit)")
            statement("offset(request.offset)")
            endControlFlow()
            statement(".all(viewerContext)")
            beginControlFlow("val rows = when (result)")
            statement("is %T.Success -> result.value", READ_RESULT)
            beginControlFlow("is %T.Failed ->", READ_RESULT)
            statement("val e = result.exception")
            beginControlFlow("if (e is %T && e.origin is %T.Root)", ENT_PRIVACY_DENIED, LOAD_DENIAL_ORIGIN)
            statement(
                "return %T(emptyList(), hasNext = null, privacyFiltered = true)",
                VIEWER_LIST_RESULT,
            )
            endControlFlow()
            statement("throw e")
            endControlFlow()
            endControlFlow()
            statement(
                "return %T(rows.take(request.pageSize).map { toRow(it) }, hasNext = rows.size > request.pageSize)",
                VIEWER_LIST_RESULT,
            )
        }
        return function("list", VIEWER_LIST_RESULT) {
            addModifiers(KModifier.OVERRIDE)
            parameter("client", clientClass)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("request", VIEWER_LIST_REQUEST)
            addCode(body)
        }
    }

    private fun buildGetFunction(
        input: SchemaInput,
        clientClass: ClassName,
        entityClass: ClassName,
        repoProp: String,
    ): FunSpec {
        val idParse = when (input.schema.id().type) {
            FieldType.INT -> CodeBlock.of("id.toIntOrNull() ?: return null")
            FieldType.LONG -> CodeBlock.of("id.toLongOrNull() ?: return null")
            FieldType.UUID -> CodeBlock.of(
                "try { %T.fromString(id) } catch (_: %T) { return null }",
                ClassName("java.util", "UUID"), ClassName("kotlin", "IllegalArgumentException"),
            )
            else -> CodeBlock.of("id")
        }
        return function("get", VIEWER_ROW.copy(nullable = true)) {
            addModifiers(KModifier.OVERRIDE)
            parameter("client", clientClass)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("id", String::class.asTypeName())
            addCode(codeBlock {
                // Unparseable id, missing row, and privacy-denied row all return null.
                statement("val parsed = %L", idParse)
                statement(
                        "val entity = client.%L.findById(viewerContext, parsed).%M().getOrThrow() ?: return·null",
                        repoProp, VISIBLE_OR_NULL,
                    )
                statement("return toRow(entity)")
            })
        }
    }

    private fun buildToRowFunction(
        input: SchemaInput,
        entityClass: ClassName,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val schema = input.schema
        val columns = columnMetadataFor(schema, schemaNames)
        val fieldsByColumn = schema.fields().associateBy { it.columnName }
        val fkByColumn = computeEdgeFks(schema, schemaNames).associateBy { it.columnName }

        val values = columns.map { col ->
            if (col.sensitive) {
                // Never materialize the value — and don't disclose null-ness.
                return@map CodeBlock.of("%T.redacted(%S)", VIEWER_VALUE, col.name)
            }
            val expr: CodeBlock = if (col.name == "id") {
                CodeBlock.of("entity.id.toString()")
            } else {
                val field = fieldsByColumn[col.name]
                // FK columns first: field-backed FKs surface as an entity
                // property named after the captured Kotlin val (EdgeFk
                // .propertyName), which is declaration-derived.
                val prop = fkByColumn[col.name]?.propertyName
                    ?: field?.let { it.apiName }
                    ?: col.name
                val access = if (col.nullable) "entity.$prop?" else "entity.$prop"
                when (col.type) {
                    FieldType.STRING, FieldType.TEXT ->
                        CodeBlock.of("%L", "entity.$prop")
                    FieldType.ENUM ->
                        CodeBlock.of("%L", "$access.name")
                    FieldType.BYTES ->
                        if (col.nullable) CodeBlock.of("%L", "entity.$prop?.let { \"bytes[\" + it.size + \"]\" }")
                        else CodeBlock.of("%L", "\"bytes[\" + entity.$prop.size + \"]\"")
                    else ->
                        CodeBlock.of("%L", "$access.toString()")
                }
            }
            CodeBlock.of("%T.of(%S, %L)", VIEWER_VALUE, col.name, expr)
        }

        return function("toRow", VIEWER_ROW) {
            addModifiers(KModifier.PRIVATE)
            parameter("entity", entityClass)
            addCode(codeBlock {
                add("return %T(\n  id = entity.id.toString(),\n  values = ", VIEWER_ROW)
                add(values.joinToCodeList())
                add(",\n)")
            })
        }
    }

    private fun buildPredicateFunction(entityClass: ClassName): FunSpec =
        function("predicateFor", PREDICATE.parameterizedBy(entityClass)) {
            addModifiers(KModifier.PRIVATE)
            parameter("filter", VIEWER_FILTER)
            addCode(codeBlock {
                statement(
                        "val column = columnsByName[filter.column] ?: throw %T(%P)",
                        VIEWER_BAD_REQUEST, "Unknown filter column '\${filter.column}'.",
                    )
                statement("return %T.predicate(column, filter, enumNames[column.name])", VIEWER_FILTERS)
            })
        }

    private fun generateRegistry(schemas: List<SchemaInput>): FileSpec {
        val clientClass = ClassName(packageName, "EntClient")
        val entityList = schemas.joinToString(", ") { "${it.name}ViewerEntity" }
        return kotlinFile(packageName, "GeneratedEntViewerRegistry") {
            property(
                    "GeneratedEntViewerRegistry",
                    ClassName("kotlin.collections", "List")
                        .parameterizedBy(VIEWER_ENTITY.parameterizedBy(clientClass)),
                ) {
                addKdoc(
                    "Every generated viewer entity, in schema order.\n" +
                        "Pass to `EntViewer(client, GeneratedEntViewerRegistry) { ... }`.\n",
                )
                initializer("listOf(%L)", entityList)
            }
        }
    }
}

private fun List<CodeBlock>.joinToCodeList(): CodeBlock {
    if (isEmpty()) return CodeBlock.of("emptyList()")
    return codeBlock {
        add("listOf(\n")
        this@joinToCodeList.forEach { add("  %L,\n", it) }
        add(")")
    }
}
