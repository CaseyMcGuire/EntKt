package entkt.codegen.entity

import entkt.codegen.apiName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import entkt.codegen.columnName
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.companionObject
import entkt.codegen.kotlinpoet.constructor
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
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
        val tableProperty = property("TABLE", STRING) { initializer("%S", tableName) }
        val schemaProperty = property("SCHEMA", ENTITY_SCHEMA) {
            initializer(entitySchemaCodeBlock(schemaName, schema, schemaNames, jsonMapper))
        }
        val fromRowFn = buildFromRowFunction(entityClass, schema, schemaNames)

        // Build Edges inner data class for schemas with edges
        val edgeDescriptors = schema.edges().mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val targetClass = ClassName(packageName, targetName)
            EdgeDescriptor(edge.apiName, targetClass, edge.kind is EdgeKind.BelongsTo || edge.kind is EdgeKind.HasOne, edge.comment)
        }
        val edgesClass = if (edgeDescriptors.isNotEmpty()) buildEdgesClass(edgeDescriptors) else null
        val edgesClassName = entityClass.nestedClass("Edges")

        val typeSpec = classType(className) {
            addModifiers(KModifier.DATA)
            addSuperinterface(entEntityIdContract(schema.id().type))
            primaryConstructor(buildConstructor(idField, allFields, edgeFks, edgesClass?.let { edgesClassName }))
            addProperty(idField)
            addProperties(allFields.map { buildProperty(it) })
            addProperties(edgeFks.map { buildEdgeProperty(it) })
            if (edgesClass != null) {
                property("edges", edgesClassName) { initializer("edges") }
                addType(edgesClass)
            }
            buildToString(className, schema, edgeFks, edgesClass != null)?.let(::addFunction)
            // Kotlin's data-class equals/hashCode compare ByteArray properties by reference.
            if (allFields.any { it.type == FieldType.BYTES }) {
                addFunction(buildEquals(entityClass, allFields, edgeFks, edgesClass != null))
                addFunction(buildHashCode(allFields, edgeFks, edgesClass != null))
            }
            companionObject {
                addProperty(tableProperty)
                addProperty(schemaProperty)
                addProperties(columnRefs)
                addProperties(edgeRefs)
                addFunction(fromRowFn)
            }
        }

        // Every generated entity file constructs `EdgeRef(...)` and
        // therefore needs `@file:OptIn(EntktInternal::class)` — the
        // EdgeRef constructor is opt-in-restricted so it can't be
        // fabricated from application code.
        // Emitting the file-level OptIn lets the per-edge initializers
        // compile without per-call annotation.
        val serializerOptIns = if (jsonMapper == JsonMapperIds.KOTLINX) {
            allFields.asSequence()
                .filter { it.type == FieldType.JSON }
                .mapNotNull { it.jsonType }
                .flatMap { kotlinxJsonSerializerOptIns(it).asSequence() }
                .distinct()
                .sortedBy { it.canonicalName }
                .toList()
        } else emptyList()

        return kotlinFile(packageName, className) {
            addAnnotation(annotation(ClassName("kotlin", "OptIn")) {
                useSiteTarget(com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.FILE)
                addMember("%T::class", ENTKT_INTERNAL)
                serializerOptIns.forEach { addMember("%T::class", it) }
            })
            addType(typeSpec)
        }
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

        val body = codeBlock {
            add("return %T(\n", entityClass)
            add("  id = row[%S] as %T,\n", "id", idType)

            for (field in allFields) {
                val prop = field.apiName
                val col = field.columnName
                val nullable = field.nullable
                if (field.type == FieldType.ENUM) {
                    val enumType = field.resolvedTypeName()
                    if (nullable) {
                        add(
                            "  %L = (row[%S] as %T?)?.let { %T.valueOf(it) },\n",
                            prop, col, String::class, enumType,
                        )
                    } else {
                        add(
                            "  %L = %T.valueOf(row[%S] as %T),\n",
                            prop, enumType, col, String::class,
                        )
                    }
                } else {
                    // Driver values are already decoded; materialization is a cast.
                    val target = field.resolvedTypeName().copy(nullable = nullable)
                    add("  %L = row[%S] as %T,\n", prop, col, target)
                }
            }

            for (fk in edgeFks) {
                val target = fk.idType.toTypeName().copy(nullable = !fk.required)
                add("  %L = row[%S] as %T,\n", fk.propertyName, fk.columnName, target)
            }

            add(")")
        }

        return function("fromRow", entityClass) {
            parameter("row", ROW_TYPE)
            addCode(body)
            // Parameterized JSON types require an erased cast after driver decoding.
            if (allFields.any { it.type == FieldType.JSON && it.jsonType?.arguments?.isNotEmpty() == true }) {
                addAnnotation(annotation(Suppress::class.asClassName()) {
                    addMember("%S", "UNCHECKED_CAST")
                })
            }
        }
    }

    private fun buildConstructor(
        idProperty: PropertySpec,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
        edgesClassName: ClassName? = null,
    ): FunSpec {
        return constructor {
            parameter(idProperty.name, idProperty.type)
            for (field in fields) {
                val typeName = field.resolvedTypeName().copy(nullable = field.nullable)
                parameter(field.apiName, typeName) {
                    if (field.nullable) defaultValue("null")
                }
            }
            for (fk in edgeFks) {
                val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
                parameter(fk.propertyName, typeName) {
                    if (!fk.required) defaultValue("null")
                }
            }
            if (edgesClassName != null) {
                parameter("edges", edgesClassName) { defaultValue("%T()", edgesClassName) }
            }
        }
    }

    private fun buildIdProperty(schema: EntSchema): PropertySpec {
        val idType = schema.id().type.toTypeName()
        return property("id", idType) {
            addModifiers(KModifier.OVERRIDE)
            initializer("id")
        }
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
        return property(propertyName, typeName) {
            initializer(propertyName)
            field.comment?.let { addKdoc("%L", it) }
        }
    }

    private fun buildEdgeProperty(fk: EdgeFk): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
        return property(fk.propertyName, typeName) {
            initializer(fk.propertyName)
            addKdoc("%L", fkPropertyKdoc(fk))
        }
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
        return function("toString", String::class.asTypeName()) {
            addModifiers(KModifier.OVERRIDE)
            statement("return %P", template)
        }
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
        val body = codeBlock {
            statement("if (this === other) return true")
            statement("if (other !is %T) return false", entityClass)
            statement("if (id != other.id) return false")
            for (field in fields) {
                val prop = field.apiName
                if (field.type == FieldType.BYTES) {
                    statement("if (!(%L contentEquals other.%L)) return false", prop, prop)
                } else {
                    statement("if (%L != other.%L) return false", prop, prop)
                }
            }
            for (fk in edgeFks) {
                statement("if (%L != other.%L) return false", fk.propertyName, fk.propertyName)
            }
            if (hasEdges) statement("if (edges != other.edges) return false")
            statement("return true")
        }
        return function("equals", Boolean::class.asTypeName()) {
            addModifiers(KModifier.OVERRIDE)
            parameter("other", ANY_NULLABLE)
            addCode(body)
        }
    }

    /** Companion to [buildEquals]: ByteArray properties hash via `contentHashCode`. */
    private fun buildHashCode(
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
        hasEdges: Boolean,
    ): FunSpec {
        val body = codeBlock {
            statement("var result = id.hashCode()")
            for (field in fields) {
                val prop = field.apiName
                val expr = when {
                    field.type == FieldType.BYTES && field.nullable -> "($prop?.contentHashCode() ?: 0)"
                    field.type == FieldType.BYTES -> "$prop.contentHashCode()"
                    field.nullable -> "($prop?.hashCode() ?: 0)"
                    else -> "$prop.hashCode()"
                }
                statement("result = 31 * result + %L", expr)
            }
            for (fk in edgeFks) {
                val expr = if (fk.required) {
                    "${fk.propertyName}.hashCode()"
                } else {
                    "(${fk.propertyName}?.hashCode() ?: 0)"
                }
                statement("result = 31 * result + %L", expr)
            }
            if (hasEdges) statement("result = 31 * result + edges.hashCode()")
            statement("return result")
        }
        return function("hashCode", Int::class.asTypeName()) {
            addModifiers(KModifier.OVERRIDE)
            addCode(body)
        }
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
        return property("id", columnType) { initializer("%T(%S)", columnType, "id") }
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
            return property(propertyName, columnType) {
                initializer("%T(%S) { %T.valueOf(it) }", columnType, field.columnName, enumTypeName)
            }
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
        return property(propertyName, columnType) {
            initializer("%T(%S)", columnType, field.columnName)
        }
    }

    private fun buildEdgeColumnRef(fk: EdgeFk, entityClass: ClassName): PropertySpec {
        val nullable = !fk.required
        val columnType = columnClassFor(fk.idType, nullable, entityClass)
        return property(fk.propertyName, columnType) {
            initializer("%T(%S)", columnType, fk.columnName)
        }
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
        return property(propertyName, edgeRefType) {
            initializer("%T(%S) { %T(%T) }", EDGE_REF, edge.name, targetQuery, NOOP_DRIVER)
        }
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
    val edgeProperties = edges.map { edge ->
        val type = if (edge.toOne) {
            EDGE_STATE.parameterizedBy(edge.targetClass.copy(nullable = true))
        } else {
            EDGE_STATE.parameterizedBy(List::class.asClassName().parameterizedBy(edge.targetClass))
        }
        edge to type
    }
    return classType("Edges") {
        addModifiers(KModifier.DATA)
        primaryConstructor {
            for ((edge, type) in edgeProperties) {
                parameter(edge.apiName, type) { defaultValue("%T.Unloaded", EDGE_STATE) }
            }
        }
        for ((edge, type) in edgeProperties) {
            property(edge.apiName, type) {
                initializer(edge.apiName)
                edge.comment?.let { addKdoc("%L", it) }
            }
        }
    }
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
