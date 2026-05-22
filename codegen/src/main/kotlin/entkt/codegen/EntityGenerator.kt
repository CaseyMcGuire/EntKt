package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType

private val EDGE_REF = ClassName("entkt.query", "EdgeRef")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val NOOP_DRIVER = ClassName("entkt.runtime", "NoopDriver")
private val ANY_NULLABLE = Any::class.asTypeName().copy(nullable = true)
private val ROW_TYPE = ClassName("kotlin.collections", "Map")
    .parameterizedBy(STRING, ANY_NULLABLE)

internal class EntityGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = schemaName
        val idField = buildIdProperty(schema)
        // Backing FK columns flow through `edgeFks` so the relationship's
        // nullability and unique flags drive the generated property, not
        // the scalar field declaration.
        val allFields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val entityClass = ClassName(packageName, className)
        val columnRefs = buildList {
            add(buildIdColumnRef(schema, entityClass))
            addAll(allFields.map { buildFieldColumnRef(it, entityClass) })
            addAll(edgeFks.map { buildEdgeColumnRef(it, entityClass) })
        }
        // Edge refs are emitted for *every* declared edge — including
        // non-unique to-many edges that don't get a synthetic FK column.
        // The runtime uses these to lower has/exists predicates.
        val edgeRefs = schema.edges()
            .mapNotNull { edge -> buildEdgeRef(edge, entityClass, schemaNames) }
        val tableName = schema.tableName
        val tableProperty = PropertySpec.builder("TABLE", STRING)
            .initializer("%S", tableName)
            .build()
        val schemaProperty = PropertySpec.builder("SCHEMA", ENTITY_SCHEMA)
            .initializer(entitySchemaCodeBlock(schemaName, schema, schemaNames))
            .build()
        val fromRowFn = buildFromRowFunction(entityClass, schema, schemaNames)

        // Build Edges inner data class for schemas with edges
        val edgeDescriptors = schema.edges().mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val targetClass = ClassName(packageName, targetName)
            EdgeDescriptor(edge.name, targetClass, edge.kind is EdgeKind.BelongsTo || edge.kind is EdgeKind.HasOne, edge.comment)
        }
        val edgesClass = if (edgeDescriptors.isNotEmpty()) buildEdgesClass(edgeDescriptors) else null
        val edgesClassName = entityClass.nestedClass("Edges")

        val typeSpec = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(buildConstructor(idField, allFields, edgeFks, edgesClass?.let { edgesClassName }))
            .addProperty(idField)
            .addProperties(allFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeProperty(it) })
            .apply {
                if (edgesClass != null) {
                    addProperty(
                        PropertySpec.builder("edges", edgesClassName)
                            .initializer("edges")
                            .build()
                    )
                    addType(edgesClass)
                }
            }
            .apply {
                val toStringFn = buildToString(className, schema, edgeFks, edgesClass != null)
                if (toStringFn != null) addFunction(toStringFn)
            }
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addProperty(tableProperty)
                    .addProperty(schemaProperty)
                    .addProperties(columnRefs)
                    .addProperties(edgeRefs)
                    .addFunction(fromRowFn)
                    .build()
            )
            .build()

        // Every generated entity file constructs `EdgeRef(...)` and
        // therefore needs `@file:OptIn(EntktInternal::class)` — the
        // EdgeRef constructor is opt-in-restricted per RFC §"Constructor
        // Visibility" so it can't be fabricated from application code.
        // Emitting the file-level OptIn lets the per-edge initializers
        // compile without per-call annotation.
        return FileSpec.builder(packageName, className)
            .addAnnotation(
                com.squareup.kotlinpoet.AnnotationSpec.builder(
                    ClassName("kotlin", "OptIn"),
                )
                    .useSiteTarget(com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ENTKT_INTERNAL)
                    .build()
            )
            .addType(typeSpec)
            .build()
    }

    /**
     * Emit a `fromRow(row: Map<String, Any?>): Entity` on the companion.
     * The driver hands back typed values (`Instant`, `UUID`, ...), so
     * this is almost entirely unchecked casts — the driver's per-column
     * metadata is the authority. Null columns for non-nullable fields
     * are a driver/schema bug and will surface as a ClassCastException,
     * which is more useful than a silent null-coalesce.
     */
    private fun buildFromRowFunction(
        entityClass: ClassName,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val allFields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        val idType = schema.id().type.toTypeName()

        val body = CodeBlock.builder()
            .add("return %T(\n", entityClass)
            .add("  id = row[%S] as %T,\n", "id", idType)

        for (field in allFields) {
            val prop = toCamelCase(field.name)
            val col = field.columnName
            val nullable = field.nullable
            if (field.type == FieldType.ENUM) {
                val enumType = field.resolvedTypeName()
                if (nullable) {
                    body.add(
                        "  %L = (row[%S] as %T?)?.let { %T.valueOf(it) },\n",
                        prop, col, String::class, enumType,
                    )
                } else {
                    body.add(
                        "  %L = %T.valueOf(row[%S] as %T),\n",
                        prop, enumType, col, String::class,
                    )
                }
            } else {
                val base = field.type.toTypeName()
                val target = base.copy(nullable = nullable)
                body.add("  %L = row[%S] as %T,\n", prop, col, target)
            }
        }

        for (fk in edgeFks) {
            val base = fk.idType.toTypeName()
            val target = base.copy(nullable = !fk.required)
            body.add("  %L = row[%S] as %T,\n", fk.propertyName, fk.columnName, target)
        }

        body.add(")")

        return FunSpec.builder("fromRow")
            .addParameter("row", ROW_TYPE)
            .returns(entityClass)
            .addCode(body.build())
            .build()
    }

    private fun buildConstructor(
        idProperty: PropertySpec,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
        edgesClassName: ClassName? = null,
    ): FunSpec {
        val builder = FunSpec.constructorBuilder()
            .addParameter(
                ParameterSpec.builder(idProperty.name, idProperty.type).build()
            )

        for (field in fields) {
            val typeName = field.resolvedTypeName().let {
                if (field.nullable) it.copy(nullable = true) else it
            }
            val param = ParameterSpec.builder(toCamelCase(field.name), typeName)
            if (field.nullable) {
                param.defaultValue("null")
            }
            builder.addParameter(param.build())
        }

        for (fk in edgeFks) {
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            val param = ParameterSpec.builder(fk.propertyName, typeName)
            if (!fk.required) {
                param.defaultValue("null")
            }
            builder.addParameter(param.build())
        }

        if (edgesClassName != null) {
            builder.addParameter(
                ParameterSpec.builder("edges", edgesClassName)
                    .defaultValue("%T()", edgesClassName)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildIdProperty(schema: EntSchema): PropertySpec {
        val idType = schema.id().type.toTypeName()
        return PropertySpec.builder("id", idType)
            .initializer("id")
            .build()
    }

    private fun buildProperty(field: Field): PropertySpec {
        val typeName = field.resolvedTypeName().let {
            if (field.nullable) it.copy(nullable = true) else it
        }
        val propertyName = toCamelCase(field.name)
        val builder = PropertySpec.builder(propertyName, typeName)
            .initializer(propertyName)
        val comment = field.comment
        if (comment != null) builder.addKdoc("%L", comment)
        return builder.build()
    }

    private fun buildEdgeProperty(fk: EdgeFk): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
        return PropertySpec.builder(fk.propertyName, typeName)
            .initializer(fk.propertyName)
            .addKdoc("%L", fkPropertyKdoc(fk))
            .build()
    }

    /**
     * Generate an explicit `toString()` that redacts sensitive fields.
     * Returns null when no fields are sensitive — Kotlin's data class
     * toString is fine in that case.
     */
    private fun buildToString(
        className: String,
        schema: EntSchema,
        edgeFks: List<EdgeFk>,
        hasEdges: Boolean,
    ): FunSpec? {
        val allFields = scalarFields(schema)
        // Generate a custom toString if *any* scalar field or
        // field-backed FK carries `.sensitive()`. Implicit FKs can't be
        // sensitive (no DSL surface for it).
        if (allFields.none { it.sensitive } && edgeFks.none { it.sensitive }) return null

        val parts = mutableListOf<String>()
        parts.add("id=\$id")
        for (field in allFields) {
            val prop = toCamelCase(field.name)
            parts.add(if (field.sensitive) "$prop=***" else "$prop=\$$prop")
        }
        for (fk in edgeFks) {
            parts.add(if (fk.sensitive) "${fk.propertyName}=***" else "${fk.propertyName}=\$${fk.propertyName}")
        }
        if (hasEdges) {
            parts.add("edges=\$edges")
        }

        val template = "$className(${parts.joinToString(", ")})"
        return FunSpec.builder("toString")
            .addModifiers(KModifier.OVERRIDE)
            .returns(String::class)
            .addStatement("return %P", template)
            .build()
    }

    /**
     * Companion column ref for the entity's id column. Surfaced so
     * callers can write `where(Foo.id eq someId)` when they need to
     * compose an id filter into a query — e.g. to chain a forward
     * M2M traversal off a single known entity. The id column is
     * always non-null and named "id"; type follows the schema's
     * `id()` declaration.
     */
    private fun buildIdColumnRef(schema: EntSchema, entityClass: ClassName): PropertySpec {
        val idType = schema.id().type
        val columnType = columnClassFor(idType, nullable = false, entityClass)
        return PropertySpec.builder("id", columnType)
            .initializer("%T(%S)", columnType, "id")
            .build()
    }

    private fun buildFieldColumnRef(field: Field, entityClass: ClassName): PropertySpec {
        val propertyName = toCamelCase(field.name)
        val nullable = field.nullable
        val columnType = if (field.type == FieldType.ENUM) {
            val enumTypeName = field.resolvedTypeName()
            val cls = if (nullable) ClassName("entkt.query", "NullableEnumColumn")
            else ClassName("entkt.query", "EnumColumn")
            // Phantom-typed columns: first type arg is the owning entity
            // (`E`), second is the value type (`T`).
            cls.parameterizedBy(entityClass, enumTypeName)
        } else {
            columnClassFor(field.type, nullable, entityClass)
        }
        return PropertySpec.builder(propertyName, columnType)
            .initializer("%T(%S)", columnType, field.columnName)
            .build()
    }

    private fun buildEdgeColumnRef(fk: EdgeFk, entityClass: ClassName): PropertySpec {
        val nullable = !fk.required
        val columnType = columnClassFor(fk.idType, nullable, entityClass)
        return PropertySpec.builder(fk.propertyName, columnType)
            .initializer("%T(%S)", columnType, fk.columnName)
            .build()
    }

    private fun buildEdgeRef(
        edge: Edge,
        sourceEntity: ClassName,
        schemaNames: Map<EntSchema, String>,
    ): PropertySpec? {
        val targetName = schemaNames[edge.target] ?: return null
        val targetEntity = ClassName(packageName, targetName)
        val targetQuery = ClassName(packageName, "${targetName}Query")
        // EdgeRef now carries three type args: Source, Target, Q.
        val edgeRefType = EDGE_REF.parameterizedBy(sourceEntity, targetEntity, targetQuery)
        val propertyName = toCamelCase(edge.name)
        // EdgeRef.has { block } only accumulates predicates off the
        // query — it never calls the driver — so we hand it NoopDriver
        // and bail loudly if something tries to run a terminal op
        // inside `has { }`.
        //
        // The EdgeRef constructor is `@EntktInternal`; the surrounding
        // FileSpec carries `@file:OptIn(EntktInternal::class)` so the
        // call site compiles without a per-call opt-in.
        return PropertySpec.builder(propertyName, edgeRefType)
            .initializer("%T(%S) { %T(%T) }", EDGE_REF, edge.name, targetQuery, NOOP_DRIVER)
            .build()
    }
}

/**
 * Describes one edge on a schema for the purpose of building the `Edges`
 * inner data class on the entity.
 */
internal data class EdgeDescriptor(
    val name: String,
    val targetClass: ClassName,
    val toOne: Boolean,
    val comment: String? = null,
)

/**
 * Build the inner `Edges` data class for an entity. To-one edges become
 * a nullable target entity; to-many edges become a nullable list.
 */
private fun buildEdgesClass(edges: List<EdgeDescriptor>): TypeSpec {
    val constructor = FunSpec.constructorBuilder()
    val properties = mutableListOf<PropertySpec>()

    for (edge in edges) {
        val propName = toCamelCase(edge.name)
        val propType = if (edge.toOne) {
            edge.targetClass.copy(nullable = true)
        } else {
            List::class.asClassName().parameterizedBy(edge.targetClass).copy(nullable = true)
        }
        constructor.addParameter(
            ParameterSpec.builder(propName, propType)
                .defaultValue("null")
                .build()
        )
        val propBuilder = PropertySpec.builder(propName, propType)
            .initializer(propName)
        if (edge.comment != null) propBuilder.addKdoc("%L", edge.comment)
        properties.add(propBuilder.build())
    }

    return TypeSpec.classBuilder("Edges")
        .addModifiers(KModifier.DATA)
        .primaryConstructor(constructor.build())
        .addProperties(properties)
        .build()
}

internal fun columnClassFor(type: FieldType, nullable: Boolean, entityClass: ClassName): TypeName {
    // Phantom-typed columns: every column class is parameterized by
    // `<E, T>` (the StringColumn variants and EnumColumn `<E, T>` carry
    // the same E in the first position). The entity scope flows in as
    // the first type argument; the value type is the second (when the
    // column class takes one).
    return when (type) {
        FieldType.STRING, FieldType.TEXT -> {
            val cls = if (nullable) ClassName("entkt.query", "NullableStringColumn")
            else ClassName("entkt.query", "StringColumn")
            // StringColumn<E> takes only the entity-scope parameter;
            // its value type is fixed as `String` by the class.
            cls.parameterizedBy(entityClass)
        }
        FieldType.INT,
        FieldType.LONG,
        FieldType.FLOAT,
        FieldType.DOUBLE,
        FieldType.TIME -> {
            val cls = if (nullable) ClassName("entkt.query", "NullableComparableColumn")
            else ClassName("entkt.query", "ComparableColumn")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
        FieldType.BOOL,
        FieldType.UUID,
        FieldType.BYTES,
        FieldType.ENUM -> {
            val cls = if (nullable) ClassName("entkt.query", "NullableColumn")
            else ClassName("entkt.query", "Column")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
    }
}

/** The database column name for this field. */
internal val Field.columnName: String get() = name

internal fun toCamelCase(snakeCase: String): String {
    return snakeCase.split("_").mapIndexed { index, part ->
        if (index == 0) part.lowercase()
        else part.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}