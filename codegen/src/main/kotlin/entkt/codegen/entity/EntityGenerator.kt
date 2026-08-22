package entkt.codegen.entity

import entkt.codegen.apiName
import com.squareup.kotlinpoet.AnnotationSpec
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
import entkt.codegen.columnName
import entkt.codegen.metadata.ENTITY_SCHEMA
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.entitySchemaCodeBlock
import entkt.codegen.metadata.fkPropertyKdoc
import entkt.codegen.metadata.kotlinxJsonSerializerOptIns
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.runtime.driver.JsonMapperIds
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType

private val EDGE_REF = ClassName("entkt.query", "EdgeRef")
private val EDGE_STATE = ClassName("entkt.runtime.query", "EdgeState")
private val ENT_ENTITY = ClassName("entkt.runtime.entity", "EntEntity")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val NOOP_DRIVER = ClassName("entkt.runtime.driver", "NoopDriver")
private val ANY_NULLABLE = Any::class.asTypeName().copy(nullable = true)
private val ROW_TYPE = ClassName("kotlin.collections", "Map")
    .parameterizedBy(STRING, ANY_NULLABLE)

internal class EntityGenerator(
    private val packageName: String,
    private val jsonMapper: String = entkt.runtime.driver.JsonMapperIds.KOTLINX,
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
            .initializer(entitySchemaCodeBlock(schemaName, schema, schemaNames, jsonMapper))
            .build()
        val fromRowFn = buildFromRowFunction(entityClass, schema, schemaNames)

        // Build Edges inner data class for schemas with edges
        val edgeDescriptors = schema.edges().mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val targetClass = ClassName(packageName, targetName)
            EdgeDescriptor(edge.apiName, targetClass, edge.kind is EdgeKind.BelongsTo || edge.kind is EdgeKind.HasOne, edge.comment)
        }
        val edgesClass = if (edgeDescriptors.isNotEmpty()) buildEdgesClass(edgeDescriptors) else null
        val edgesClassName = entityClass.nestedClass("Edges")

        val typeSpec = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.DATA)
            .addSuperinterface(entEntityIdContract(schema.id().type))
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
            .apply {
                // Kotlin's data-class equals/hashCode compare ByteArray
                // properties by reference, so two separately loaded
                // entities with identical bytes would compare unequal.
                // Override with content comparison when a BYTES field
                // exists; other schemas keep the data-class defaults.
                if (allFields.any { it.type == FieldType.BYTES }) {
                    addFunction(buildEquals(entityClass, allFields, edgeFks, edgesClass != null))
                    addFunction(buildHashCode(allFields, edgeFks, edgesClass != null))
                }
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
        // EdgeRef constructor is opt-in-restricted so it can't be
        // fabricated from application code.
        // Emitting the file-level OptIn lets the per-edge initializers
        // compile without per-call annotation.
        val fileOptIn = AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            .addMember("%T::class", ENTKT_INTERNAL)
        if (jsonMapper == JsonMapperIds.KOTLINX) {
            allFields.asSequence()
                .filter { it.type == FieldType.JSON }
                .mapNotNull { it.jsonType }
                .flatMap { kotlinxJsonSerializerOptIns(it).asSequence() }
                .distinct()
                .sortedBy { it.canonicalName }
                .forEach { fileOptIn.addMember("%T::class", it) }
        }

        return FileSpec.builder(packageName, className)
            .addAnnotation(fileOptIn.build())
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
            val prop = field.apiName
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
                // resolvedTypeName is JSON-aware (returns the @Serializable
                // class); identical to toTypeName for scalars/pgvector. The
                // driver returns the decoded Kotlin value, so this is a plain cast.
                val base = field.resolvedTypeName()
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

        val fromRow = FunSpec.builder("fromRow")
            .addParameter("row", ROW_TYPE)
            .returns(entityClass)
            .addCode(body.build())
        // A parameterized JSON type (List<Rect>) can only be cast unchecked —
        // the driver decoded it through the column's registered serializer, so
        // the erased cast is sound. Suppress only when such a field exists.
        if (allFields.any { it.type == FieldType.JSON && it.jsonType?.arguments?.isNotEmpty() == true }) {
            fromRow.addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("%S", "UNCHECKED_CAST")
                    .build(),
            )
        }
        return fromRow.build()
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
            val param = ParameterSpec.builder(field.apiName, typeName)
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
            .addModifiers(KModifier.OVERRIDE)
            .initializer("id")
            .build()
    }

    private fun entEntityIdContract(type: FieldType): ClassName = when (type) {
        FieldType.INT -> ENT_ENTITY.nestedClass("IntId")
        FieldType.LONG -> ENT_ENTITY.nestedClass("LongId")
        FieldType.UUID -> ENT_ENTITY.nestedClass("UuidId")
        FieldType.STRING -> ENT_ENTITY.nestedClass("StringId")
        else -> error("unsupported entity id type: $type")
    }

    private fun buildProperty(field: Field): PropertySpec {
        val typeName = field.resolvedTypeName().let {
            if (field.nullable) it.copy(nullable = true) else it
        }
        val propertyName = field.apiName
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
            val prop = field.apiName
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
     * Explicit structural `equals` for entities with BYTES fields:
     * ByteArray properties compare via `contentEquals` (nullable-safe);
     * every other property keeps `==`, matching what the data-class
     * default would do. Component order mirrors the constructor.
     */
    private fun buildEquals(
        entityClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
        hasEdges: Boolean,
    ): FunSpec {
        val body = CodeBlock.builder()
            .addStatement("if (this === other) return true")
            .addStatement("if (other !is %T) return false", entityClass)
            .addStatement("if (id != other.id) return false")
        for (field in fields) {
            val prop = field.apiName
            if (field.type == FieldType.BYTES) {
                body.addStatement("if (!(%L contentEquals other.%L)) return false", prop, prop)
            } else {
                body.addStatement("if (%L != other.%L) return false", prop, prop)
            }
        }
        for (fk in edgeFks) {
            body.addStatement("if (%L != other.%L) return false", fk.propertyName, fk.propertyName)
        }
        if (hasEdges) {
            body.addStatement("if (edges != other.edges) return false")
        }
        body.addStatement("return true")
        return FunSpec.builder("equals")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("other", ANY_NULLABLE)
            .returns(Boolean::class)
            .addCode(body.build())
            .build()
    }

    /** Companion to [buildEquals]: ByteArray properties hash via `contentHashCode`. */
    private fun buildHashCode(
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
        hasEdges: Boolean,
    ): FunSpec {
        val body = CodeBlock.builder()
            .addStatement("var result = id.hashCode()")
        for (field in fields) {
            val prop = field.apiName
            val expr = when {
                field.type == FieldType.BYTES && field.nullable -> "($prop?.contentHashCode() ?: 0)"
                field.type == FieldType.BYTES -> "$prop.contentHashCode()"
                field.nullable -> "($prop?.hashCode() ?: 0)"
                else -> "$prop.hashCode()"
            }
            body.addStatement("result = 31 * result + %L", expr)
        }
        for (fk in edgeFks) {
            val expr =
                if (fk.required) "${fk.propertyName}.hashCode()"
                else "(${fk.propertyName}?.hashCode() ?: 0)"
            body.addStatement("result = 31 * result + %L", expr)
        }
        if (hasEdges) {
            body.addStatement("result = 31 * result + edges.hashCode()")
        }
        body.addStatement("return result")
        return FunSpec.builder("hashCode")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Int::class)
            .addCode(body.build())
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
        val propertyName = field.apiName
        val nullable = field.nullable
        if (field.type == FieldType.ENUM) {
            // EnumColumn carries a `fromName` decoder so it can be an aggregate
            // group key (the driver returns the stored `.name` String, which the
            // grouped terminal maps back to the enum via this lambda).
            val enumTypeName = field.resolvedTypeName()
            val cls = if (nullable) ClassName("entkt.query", "NullableEnumColumn")
            else ClassName("entkt.query", "EnumColumn")
            // Phantom-typed columns: first type arg is the owning entity
            // (`E`), second is the value type (`T`).
            val columnType = cls.parameterizedBy(entityClass, enumTypeName)
            return PropertySpec.builder(propertyName, columnType)
                .initializer("%T(%S) { %T.valueOf(it) }", columnType, field.columnName, enumTypeName)
                .build()
        }
        val columnType = if (field.type == FieldType.JSON) {
            // Narrow JSON column ref: JsonColumn<E, T> (null checks only).
            val jsonTypeName = field.resolvedTypeName()
            val cls = if (nullable) ClassName("entkt.query", "NullableJsonColumn")
            else ClassName("entkt.query", "JsonColumn")
            cls.parameterizedBy(entityClass, jsonTypeName)
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
        val propertyName = edge.apiName
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
    /** The edge's Kotlin declaration name — the generated member name. */
    val apiName: String,
    val targetClass: ClassName,
    val toOne: Boolean,
    val comment: String? = null,
)

/**
 * Build the inner `Edges` data class for an entity. Every property is
 * an `EdgeState` defaulting to `Unloaded`: to-one edges wrap a nullable
 * target (`EdgeState<Target?>` — always nullable inside `Loaded`, even
 * for a required FK, because eager predicates, interceptors, or bounds
 * can exclude the target); to-many edges wrap a non-null list
 * (`EdgeState<List<Target>>`).
 */
private fun buildEdgesClass(edges: List<EdgeDescriptor>): TypeSpec {
    val constructor = FunSpec.constructorBuilder()
    val properties = mutableListOf<PropertySpec>()

    for (edge in edges) {
        val propName = edge.apiName
        val propType = if (edge.toOne) {
            EDGE_STATE.parameterizedBy(edge.targetClass.copy(nullable = true))
        } else {
            EDGE_STATE.parameterizedBy(List::class.asClassName().parameterizedBy(edge.targetClass))
        }
        constructor.addParameter(
            ParameterSpec.builder(propName, propType)
                .defaultValue("%T.Unloaded", EDGE_STATE)
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
        FieldType.LONG -> {
            // Integral numeric → sum returns Long?; also comparable (min/max) and groupable.
            val cls = if (nullable) ClassName("entkt.query", "NullableIntegralColumn")
            else ClassName("entkt.query", "IntegralColumn")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
        FieldType.FLOAT,
        FieldType.DOUBLE -> {
            // Floating numeric → sum returns Double?; also comparable and groupable.
            val cls = if (nullable) ClassName("entkt.query", "NullableFloatingColumn")
            else ClassName("entkt.query", "FloatingColumn")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
        FieldType.TIME -> {
            val cls = if (nullable) ClassName("entkt.query", "NullableComparableColumn")
            else ClassName("entkt.query", "ComparableColumn")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
        FieldType.BOOL,
        FieldType.UUID -> {
            // Groupable but not comparable: usable as a group key, not for min/max/sum/avg.
            val cls = if (nullable) ClassName("entkt.query", "NullableGroupableScalarColumn")
            else ClassName("entkt.query", "GroupableScalarColumn")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
        FieldType.BYTES,
        FieldType.ENUM -> {
            // BYTES is neither groupable nor comparable. (ENUM field refs are
            // built in buildFieldColumnRef; ENUM only reaches here for the rare
            // case of an enum-typed id/FK column.)
            val cls = if (nullable) ClassName("entkt.query", "NullableColumn")
            else ClassName("entkt.query", "Column")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
        // Vector field: a plain Column<E, PgVector> (no comparable/equality
        // helpers — those make no sense for a vector). Distance-ordering helpers
        // are added on top in Phase 6.
        FieldType.PGVECTOR -> {
            val cls = if (nullable) ClassName("entkt.query", "NullableColumn")
            else ClassName("entkt.query", "Column")
            cls.parameterizedBy(entityClass, type.toTypeName())
        }
        // JSON: a narrow JsonColumn<E, T> (null checks only, no scalar helpers),
        // built in buildColumnRef from Field.jsonType — never via this map.
        FieldType.JSON -> error("JSON column type is resolved from jsonType in buildColumnRef")
    }
}
