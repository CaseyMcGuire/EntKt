package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema
import entkt.schema.Field

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

        val typeSpec = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(buildConstructor(idField, allFields, edgeFks))
            .addProperty(idField)
            .addProperties(allFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeProperty(it) })
            .addFunction(
                FunSpec.builder("update")
                    .returns(updateClass)
                    .addStatement("return %T(this)", updateClass)
                    .build()
            )
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(
                        FunSpec.builder("create")
                            .returns(createClass)
                            .addStatement("return %T()", createClass)
                            .build()
                    )
                    .addFunction(
                        FunSpec.builder("query")
                            .returns(queryClass)
                            .addStatement("return %T()", queryClass)
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
}

internal fun toCamelCase(snakeCase: String): String {
    return snakeCase.split("_").mapIndexed { index, part ->
        if (index == 0) part.lowercase()
        else part.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}