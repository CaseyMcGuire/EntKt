package entkt.codegen.mutation

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import entkt.codegen.apiName
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.objectType
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.scalarFields
import entkt.schema.EntSchema

private val DELETE_MUTATION_CONVERTER =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationConverter")

/** Map loaded entities to DELETE rule candidates without repository callbacks. */
internal class DeleteConverterGenerator(private val packageName: String) {
    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}DeleteConverter"
        val entityClass = ClassName(packageName, schemaName)
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val typeSpec = objectType(className) {
            addModifiers(KModifier.INTERNAL)
            addAnnotation(ENTKT_INTERNAL)
            addSuperinterface(DELETE_MUTATION_CONVERTER.parameterizedBy(entityClass, candidateClass))
            function("toCandidate", candidateClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("entity", entityClass)
                addCode(codeBlock {
                    add("return %T(\n", candidateClass)
                    indent()
                    for (field in scalarFields(schema)) {
                        add("%L = entity.%L,\n", field.apiName, field.apiName)
                    }
                    for (fk in computeEdgeFks(schema, schemaNames)) {
                        add("%L = entity.%L,\n", fk.propertyName, fk.propertyName)
                    }
                    unindent()
                    add(")\n")
                })
            }
        }
        return kotlinFile(packageName, className) {
            addAnnotation(entktInternalFileOptIn())
            addType(typeSpec)
        }
    }
}
