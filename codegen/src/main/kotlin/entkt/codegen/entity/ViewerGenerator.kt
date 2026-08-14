package entkt.codegen.entity

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import entkt.codegen.SchemaInput
import entkt.codegen.columnName
import entkt.codegen.metadata.ColumnDescriptor
import entkt.codegen.metadata.FIELD_TYPE
import entkt.codegen.metadata.columnMetadataFor
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.resolveEdgeJoin
import entkt.codegen.pluralize
import entkt.codegen.toCamelCase
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.FieldType

private val VIEWER_ENTITY = ClassName("entkt.viewer", "EntViewerEntity")
private val VIEWER_REGISTRY = ClassName("entkt.viewer", "EntViewerRegistry")
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
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
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
 * result decoding — never `Driver.query(...)`.
 *
 * When the flag is off (the default) no viewer files are emitted and the
 * application needs no `io.entkt:ent-viewer` dependency.
 */
internal class ViewerGenerator(private val packageName: String) {

    fun generate(schemas: List<SchemaInput>, schemaNames: Map<EntSchema, String>): List<FileSpec> {
        val files = schemas.map { generateAdapter(it, schemaNames) }
        return files + generateRegistry(schemas)
    }

    private fun routeName(entityName: String): String =
        entityName.replaceFirstChar { it.lowercase() }

    private fun generateAdapter(input: SchemaInput, schemaNames: Map<EntSchema, String>): FileSpec {
        val name = input.name
        val schema = input.schema
        val entityClass = ClassName(packageName, name)
        val clientClass = ClassName(packageName, "EntClient")
        val objectName = "${name}ViewerEntity"
        val repoProp = pluralize(name.replaceFirstChar { it.lowercase() })

        val columns = columnMetadataFor(schema, schemaNames)

        val type = TypeSpec.objectBuilder(objectName)
            .addSuperinterface(VIEWER_ENTITY.parameterizedBy(clientClass))
            .addKdoc(
                "Generated viewer adapter for [%T]. Reads go through the generated\n" +
                    "typed repo, so privacy, interceptors, and soft-delete filters apply.\n",
                entityClass,
            )
            .addProperty(
                PropertySpec.builder("schema", ClassName("entkt.runtime.driver", "EntitySchema"), KModifier.OVERRIDE)
                    .initializer("%T.SCHEMA", entityClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("displayName", String::class, KModifier.OVERRIDE)
                    .initializer("%S", name)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("routeName", String::class, KModifier.OVERRIDE)
                    .initializer("%S", routeName(name))
                    .build(),
            )
            .addProperty(buildColumnsProperty(columns, schema))
            .addProperty(buildEdgesProperty(schema, schemaNames))
            .addProperty(
                PropertySpec.builder(
                    "columnsByName",
                    ClassName("kotlin.collections", "Map").parameterizedBy(
                        ClassName("kotlin", "String"), VIEWER_COLUMN,
                    ),
                    KModifier.PRIVATE,
                )
                    .initializer("columns.associateBy { it.name }")
                    .build(),
            )
            .addProperty(buildEnumNamesProperty(schema))
            .addFunction(buildListFunction(input, clientClass, entityClass, repoProp))
            .addFunction(buildGetFunction(input, clientClass, entityClass, repoProp))
            .addFunction(buildToRowFunction(input, entityClass, schemaNames))
            .addFunction(buildPredicateFunction(entityClass))
            .build()

        return FileSpec.builder(packageName, objectName)
            .addType(type)
            .build()
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
        return PropertySpec.builder(
            "columns",
            ClassName("kotlin.collections", "List").parameterizedBy(VIEWER_COLUMN),
            KModifier.OVERRIDE,
        )
            .initializer(entries.joinToCodeList())
            .build()
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
            val targetRoute = targetName?.let { routeName(it) }
            val join = if (edge.kind is EdgeKind.ManyToMany) null else resolveEdgeJoin(edge, schema)
            val (cardinality, localFk, targetFilter) = when (edge.kind) {
                is EdgeKind.BelongsTo -> Triple("to-one", join?.sourceColumn, null)
                is EdgeKind.HasOne -> Triple("to-one", null, join?.targetColumn)
                is EdgeKind.HasMany -> Triple("to-many", null, join?.targetColumn)
                is EdgeKind.ManyToMany -> Triple("many-to-many", null, null)
            }
            CodeBlock.of(
                "%T(name = %S, targetRouteName = %L, cardinality = %S, localFkColumn = %L, targetFilterColumn = %L)",
                VIEWER_EDGE, edge.name,
                targetRoute?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
                cardinality,
                localFk?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
                targetFilter?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
            )
        }
        return PropertySpec.builder(
            "edges",
            ClassName("kotlin.collections", "List").parameterizedBy(VIEWER_EDGE),
            KModifier.OVERRIDE,
        )
            .initializer(entries.joinToCodeList())
            .build()
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
            CodeBlock.builder().add("mapOf(\n")
                .apply { entries.forEach { add("  %L,\n", it) } }
                .add(")")
                .build()
        }
        return PropertySpec.builder(
            "enumNames",
            ClassName("kotlin.collections", "Map").parameterizedBy(
                ClassName("kotlin", "String"),
                ClassName("kotlin.collections", "Set").parameterizedBy(ClassName("kotlin", "String")),
            ),
            KModifier.PRIVATE,
        )
            .initializer(initializer)
            .build()
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
        val body = CodeBlock.builder()
            .addStatement("val fetchLimit = request.pageSize + 1")
            .beginControlFlow("val result = client.%L.query", repoProp)
            .addStatement("for (filter in request.filters) `where`(predicateFor(filter))")
            .addStatement("val order = request.order")
            .beginControlFlow("if (order != null)")
            .addStatement(
                "val column = columnsByName[order.column] ?: throw %T(%P)",
                VIEWER_BAD_REQUEST, "Unknown order column '\${order.column}'.",
            )
            .addStatement(
                "if (!column.orderable) throw %T(%P)",
                VIEWER_BAD_REQUEST, "Column '\${column.name}' is not orderable.",
            )
            .addStatement(
                "orderBy(%T(order.column, if (order.descending) %T.DESC else %T.ASC))",
                ORDER_FIELD.parameterizedBy(entityClass), ORDER_DIRECTION, ORDER_DIRECTION,
            )
            .endControlFlow()
            .addStatement("limit(fetchLimit)")
            .addStatement("offset(request.offset)")
            .endControlFlow()
            .addStatement(".all()")
            .beginControlFlow("val rows = when (result)")
            .addStatement("is %T.Success -> result.value", READ_RESULT)
            .beginControlFlow("is %T.Failed ->", READ_RESULT)
            .addStatement("val e = result.exception")
            .beginControlFlow("if (e is %T && e.origin is %T.Root)", ENT_PRIVACY_DENIED, LOAD_DENIAL_ORIGIN)
            .addStatement(
                "return %T(emptyList(), hasNext = null, privacyFiltered = true)",
                VIEWER_LIST_RESULT,
            )
            .endControlFlow()
            .addStatement("throw e")
            .endControlFlow()
            .endControlFlow()
            .addStatement(
                "return %T(rows.take(request.pageSize).map { toRow(it) }, hasNext = rows.size > request.pageSize)",
                VIEWER_LIST_RESULT,
            )
            .build()
        return FunSpec.builder("list")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("client", clientClass)
            .addParameter("request", VIEWER_LIST_REQUEST)
            .returns(VIEWER_LIST_RESULT)
            .addCode(body)
            .build()
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
        return FunSpec.builder("get")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("client", clientClass)
            .addParameter("id", String::class)
            .returns(VIEWER_ROW.copy(nullable = true))
            .addCode(
                CodeBlock.builder()
                    // Unparseable id, missing row, and privacy-denied row all
                    // return null — the viewer's uniform not-found.
                    .addStatement("val parsed = %L", idParse)
                    .addStatement(
                        "val entity = client.%L.findById(parsed).%M().getOrThrow() ?: return·null",
                        repoProp, VISIBLE_OR_NULL,
                    )
                    .addStatement("return toRow(entity)")
                    .build(),
            )
            .build()
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
                // .propertyName), not toCamelCase(column).
                val prop = fkByColumn[col.name]?.propertyName
                    ?: field?.let { toCamelCase(it.name) }
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

