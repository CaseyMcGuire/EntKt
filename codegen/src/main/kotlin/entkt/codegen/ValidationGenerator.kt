package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import entkt.schema.EntSchema

private val VALIDATION_RULE = ClassName("entkt.runtime", "ValidationRule")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")

/**
 * Emits per-entity validation infrastructure:
 *
 * - `{Entity}ValidationConfig` — internal mutable config holding rule lists
 * - `{Entity}ValidationScope` — DSL scope for declaring rules per operation
 * - `{Entity}{Op}ValidationContext` — context classes for create/update/delete
 * - `{Entity}{Op}ValidationRule` — typealiases for each operation's rule type
 *
 * Unlike privacy, validation has no LOAD operation and contexts do not
 * carry a [PrivacyContext] — validation is viewer-agnostic. The
 * [WriteCandidate] is reused from the privacy generator.
 */
class ValidationGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val entityClass = ClassName(packageName, schemaName)
        val clientClass = ClassName(packageName, "EntClient")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val configClass = ClassName(packageName, "${schemaName}ValidationConfig")
        val scopeClass = ClassName(packageName, "${schemaName}ValidationScope")

        val fileBuilder = FileSpec.builder(packageName, "${schemaName}Validation")

        // Operation context class names
        val createCtx = ClassName(packageName, "${schemaName}CreateValidationContext")
        val updateCtx = ClassName(packageName, "${schemaName}UpdateValidationContext")
        val deleteCtx = ClassName(packageName, "${schemaName}DeleteValidationContext")

        // Rule typealiases
        val createRule = "${schemaName}CreateValidationRule"
        val updateRule = "${schemaName}UpdateValidationRule"
        val deleteRule = "${schemaName}DeleteValidationRule"

        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(createRule, VALIDATION_RULE.parameterizedBy(createCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(updateRule, VALIDATION_RULE.parameterizedBy(updateCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(deleteRule, VALIDATION_RULE.parameterizedBy(deleteCtx)).build(),
        )

        // Operation context data classes
        fileBuilder.addType(buildCreateContext(entityClass, clientClass, candidateClass, createCtx))
        fileBuilder.addType(buildUpdateContext(entityClass, clientClass, candidateClass, updateCtx))
        fileBuilder.addType(buildDeleteContext(entityClass, clientClass, candidateClass, deleteCtx))

        // ValidationConfig
        fileBuilder.addType(
            buildValidationConfig(
                configClass,
                ClassName(packageName, createRule),
                ClassName(packageName, updateRule),
                ClassName(packageName, deleteRule),
            ),
        )

        // ValidationScope
        fileBuilder.addType(
            buildValidationScope(
                scopeClass,
                configClass,
                ClassName(packageName, createRule),
                ClassName(packageName, updateRule),
                ClassName(packageName, deleteRule),
            ),
        )

        return fileBuilder.build()
    }

    private fun buildCreateContext(
        entityClass: ClassName,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("client", clientClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildUpdateContext(
        entityClass: ClassName,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("client", clientClass)
                .addParameter("before", entityClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("before", entityClass).initializer("before").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildDeleteContext(
        entityClass: ClassName,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("client", clientClass)
                .addParameter("entity", entityClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("entity", entityClass).initializer("entity").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildValidationConfig(
        configClass: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
    ): TypeSpec {
        return TypeSpec.classBuilder(configClass)
            .addProperty(
                PropertySpec.builder("createRules", MUTABLE_LIST.parameterizedBy(createRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("updateRules", MUTABLE_LIST.parameterizedBy(updateRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("deleteRules", MUTABLE_LIST.parameterizedBy(deleteRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("updateDerivesFromCreate", Boolean::class)
                    .mutable(true)
                    .initializer("false")
                    .build(),
            )
            .build()
    }

    private fun buildValidationScope(
        scopeClass: ClassName,
        configClass: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
    ): TypeSpec {
        return TypeSpec.classBuilder(scopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("config", configClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("config", configClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("config")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("create")
                    .addParameter("rules", createRuleType, KModifier.VARARG)
                    .addStatement("config.createRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("rules", updateRuleType, KModifier.VARARG)
                    .addStatement("config.updateRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("delete")
                    .addParameter("rules", deleteRuleType, KModifier.VARARG)
                    .addStatement("config.deleteRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("updateDerivesFromCreate")
                    .addStatement("config.updateDerivesFromCreate = true")
                    .build(),
            )
            .build()
    }
}
