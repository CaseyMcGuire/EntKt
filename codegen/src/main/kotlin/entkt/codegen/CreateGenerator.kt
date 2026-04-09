package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema
import entkt.schema.Field

class CreateGenerator(
    private val packageName: String,
) {

    fun generate(schemaName: String, schema: EntSchema): FileSpec {
        val className = "${schemaName}Create"
        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val allFields = fields + mixinFields

        val typeSpec = TypeSpec.classBuilder(className)
            .addProperties(allFields.map { buildProperty(it) })
            .addFunctions(allFields.map { buildSetter(className, it) })
            .addFunction(buildSaveFunction(schemaName, schema, allFields))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildProperty(field: Field): PropertySpec {
        val typeName = field.type.toTypeName().copy(nullable = true)
        return PropertySpec.builder(toCamelCase(field.name), typeName)
            .addModifiers(KModifier.PRIVATE)
            .mutable(true)
            .initializer("null")
            .build()
    }

    private fun buildSetter(className: String, field: Field): FunSpec {
        val propertyName = toCamelCase(field.name)
        val paramType = field.type.toTypeName()
        return FunSpec.builder("set${propertyName.replaceFirstChar { it.uppercase() }}")
            .addParameter("value", paramType)
            .returns(ClassName(packageName, className))
            .addStatement("this.%L = value", propertyName)
            .addStatement("return this")
            .build()
    }

    private fun buildSaveFunction(
        schemaName: String,
        schema: EntSchema,
        allFields: List<Field>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val builder = FunSpec.builder("save")
            .returns(entityClass)

        // Validate required fields
        for (field in allFields) {
            if (!field.optional && !field.nillable && field.default == null) {
                val propertyName = toCamelCase(field.name)
                builder.addStatement(
                    "val %L = this.%L ?: throw IllegalStateException(%S)",
                    propertyName,
                    propertyName,
                    "${field.name} is required",
                )
            }
        }

        // Build return statement
        val constructorArgs = mutableListOf<String>()
        val idType = schema.id().type.toTypeName()
        constructorArgs.add("id = TODO(\"ID generation\")")

        for (field in allFields) {
            val propertyName = toCamelCase(field.name)
            if (!field.optional && !field.nillable && field.default == null) {
                constructorArgs.add("$propertyName = $propertyName")
            } else {
                constructorArgs.add("$propertyName = this.$propertyName")
            }
        }

        builder.addStatement(
            "return %T(\n${constructorArgs.joinToString(",\n") { "  $it" }}\n)",
            entityClass,
        )

        return builder.build()
    }
}