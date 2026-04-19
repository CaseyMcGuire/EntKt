package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime", "Driver")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val ENT_CLIENT_NAME = "EntClient"

/**
 * Emits a per-schema repository class. The repo is the only entry point
 * for I/O — it owns the [Driver] and exposes `query`, `create`,
 * `update(entity)`, and `byId` accessors. Its `init` block registers the
 * entity's [entkt.runtime.EntitySchema] so the driver knows the table
 * layout before any other call lands, and every builder it hands back is
 * constructed with the same driver reference.
 *
 * Hooks are applied from the client's hooks DSL via [applyHooks] at
 * construction time, and inherited by transactional repos via
 * [copyHooksFrom].
 */
class RepoGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
    ): FileSpec {
        val className = "${schemaName}Repo"
        val repoClass = ClassName(packageName, className)
        val entityClass = ClassName(packageName, schemaName)
        val createClass = ClassName(packageName, "${schemaName}Create")
        val updateClass = ClassName(packageName, "${schemaName}Update")
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val entityHooksClass = ClassName(packageName, "${schemaName}Hooks")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
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

        // Hook list types
        val beforeSaveHookLambda = LambdaTypeName.get(parameters = arrayOf(mutationClass), returnType = UNIT)
        val beforeCreateHookLambda = LambdaTypeName.get(parameters = arrayOf(createClass), returnType = UNIT)
        val afterCreateHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)
        val beforeUpdateHookLambda = LambdaTypeName.get(parameters = arrayOf(updateClass), returnType = UNIT)
        val afterUpdateHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)
        val beforeDeleteHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)
        val afterDeleteHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)

        fun mutableHookList(lambdaType: LambdaTypeName) =
            MUTABLE_LIST.parameterizedBy(lambdaType)

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
            // Client reference — set by EntClient after construction.
            .addProperty(
                PropertySpec.builder("client", clientClass)
                    .addModifiers(KModifier.INTERNAL, KModifier.LATEINIT)
                    .mutable(true)
                    .build()
            )
            // Hook list properties
            .addProperty(hookListProperty("beforeSaveHooks", mutableHookList(beforeSaveHookLambda)))
            .addProperty(hookListProperty("beforeCreateHooks", mutableHookList(beforeCreateHookLambda)))
            .addProperty(hookListProperty("afterCreateHooks", mutableHookList(afterCreateHookLambda)))
            .addProperty(hookListProperty("beforeUpdateHooks", mutableHookList(beforeUpdateHookLambda)))
            .addProperty(hookListProperty("afterUpdateHooks", mutableHookList(afterUpdateHookLambda)))
            .addProperty(hookListProperty("beforeDeleteHooks", mutableHookList(beforeDeleteHookLambda)))
            .addProperty(hookListProperty("afterDeleteHooks", mutableHookList(afterDeleteHookLambda)))
            .addInitializerBlock(
                CodeBlock.of("driver.register(%T.SCHEMA)\n", entityClass),
            )
            .addFunction(
                FunSpec.builder("query")
                    .addParameter(
                        ParameterSpec.builder("block", queryLambda)
                            .defaultValue("{}")
                            .build()
                    )
                    .returns(queryClass)
                    .addStatement("return %T(driver).apply(block)", queryClass)
                    .build()
            )
            .addFunction(
                FunSpec.builder("create")
                    .addParameter("block", createLambda)
                    .returns(createClass)
                    .addStatement(
                        "return %T(driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks).apply(block)",
                        createClass,
                    )
                    .build()
            )
            .addFunction(buildRepoUpsert(entityClass, createClass, createLambda))
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("entity", entityClass)
                    .addParameter("block", updateLambda)
                    .returns(updateClass)
                    .addStatement(
                        "return %T(driver, client, entity, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks).apply(block)",
                        updateClass,
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("byId")
                    .addParameter("id", idType)
                    .returns(entityClass.copy(nullable = true))
                    .addStatement(
                        "return driver.byId(%T.TABLE, id)?.let { %T.fromRow(it) }",
                        entityClass,
                        entityClass,
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("delete")
                    .addParameter("entity", entityClass)
                    .returns(Boolean::class)
                    .addStatement("for (hook in beforeDeleteHooks) hook(entity)")
                    .addStatement(
                        "val deleted = driver.delete(%T.TABLE, entity.id)",
                        entityClass,
                    )
                    .addStatement("if (deleted) for (hook in afterDeleteHooks) hook(entity)")
                    .addStatement("return deleted")
                    .build()
            )
            .addFunction(
                FunSpec.builder("deleteById")
                    .addParameter("id", idType)
                    .returns(Boolean::class)
                    .addStatement("val entity = byId(id) ?: return false")
                    .addStatement("return delete(entity)")
                    .build()
            )
            .addFunction(buildApplyHooks(entityHooksClass))
            .addFunction(buildCopyHooksFrom(repoClass))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildRepoUpsert(
        entityClass: ClassName,
        createClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        val columnClass = ClassName("entkt.query", "Column")
        return FunSpec.builder("upsert")
            .addParameter(
                ParameterSpec.builder(
                    "onConflict",
                    columnClass.parameterizedBy(STAR),
                ).addModifiers(KModifier.VARARG).build(),
            )
            .addParameter("block", createLambda)
            .returns(entityClass)
            .addStatement(
                "return %T(driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks, afterUpdateHooks).apply(block).upsert(*onConflict)",
                createClass,
            )
            .build()
    }

    private fun buildApplyHooks(entityHooksClass: ClassName): FunSpec =
        FunSpec.builder("applyHooks")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("hooks", entityHooksClass)
            .addStatement("beforeSaveHooks.addAll(hooks.beforeSaveHooks)")
            .addStatement("beforeCreateHooks.addAll(hooks.beforeCreateHooks)")
            .addStatement("afterCreateHooks.addAll(hooks.afterCreateHooks)")
            .addStatement("beforeUpdateHooks.addAll(hooks.beforeUpdateHooks)")
            .addStatement("afterUpdateHooks.addAll(hooks.afterUpdateHooks)")
            .addStatement("beforeDeleteHooks.addAll(hooks.beforeDeleteHooks)")
            .addStatement("afterDeleteHooks.addAll(hooks.afterDeleteHooks)")
            .build()

    private fun buildCopyHooksFrom(repoClass: ClassName): FunSpec =
        FunSpec.builder("copyHooksFrom")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("other", repoClass)
            .addStatement("beforeSaveHooks.addAll(other.beforeSaveHooks)")
            .addStatement("beforeCreateHooks.addAll(other.beforeCreateHooks)")
            .addStatement("afterCreateHooks.addAll(other.afterCreateHooks)")
            .addStatement("beforeUpdateHooks.addAll(other.beforeUpdateHooks)")
            .addStatement("afterUpdateHooks.addAll(other.afterUpdateHooks)")
            .addStatement("beforeDeleteHooks.addAll(other.beforeDeleteHooks)")
            .addStatement("afterDeleteHooks.addAll(other.afterDeleteHooks)")
            .build()

    private fun hookListProperty(name: String, type: com.squareup.kotlinpoet.TypeName): PropertySpec =
        PropertySpec.builder(name, type)
            .addModifiers(KModifier.PRIVATE)
            .initializer("mutableListOf()")
            .build()
}
