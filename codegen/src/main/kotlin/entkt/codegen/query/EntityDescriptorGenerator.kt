package entkt.codegen.query

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.schema.EntSchema

/** Generates one canonical entity descriptor and one file per typed edge descriptor. */
internal class EntityDescriptorGenerator(
    private val packageName: String,
) {
    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): List<FileSpec> {
        val resolved = resolveQuerySchema(packageName, schemaName, schema, schemaNames)
        return buildList {
            add(descriptorFile(resolved.entityDescriptorClass, buildEntityDescriptor(resolved)))
            buildEdgeDescriptors(resolved).forEach { edgeDescriptor ->
                val name = checkNotNull(edgeDescriptor.name)
                add(
                    descriptorFile(
                        ClassName(packageName, name),
                        edgeDescriptor,
                    ),
                )
            }
        }
    }

    private fun descriptorFile(
        descriptorClass: ClassName,
        descriptor: TypeSpec,
    ): FileSpec = kotlinFile(packageName, descriptorClass.simpleName) {
        addAnnotation(
            annotation(ClassName("kotlin", "OptIn")) {
                useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
            },
        )
        addType(descriptor)
    }
}
