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
import com.squareup.kotlinpoet.asTypeName
import entkt.schema.Edge
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType

private val EDGE_REF = ClassName("entkt.query", "EdgeRef")
private val NOOP_DRIVER = ClassName("entkt.runtime", "NoopDriver")
private val ANY_NULLABLE = Any::class.asTypeName().copy(nullable = true)
private val ROW_TYPE = ClassName("kotlin.collections", "Map")
    .parameterizedBy(STRING, ANY_NULLABLE)

class EntityGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = schemaName
        val idField = buildIdProperty(schema)
        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val allFields = fields + mixinFields
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val columnRefs = buildList {
            addAll(allFields.map { buildFieldColumnRef(it) })
            addAll(edgeFks.map { buildEdgeColumnRef(it) })
        }
        // Edge refs are emitted for *every* declared edge — including
        // non-unique to-many edges that don't get a synthetic FK column.
        // The runtime uses these to lower has/exists predicates.
        val edgeRefs = schema.edges()
            .filter { it.through == null }
            .mapNotNull { edge -> buildEdgeRef(edge, schemaNames) }

        val entityClass = ClassName(packageName, className)
        val tableName = tableNameFor(schemaName)
        val tableProperty = PropertySpec.builder("TABLE", STRING)
            .initializer("%S", tableName)
            .build()
        val schemaProperty = PropertySpec.builder("SCHEMA", ENTITY_SCHEMA)
            .initializer(entitySchemaCodeBlock(schemaName, schema, schemaNames))
            .build()
        val fromRowFn = buildFromRowFunction(entityClass, schema, schemaNames)

        val typeSpec = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(buildConstructor(idField, allFields, edgeFks))
            .addProperty(idField)
            .addProperties(allFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeProperty(it) })
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

        return FileSpec.builder(packageName, className)
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
        val allFields = schema.fields() + schema.mixins().flatMap { it.fields() }
        val edgeFks = computeEdgeFks(schema, schemaNames)
        val idType = schema.id().type.toTypeName()

        val body = CodeBlock.builder()
            .add("return %T(\n", entityClass)
            .add("  id = row[%S] as %T,\n", "id", idType)

        for (field in allFields) {
            val prop = toCamelCase(field.name)
            val base = field.type.toTypeName()
            val nullable = field.optional || field.nillable
            val target = base.copy(nullable = nullable)
            if (nullable) {
                body.add("  %L = row[%S] as %T,\n", prop, field.name, target)
            } else {
                body.add("  %L = row[%S] as %T,\n", prop, field.name, target)
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
    ): FunSpec {
        val builder = FunSpec.constructorBuilder()
            .addParameter(
                ParameterSpec.builder(idProperty.name, idProperty.type).build()
            )

        for (field in fields) {
            val typeName = field.type.toTypeName().let {
                if (field.optional || field.nillable) it.copy(nullable = true) else it
            }
            val param = ParameterSpec.builder(toCamelCase(field.name), typeName)
            if (field.optional || field.nillable) {
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

        return builder.build()
    }

    private fun buildIdProperty(schema: EntSchema): PropertySpec {
        val idType = schema.id().type.toTypeName()
        return PropertySpec.builder("id", idType)
            .initializer("id")
            .build()
    }

    private fun buildProperty(field: Field): PropertySpec {
        val typeName = field.type.toTypeName().let {
            if (field.optional || field.nillable) it.copy(nullable = true) else it
        }
        val propertyName = toCamelCase(field.name)
        return PropertySpec.builder(propertyName, typeName)
            .initializer(propertyName)
            .build()
    }

    private fun buildEdgeProperty(fk: EdgeFk): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
        return PropertySpec.builder(fk.propertyName, typeName)
            .initializer(fk.propertyName)
            .build()
    }

    private fun buildFieldColumnRef(field: Field): PropertySpec {
        val propertyName = toCamelCase(field.name)
        val nullable = field.optional || field.nillable
        val columnType = columnClassFor(field.type, nullable)
        return PropertySpec.builder(propertyName, columnType)
            .initializer("%T(%S)", columnType, field.name)
            .build()
    }

    private fun buildEdgeColumnRef(fk: EdgeFk): PropertySpec {
        val nullable = !fk.required
        val columnType = columnClassFor(fk.idType, nullable)
        return PropertySpec.builder(fk.propertyName, columnType)
            .initializer("%T(%S)", columnType, fk.columnName)
            .build()
    }

    private fun buildEdgeRef(
        edge: Edge,
        schemaNames: Map<EntSchema, String>,
    ): PropertySpec? {
        val targetName = schemaNames[edge.target] ?: return null
        val targetEntity = ClassName(packageName, targetName)
        val targetQuery = ClassName(packageName, "${targetName}Query")
        val edgeRefType = EDGE_REF.parameterizedBy(targetEntity, targetQuery)
        val propertyName = toCamelCase(edge.name)
        // EdgeRef.has { block } only accumulates predicates off the
        // query — it never calls the driver — so we hand it NoopDriver
        // and bail loudly if something tries to run a terminal op
        // inside `has { }`.
        return PropertySpec.builder(propertyName, edgeRefType)
            .initializer("%T(%S) { %T(%T) }", EDGE_REF, edge.name, targetQuery, NOOP_DRIVER)
            .build()
    }
}

internal fun columnClassFor(type: FieldType, nullable: Boolean): TypeName {
    return when (type) {
        FieldType.STRING, FieldType.TEXT -> {
            if (nullable) ClassName("entkt.query", "NullableStringColumn")
            else ClassName("entkt.query", "StringColumn")
        }
        FieldType.INT,
        FieldType.LONG,
        FieldType.FLOAT,
        FieldType.DOUBLE,
        FieldType.TIME -> {
            val cls = if (nullable) ClassName("entkt.query", "NullableComparableColumn")
            else ClassName("entkt.query", "ComparableColumn")
            cls.parameterizedBy(type.toTypeName())
        }
        FieldType.BOOL,
        FieldType.UUID,
        FieldType.BYTES,
        FieldType.ENUM -> {
            val cls = if (nullable) ClassName("entkt.query", "NullableColumn")
            else ClassName("entkt.query", "Column")
            cls.parameterizedBy(type.toTypeName())
        }
    }
}

internal fun toCamelCase(snakeCase: String): String {
    return snakeCase.split("_").mapIndexed { index, part ->
        if (index == 0) part.lowercase()
        else part.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}