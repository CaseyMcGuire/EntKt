package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName

private val DRIVER = ClassName("entkt.runtime", "Driver")
private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")

/**
 * Emits the top-level `EntClient` that wires every per-schema repo
 * together, plus the hooks DSL classes (`EntClientConfig`,
 * `EntClientHooks`, and per-entity `{Entity}Hooks`).
 *
 * The client takes a [Driver] and an optional configuration lambda:
 *
 * ```kotlin
 * val client = EntClient(driver) {
 *     hooks {
 *         users {
 *             beforeSave { it.updatedAt = Instant.now() }
 *         }
 *     }
 * }
 * ```
 *
 * Hooks are registered once at construction time and automatically
 * inherited by transactional clients via `copyHooksFrom`.
 */
class ClientGenerator(
    private val packageName: String,
) {

    fun generate(schemas: List<SchemaInput>): FileSpec {
        val clientClass = ClassName(packageName, "EntClient")
        val configClass = ClassName(packageName, "EntClientConfig")
        val hooksClass = ClassName(packageName, "EntClientHooks")
        val t = TypeVariableName("T")

        val fileBuilder = FileSpec.builder(packageName, "EntClient")

        // Generate per-entity hooks DSL classes
        for (input in schemas) {
            fileBuilder.addType(buildEntityHooksClass(input))
        }

        // Generate EntClientHooks
        fileBuilder.addType(buildHooksClass(hooksClass, schemas))

        // Generate EntClientConfig
        fileBuilder.addType(buildConfigClass(configClass, hooksClass))

        // Generate EntClient
        val configLambda = LambdaTypeName.get(
            receiver = configClass,
            returnType = UNIT,
        )

        val typeSpec = TypeSpec.classBuilder("EntClient")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter(
                        ParameterSpec.builder("config", configLambda)
                            .defaultValue("{}")
                            .build(),
                    )
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperties(schemas.map { buildRepoProperty(it) })
            .addInitializerBlock(buildInitBlock(configClass, schemas))
            .addFunction(buildWithTransaction(clientClass, configClass, t, schemas))
            .addType(buildCompanionObject(schemas))
            .build()

        fileBuilder.addType(typeSpec)

        return fileBuilder.build()
    }

    private fun buildEntityHooksClass(input: SchemaInput): TypeSpec {
        val schemaName = input.name
        val className = "${schemaName}Hooks"
        val entityClass = ClassName(packageName, schemaName)
        val createClass = ClassName(packageName, "${schemaName}Create")
        val updateClass = ClassName(packageName, "${schemaName}Update")
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")

        val hookDefs = listOf(
            HookDef("beforeSave", mutationClass),
            HookDef("beforeCreate", createClass),
            HookDef("afterCreate", entityClass),
            HookDef("beforeUpdate", updateClass),
            HookDef("afterUpdate", entityClass),
            HookDef("beforeDelete", entityClass),
            HookDef("afterDelete", entityClass),
        )

        val builder = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())

        for (def in hookDefs) {
            val lambdaType = LambdaTypeName.get(parameters = arrayOf(def.paramType), returnType = UNIT)
            val listType = MUTABLE_LIST.parameterizedBy(lambdaType)

            // Internal property: the hook list
            builder.addProperty(
                PropertySpec.builder("${def.name}Hooks", listType)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("mutableListOf()")
                    .build()
            )

            // Public DSL method: beforeSave { ... }
            builder.addFunction(
                FunSpec.builder(def.name)
                    .addParameter("hook", lambdaType)
                    .addStatement("%LHooks.add(hook)", def.name)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildHooksClass(
        hooksClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(hooksClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())

        for (input in schemas) {
            val entityHooksClass = ClassName(packageName, "${input.name}Hooks")
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })

            // Internal property holding the entity hooks
            builder.addProperty(
                PropertySpec.builder(propName, entityHooksClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", entityHooksClass)
                    .build()
            )

            // DSL method: users { ... }
            val blockLambda = LambdaTypeName.get(
                receiver = entityHooksClass,
                returnType = UNIT,
            )
            builder.addFunction(
                FunSpec.builder(propName)
                    .addParameter("block", blockLambda)
                    .addStatement("%L.apply(block)", propName)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildConfigClass(
        configClass: ClassName,
        hooksClass: ClassName,
    ): TypeSpec {
        val blockLambda = LambdaTypeName.get(
            receiver = hooksClass,
            returnType = UNIT,
        )

        return TypeSpec.classBuilder(configClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addProperty(
                PropertySpec.builder("hooksConfig", hooksClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", hooksClass)
                    .build()
            )
            .addFunction(
                FunSpec.builder("hooks")
                    .addParameter("block", blockLambda)
                    .addStatement("hooksConfig.apply(block)")
                    .build()
            )
            .build()
    }

    private fun buildInitBlock(
        configClass: ClassName,
        schemas: List<SchemaInput>,
    ): CodeBlock {
        val block = CodeBlock.builder()
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            block.addStatement("%L.client = this", propName)
        }
        block.addStatement("val cfg = %T().apply(config)", configClass)
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            block.addStatement("%L.applyHooks(cfg.hooksConfig.%L)", propName, propName)
        }
        return block.build()
    }

    private fun buildWithTransaction(
        clientClass: ClassName,
        configClass: ClassName,
        t: TypeVariableName,
        schemas: List<SchemaInput>,
    ): FunSpec {
        val body = CodeBlock.builder()
        body.beginControlFlow("return driver.withTransaction { txDriver ->")
        body.addStatement("val tx = %T(txDriver)", clientClass)
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            body.addStatement("tx.%L.copyHooksFrom(this.%L)", propName, propName)
        }
        body.addStatement("block(tx)")
        body.endControlFlow()

        return FunSpec.builder("withTransaction")
            .addTypeVariable(t)
            .addParameter(
                "block",
                LambdaTypeName.get(
                    parameters = listOf(
                        ParameterSpec.unnamed(clientClass),
                    ),
                    returnType = t,
                ),
            )
            .returns(t)
            .addCode(body.build())
            .build()
    }

    private fun buildCompanionObject(schemas: List<SchemaInput>): TypeSpec {
        val listType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ENTITY_SCHEMA)
        val code = CodeBlock.builder()
            .add("listOf(\n")
        for ((i, input) in schemas.withIndex()) {
            val entityClass = ClassName(packageName, input.name)
            val suffix = if (i < schemas.size - 1) "," else ""
            code.add("  %T.SCHEMA$suffix\n", entityClass)
        }
        code.add(")")
        return TypeSpec.companionObjectBuilder()
            .addProperty(
                PropertySpec.builder("SCHEMAS", listType)
                    .initializer(code.build())
                    .build()
            )
            .build()
    }

    private fun buildRepoProperty(input: SchemaInput): PropertySpec {
        val repoClass = ClassName(packageName, "${input.name}Repo")
        val propertyName = pluralize(input.name.replaceFirstChar { it.lowercase() })
        return PropertySpec.builder(propertyName, repoClass)
            .initializer("%T(driver)", repoClass)
            .build()
    }
}

private data class HookDef(val name: String, val paramType: ClassName)

/**
 * Naive English pluralization good enough for the small surface area of
 * generated repo property names. Handles the cases the example schemas
 * exercise (`user` → `users`, `post` → `posts`, `tag` → `tags`,
 * `category` → `categories`) and is conservative everywhere else: if
 * the rule is unclear, it just appends `s`.
 */
internal fun pluralize(word: String): String {
    if (word.isEmpty()) return word
    return when {
        word.endsWith("y") && word.length > 1 && word[word.length - 2] !in "aeiou" ->
            word.dropLast(1) + "ies"
        word.endsWith("s") || word.endsWith("x") || word.endsWith("z") ||
            word.endsWith("ch") || word.endsWith("sh") -> word + "es"
        else -> word + "s"
    }
}
