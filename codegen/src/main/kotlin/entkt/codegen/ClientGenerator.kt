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
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val VIEWER = ClassName("entkt.runtime", "Viewer")
private val ENTITY_POLICY = ClassName("entkt.runtime", "EntityPolicy")

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
        // Sort schemas so that FK dependencies are registered before
        // dependents — e.g. User before Friendship (which references User).
        val sorted = topologicalSort(schemas)

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

        // Generate EntClientPolicies
        val policiesClass = ClassName(packageName, "EntClientPolicies")
        fileBuilder.addType(buildPoliciesClass(policiesClass, schemas))

        // Generate EntClientConfig
        fileBuilder.addType(buildConfigClass(configClass, hooksClass, policiesClass))

        // Generate EntClient
        val configLambda = LambdaTypeName.get(
            receiver = configClass,
            returnType = UNIT,
        )

        val privacyProviderType = LambdaTypeName.get(returnType = PRIVACY_CONTEXT)

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
            .addProperty(
                PropertySpec.builder("privacyContextProvider", privacyProviderType)
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("{ %T(%T.Anonymous) }", PRIVACY_CONTEXT, VIEWER)
                    .build()
            )
            .addProperties(sorted.map { buildRepoProperty(it) })
            .addInitializerBlock(buildInitBlock(configClass, sorted))
            .addFunction(
                FunSpec.builder("currentPrivacyContext")
                    .addModifiers(KModifier.INTERNAL)
                    .returns(PRIVACY_CONTEXT)
                    .addStatement("return privacyContextProvider()")
                    .build()
            )
            .addFunction(buildWithPrivacyContext(clientClass, t, sorted))
            .addFunction(
                FunSpec.builder("withFixedPrivacyContextForInternalUse")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("context", PRIVACY_CONTEXT)
                    .returns(clientClass)
                    .addCode(buildFixedContextBody(clientClass, sorted))
                    .build()
            )
            .addFunction(buildWithTransaction(clientClass, configClass, t, sorted))
            .addType(buildCompanionObject(sorted))
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
        policiesClass: ClassName,
    ): TypeSpec {
        val hooksBlockLambda = LambdaTypeName.get(
            receiver = hooksClass,
            returnType = UNIT,
        )
        val policiesBlockLambda = LambdaTypeName.get(
            receiver = policiesClass,
            returnType = UNIT,
        )
        val privacyProviderType = LambdaTypeName.get(returnType = PRIVACY_CONTEXT)

        return TypeSpec.classBuilder(configClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addProperty(
                PropertySpec.builder("hooksConfig", hooksClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", hooksClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("policiesConfig", policiesClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", policiesClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("privacyContextProviderConfig", privacyProviderType.copy(nullable = true))
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addFunction(
                FunSpec.builder("hooks")
                    .addParameter("block", hooksBlockLambda)
                    .addStatement("hooksConfig.apply(block)")
                    .build()
            )
            .addFunction(
                FunSpec.builder("policies")
                    .addParameter("block", policiesBlockLambda)
                    .addStatement("policiesConfig.apply(block)")
                    .build()
            )
            .addFunction(
                FunSpec.builder("privacyContext")
                    .addParameter("provider", privacyProviderType)
                    .addStatement("privacyContextProviderConfig = provider")
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
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            block.addStatement("%L.applyPrivacy(cfg.policiesConfig.%LPrivacyConfig)", propName, propName)
            block.addStatement("%L.applyValidation(cfg.policiesConfig.%LValidationConfig)", propName, propName)
        }
        block.addStatement("cfg.privacyContextProviderConfig?.let { privacyContextProvider = it }")
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
        body.addStatement("tx.privacyContextProvider = this.privacyContextProvider")
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            body.addStatement("tx.%L.copyHooksFrom(this.%L)", propName, propName)
            body.addStatement("tx.%L.copyPrivacyFrom(this.%L)", propName, propName)
            body.addStatement("tx.%L.copyValidationFrom(this.%L)", propName, propName)
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

    private fun buildPoliciesClass(
        policiesClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(policiesClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())

        for (input in schemas) {
            val entityClass = ClassName(packageName, input.name)
            val policyScopeClass = ClassName(packageName, "${input.name}PolicyScope")
            val privacyConfigClass = ClassName(packageName, "${input.name}PrivacyConfig")
            val validationConfigClass = ClassName(packageName, "${input.name}ValidationConfig")
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val policyType = ENTITY_POLICY.parameterizedBy(entityClass, policyScopeClass)

            // Internal privacy config property
            builder.addProperty(
                PropertySpec.builder("${propName}PrivacyConfig", privacyConfigClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", privacyConfigClass)
                    .build()
            )

            // Internal validation config property
            builder.addProperty(
                PropertySpec.builder("${propName}ValidationConfig", validationConfigClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", validationConfigClass)
                    .build()
            )

            // DSL method: users(policy)
            builder.addFunction(
                FunSpec.builder(propName)
                    .addParameter("policy", policyType)
                    .addStatement("policy.configure(%T(%LPrivacyConfig, %LValidationConfig))", policyScopeClass, propName, propName)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildWithPrivacyContext(
        clientClass: ClassName,
        t: TypeVariableName,
        schemas: List<SchemaInput>,
    ): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("val scoped = %T(driver)", clientClass)
        body.addStatement("scoped.privacyContextProvider = { context }")
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            body.addStatement("scoped.%L.copyHooksFrom(this.%L)", propName, propName)
            body.addStatement("scoped.%L.copyPrivacyFrom(this.%L)", propName, propName)
            body.addStatement("scoped.%L.copyValidationFrom(this.%L)", propName, propName)
        }
        body.addStatement("return block(scoped)")

        return FunSpec.builder("withPrivacyContext")
            .addTypeVariable(t)
            .addParameter("context", PRIVACY_CONTEXT)
            .addParameter(
                "block",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(clientClass)),
                    returnType = t,
                ),
            )
            .returns(t)
            .addCode(body.build())
            .build()
    }

    private fun buildFixedContextBody(
        clientClass: ClassName,
        schemas: List<SchemaInput>,
    ): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("val fixed = %T(driver)", clientClass)
        body.addStatement("fixed.privacyContextProvider = { context }")
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            body.addStatement("fixed.%L.copyHooksFrom(this.%L)", propName, propName)
            body.addStatement("fixed.%L.copyPrivacyFrom(this.%L)", propName, propName)
            body.addStatement("fixed.%L.copyValidationFrom(this.%L)", propName, propName)
        }
        body.addStatement("return fixed")
        return body.build()
    }

    private fun buildRepoProperty(input: SchemaInput): PropertySpec {
        val repoClass = ClassName(packageName, "${input.name}Repo")
        val propertyName = pluralize(input.name.replaceFirstChar { it.lowercase() })
        return PropertySpec.builder(propertyName, repoClass)
            .initializer("%T(driver)", repoClass)
            .build()
    }
}

/**
 * Topologically sort schemas so that FK dependencies come before the
 * schemas that reference them. Falls back to the original order for
 * schemas with no dependency relationship (stable sort).
 */
private fun topologicalSort(schemas: List<SchemaInput>): List<SchemaInput> {
    val bySchema = schemas.associateBy { it.schema }
    // Build adjacency: schema → set of schemas it depends on (FK targets)
    val deps = schemas.associate { input ->
        input to input.schema.edges()
            .filter { edge -> edge.unique && edge.through == null }
            .mapNotNull { edge -> bySchema[edge.target] }
            .toSet()
    }

    val result = mutableListOf<SchemaInput>()
    val visited = mutableSetOf<SchemaInput>()
    val visiting = mutableSetOf<SchemaInput>() // cycle guard

    fun visit(input: SchemaInput) {
        if (input in visited) return
        if (input in visiting) return // cycle — break it
        visiting.add(input)
        for (dep in deps[input].orEmpty()) {
            visit(dep)
        }
        visiting.remove(input)
        visited.add(input)
        result.add(input)
    }

    for (input in schemas) visit(input)
    return result
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
