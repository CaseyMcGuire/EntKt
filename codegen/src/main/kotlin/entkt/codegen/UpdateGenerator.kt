package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema
import entkt.schema.Field

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")

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
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
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
            .addProperties(edgeFks.map { buildEdgeFkProperty(it) })
            .addProperties(edgeFks.map { buildEdgeEntityProperty(it) })
            .addFunction(buildSaveFunction(schemaName, schema, edgeFks))
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
     * Assigning a target entity here also writes its id into the
     * underlying FK property. e.g. `author = alice` sets
     * `authorId = alice.id`.
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