package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime", "Driver")

/**
 * Emits a per-schema repository class. The repo is the only entry point
 * for I/O — it owns the [Driver] and exposes `query`, `create`,
 * `update(entity)`, and `byId` accessors. The entity's companion is
 * left as pure metadata (column refs only).
 */
class RepoGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
    ): FileSpec {
        val className = "${schemaName}Repo"
        val entityClass = ClassName(packageName, schemaName)
        val createClass = ClassName(packageName, "${schemaName}Create")
        val updateClass = ClassName(packageName, "${schemaName}Update")
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val idType = schema.id().type.toTypeName()

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

        val typeSpec = TypeSpec.classBuilder(className)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
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
            .addFunction(
                FunSpec.builder("create")
                    .addParameter("block", createLambda)
                    .returns(createClass)
                    .addStatement("return %T().apply(block)", createClass)
                    .build()
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("entity", entityClass)
                    .addParameter("block", updateLambda)
                    .returns(updateClass)
                    .addStatement("return %T(entity).apply(block)", updateClass)
                    .build()
            )
            .addFunction(
                FunSpec.builder("byId")
                    .addParameter("id", idType)
                    .returns(entityClass.copy(nullable = true))
                    .addStatement("TODO(%S)", "byId requires the runtime layer")
                    .build()
            )
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }
}