        return FunSpec.builder("toRow")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("entity", entityClass)
            .returns(VIEWER_ROW)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(\n  id = entity.id.toString(),\n  values = ", VIEWER_ROW)
                    .add(values.joinToCodeList())
                    .add(",\n)")
                    .build(),
            )
            .build()
    }

    private fun buildPredicateFunction(entityClass: ClassName): FunSpec =
        FunSpec.builder("predicateFor")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("filter", VIEWER_FILTER)
            .returns(PREDICATE.parameterizedBy(entityClass))
            .addCode(
                CodeBlock.builder()
                    .addStatement(
                        "val column = columnsByName[filter.column] ?: throw %T(%P)",
                        VIEWER_BAD_REQUEST, "Unknown filter column '\${filter.column}'.",
                    )
                    .addStatement("return %T.predicate(column, filter, enumNames[column.name])", VIEWER_FILTERS)
                    .build(),
            )
            .build()

    private fun generateRegistry(schemas: List<SchemaInput>): FileSpec {
        val clientClass = ClassName(packageName, "EntClient")
        val entityList = schemas.joinToString(", ") { "${it.name}ViewerEntity" }
        val t = TypeVariableName("T")
        val type = TypeSpec.objectBuilder("GeneratedEntViewerRegistry")
            .addSuperinterface(VIEWER_REGISTRY.parameterizedBy(clientClass))
            .addKdoc(
                "Generated viewer registry: every generated entity, in schema order.\n" +
                    "Pass to `EntViewer(client, GeneratedEntViewerRegistry) { ... }`.\n",
            )
            .addProperty(
                PropertySpec.builder(
                    "entities",
                    ClassName("kotlin.collections", "List")
                        .parameterizedBy(VIEWER_ENTITY.parameterizedBy(clientClass)),
                    KModifier.OVERRIDE,
                )
                    .initializer("listOf(%L)", entityList)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("withPrivacyContext")
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(t)
                    .addParameter("client", clientClass)
                    .addParameter("context", PRIVACY_CONTEXT)
                    .addParameter(
                        "block",
                        com.squareup.kotlinpoet.LambdaTypeName.get(parameters = arrayOf(clientClass), returnType = t),
                    )
                    .returns(t)
                    .addStatement("return client.withPrivacyContext(context, block)")
                    .build(),
            )
            .build()
        return FileSpec.builder(packageName, "GeneratedEntViewerRegistry")
            .addType(type)
            .build()
    }
}

private fun List<CodeBlock>.joinToCodeList(): CodeBlock {
    if (isEmpty()) return CodeBlock.of("emptyList()")
    val builder = CodeBlock.builder().add("listOf(\n")
    forEach { builder.add("  %L,\n", it) }
    return builder.add(")").build()
}
