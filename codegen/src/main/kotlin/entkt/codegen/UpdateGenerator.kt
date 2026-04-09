package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema
import entkt.schema.Field

class UpdateGenerator(
    private val packageName: String,
) {

    fun generate(schemaName: String, schema: EntSchema): FileSpec {
        val className = "${schemaName}Update"
        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val allFields = (fields + mixinFields).filter { !it.immutable }

        val entityClass = ClassName(packageName, schemaName)

        val typeSpec = TypeSpec.classBuilder(className)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("entity", entityClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("entity", entityClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("entity")
                    .build()
            )
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
        mutableFields: List<Field>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val builder = FunSpec.builder("save")
            .returns(entityClass)

        // For each mutable field, use the new value if set, otherwise keep the entity's value
        val allFields = schema.fields() + schema.mixins().flatMap { it.fields() }
        val constructorArgs = mutableListOf("id = entity.id")

        for (field in allFields) {
            val propertyName = toCamelCase(field.name)
            if (field.immutable) {
                constructorArgs.add("$propertyName = entity.$propertyName")
            } else {
                constructorArgs.add("$propertyName = this.$propertyName ?: entity.$propertyName")
            }
        }

        builder.addStatement(
            "return %T(\n${constructorArgs.joinToString(",\n") { "  $it" }}\n)",
            entityClass,
        )

        return builder.build()
    }
}