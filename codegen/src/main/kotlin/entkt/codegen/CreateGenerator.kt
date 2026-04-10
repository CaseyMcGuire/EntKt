package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema
import entkt.schema.Field

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")

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
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addProperties(allFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeFkProperty(it) })
            .addProperties(edgeFks.map { buildEdgeEntityProperty(it) })
            .addFunction(buildSaveFunction(schemaName, schema, allFields, edgeFks))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildProperty(field: Field): PropertySpec {
        val typeName = field.type.toTypeName().copy(nullable = true)
        return PropertySpec.builder(toCamelCase(field.name), typeName)
            .mutable(true)
            .initializer("null")
            .build()
    }

    private fun buildEdgeFkProperty(fk: EdgeFk): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        return PropertySpec.builder(fk.propertyName, typeName)
            .mutable(true)
            .initializer("null")
            .build()
    }

    /**
     * Convenience property mirroring the edge name: assigning a target
     * entity here also writes its id into the underlying FK property.
     * e.g. `author = alice` sets `authorId = alice.id`.
     */
    private fun buildEdgeEntityProperty(fk: EdgeFk): PropertySpec {
        val targetClass = ClassName(packageName, fk.targetName).copy(nullable = true)
        val edgeProp = toCamelCase(fk.edgeName)
        return PropertySpec.builder(edgeProp, targetClass)
            .mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", targetClass)
                    .addStatement("field = value")
                    .addStatement("%L = value?.id", fk.propertyName)
                    .build()
            )
            .build()
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
