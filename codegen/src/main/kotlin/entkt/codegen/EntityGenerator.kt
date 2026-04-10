package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType

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

        val createClass = ClassName(packageName, "${schemaName}Create")
        val updateClass = ClassName(packageName, "${schemaName}Update")
        val queryClass = ClassName(packageName, "${schemaName}Query")

        val createLambda = LambdaTypeName.get(
            receiver = createClass,
            returnType = UNIT,
        )
        val updateLambda = LambdaTypeName.get(
            receiver = updateClass,
            returnType = UNIT,
        )
        val queryLambda = LambdaTypeName.get(
            receiver = queryClass,
            returnType = UNIT,
        )

        val columnRefs = buildList {
            addAll(allFields.map { buildFieldColumnRef(it) })
            addAll(edgeFks.map { buildEdgeColumnRef(it) })
        }

        val typeSpec = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(buildConstructor(idField, allFields, edgeFks))
            .addProperty(idField)
            .addProperties(allFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeProperty(it) })
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("block", updateLambda)
                    .returns(updateClass)
                    .addStatement("return %T(this).apply(block)", updateClass)
                    .build()
            )
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addProperties(columnRefs)
                    .addFunction(
                        FunSpec.builder("create")
                            .addParameter("block", createLambda)
                            .returns(createClass)
                            .addStatement("return %T().apply(block)", createClass)
                            .build()
                    )
                    .addFunction(
                        FunSpec.builder("query")
                            .addParameter(
                                ParameterSpec.builder("block", queryLambda)
                                    .defaultValue("{}")
                                    .build()
                            )
                            .returns(queryClass)
                            .addStatement("return %T().apply(block)", queryClass)
                            .build()
                    )
                    .build()
            )
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
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