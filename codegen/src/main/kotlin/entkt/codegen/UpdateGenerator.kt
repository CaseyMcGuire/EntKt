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

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Update"
        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val mutableFields = (fields + mixinFields).filter { !it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)

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
            .addProperties(mutableFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeProperty(it) })
            .addFunctions(mutableFields.map { buildSetter(className, it) })
            .addFunctions(edgeFks.flatMap { buildEdgeSetters(className, it) })
            .addFunction(buildSaveFunction(schemaName, schema, edgeFks))
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

    private fun buildEdgeProperty(fk: EdgeFk): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        return PropertySpec.builder(fk.propertyName, typeName)
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

    private fun buildEdgeSetters(className: String, fk: EdgeFk): List<FunSpec> {
        val cap = fk.propertyName.replaceFirstChar { it.uppercase() }
        val edgeCap = toCamelCase(fk.edgeName).replaceFirstChar { it.uppercase() }
        val returnType = ClassName(packageName, className)
        val targetClass = ClassName(packageName, fk.targetName)

        val idSetter = FunSpec.builder("set$cap")
            .addParameter("value", fk.idType.toTypeName())
            .returns(returnType)
            .addStatement("this.%L = value", fk.propertyName)
            .addStatement("return this")
            .build()

        val entitySetter = FunSpec.builder("set$edgeCap")
            .addParameter(toCamelCase(fk.edgeName), targetClass)
            .returns(returnType)
            .addStatement("this.%L = %L.id", fk.propertyName, toCamelCase(fk.edgeName))
            .addStatement("return this")
            .build()

        return listOf(idSetter, entitySetter)
    }

    private fun buildSaveFunction(
        schemaName: String,
        schema: EntSchema,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val builder = FunSpec.builder("save")
            .returns(entityClass)

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

        for (fk in edgeFks) {
            constructorArgs.add("${fk.propertyName} = this.${fk.propertyName} ?: entity.${fk.propertyName}")
        }

        builder.addStatement(
            "return %T(\n${constructorArgs.joinToString(",\n") { "  $it" }}\n)",
            entityClass,
        )

        return builder.build()
    }
}