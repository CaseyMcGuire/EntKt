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

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Create"
        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val allFields = fields + mixinFields
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val typeSpec = TypeSpec.classBuilder(className)
            .addProperties(allFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeProperty(it) })
            .addFunctions(allFields.map { buildSetter(className, it) })
            .addFunctions(edgeFks.flatMap { buildEdgeSetters(className, it) })
            .addFunction(buildSaveFunction(schemaName, schema, allFields, edgeFks))
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

        // setOwnerId(id: Long): CarCreate
        val idSetter = FunSpec.builder("set$cap")
            .addParameter("value", fk.idType.toTypeName())
            .returns(returnType)
            .addStatement("this.%L = value", fk.propertyName)
            .addStatement("return this")
            .build()

        // setOwner(owner: User): CarCreate
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
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
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

        // Validate required edge FKs
        for (fk in edgeFks) {
            if (fk.required) {
                builder.addStatement(
                    "val %L = this.%L ?: throw IllegalStateException(%S)",
                    fk.propertyName,
                    fk.propertyName,
                    "${fk.edgeName} is required",
                )
            }
        }

        // Build return statement
        val constructorArgs = mutableListOf<String>()
        constructorArgs.add("id = TODO(\"ID generation\")")

        for (field in allFields) {
            val propertyName = toCamelCase(field.name)
            val isRequired = !field.optional && !field.nillable && field.default == null
            val hasDefault = !field.optional && !field.nillable && field.default != null
            when {
                isRequired -> constructorArgs.add("$propertyName = $propertyName")
                hasDefault -> constructorArgs.add("$propertyName = this.$propertyName ?: ${kotlinLiteral(field.default!!)}")
                else -> constructorArgs.add("$propertyName = this.$propertyName")
            }
        }

        for (fk in edgeFks) {
            if (fk.required) {
                constructorArgs.add("${fk.propertyName} = ${fk.propertyName}")
            } else {
                constructorArgs.add("${fk.propertyName} = this.${fk.propertyName}")
            }
        }

        builder.addStatement(
            "return %T(\n${constructorArgs.joinToString(",\n") { "  $it" }}\n)",
            entityClass,
        )

        return builder.build()
    }

    private fun kotlinLiteral(value: Any): String = when (value) {
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> value.toString()
    }
}