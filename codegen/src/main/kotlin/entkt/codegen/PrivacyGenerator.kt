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
import entkt.schema.Field

private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val PRIVACY_RULE = ClassName("entkt.runtime", "PrivacyRule")
private val ENTITY_POLICY = ClassName("entkt.runtime", "EntityPolicy")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")

/**
 * Emits per-entity privacy infrastructure:
 *
 * - `{Entity}PrivacyConfig` — internal mutable config holding rule lists
 * - `{Entity}PrivacyScope` — DSL scope for declaring rules per operation
 * - `{Entity}PolicyScope` — outer scope passed to [EntityPolicy.configure]
 * - `{Entity}WriteCandidate` — snapshot of writable fields for write rules
 * - `{Entity}{Op}PrivacyContext` — context classes for each operation
 * - `{Entity}{Op}PrivacyRule` — typealiases for each operation's rule type
 */
internal class PrivacyGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val entityClass = ClassName(packageName, schemaName)
        val clientClass = ClassName(packageName, "EntClient")
        val configClass = ClassName(packageName, "${schemaName}PrivacyConfig")
        val privacyScopeClass = ClassName(packageName, "${schemaName}PrivacyScope")
        val policyScopeClass = ClassName(packageName, "${schemaName}PolicyScope")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")

        val fields = schema.fields()
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val fileBuilder = FileSpec.builder(packageName, "${schemaName}Privacy")

        // Operation context class names
        val loadCtx = ClassName(packageName, "${schemaName}LoadPrivacyContext")
        val createCtx = ClassName(packageName, "${schemaName}CreatePrivacyContext")
        val updateCtx = ClassName(packageName, "${schemaName}UpdatePrivacyContext")
        val deleteCtx = ClassName(packageName, "${schemaName}DeletePrivacyContext")

        // Rule typealiases
        val loadRule = "${schemaName}LoadPrivacyRule"
        val createRule = "${schemaName}CreatePrivacyRule"
        val updateRule = "${schemaName}UpdatePrivacyRule"
        val deleteRule = "${schemaName}DeletePrivacyRule"

        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(loadRule, PRIVACY_RULE.parameterizedBy(loadCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(createRule, PRIVACY_RULE.parameterizedBy(createCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(updateRule, PRIVACY_RULE.parameterizedBy(updateCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(deleteRule, PRIVACY_RULE.parameterizedBy(deleteCtx)).build(),
        )

        // Operation context data classes
        fileBuilder.addType(buildLoadContext(schemaName, entityClass, clientClass, loadCtx))
        fileBuilder.addType(buildCreateContext(schemaName, clientClass, candidateClass, createCtx))
        fileBuilder.addType(buildUpdateContext(schemaName, entityClass, clientClass, candidateClass, updateCtx))
        fileBuilder.addType(buildDeleteContext(schemaName, entityClass, clientClass, candidateClass, deleteCtx))

        // WriteCandidate
        fileBuilder.addType(buildWriteCandidate(schemaName, candidateClass, fields, edgeFks))

        // PrivacyConfig
        fileBuilder.addType(
            buildPrivacyConfig(
                configClass,
                ClassName(packageName, loadRule),
                ClassName(packageName, createRule),
                ClassName(packageName, updateRule),
                ClassName(packageName, deleteRule),
            ),
        )

        // PrivacyScope
        fileBuilder.addType(
            buildPrivacyScope(
                privacyScopeClass,
                configClass,
                ClassName(packageName, loadRule),
                ClassName(packageName, createRule),
                ClassName(packageName, updateRule),
                ClassName(packageName, deleteRule),
            ),
        )

        // PolicyScope
        fileBuilder.addType(buildPolicyScope(schemaName, policyScopeClass, privacyScopeClass, configClass))

        return fileBuilder.build()
    }

    private fun buildLoadContext(
        schemaName: String,
        entityClass: ClassName,
        clientClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("entity", entityClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("entity", entityClass).initializer("entity").build())
        .build()

    private fun buildCreateContext(
        schemaName: String,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildUpdateContext(
        schemaName: String,
        entityClass: ClassName,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("before", entityClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("before", entityClass).initializer("before").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildDeleteContext(
        schemaName: String,
        entityClass: ClassName,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("entity", entityClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("entity", entityClass).initializer("entity").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildWriteCandidate(
        schemaName: String,
        candidateClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): TypeSpec {
        val ctor = FunSpec.constructorBuilder()
        val props = mutableListOf<PropertySpec>()

        for (field in fields) {
            val propName = toCamelCase(field.name)
            val typeName = field.resolvedTypeName().copy(nullable = field.nullable)
            ctor.addParameter(propName, typeName)
            props.add(PropertySpec.builder(propName, typeName).initializer(propName).build())
        }
        for (fk in edgeFks) {
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            ctor.addParameter(fk.propertyName, typeName)
            props.add(PropertySpec.builder(fk.propertyName, typeName).initializer(fk.propertyName).build())
        }

        if (props.isEmpty()) {
            return TypeSpec.classBuilder(candidateClass).build()
        }
        return TypeSpec.classBuilder(candidateClass)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(ctor.build())
            .addProperties(props)
            .build()
    }

    private fun buildPrivacyConfig(
        configClass: ClassName,
        loadRuleType: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
    ): TypeSpec {
        return TypeSpec.classBuilder(configClass)
            .addProperty(
                PropertySpec.builder("loadRules", MUTABLE_LIST.parameterizedBy(loadRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
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
            .addProperty(
                PropertySpec.builder("deleteDerivesFromCreate", Boolean::class)
                    .mutable(true)
                    .initializer("false")
                    .build(),
            )
            .build()
    }

    private fun buildPrivacyScope(
        scopeClass: ClassName,
        configClass: ClassName,
        loadRuleType: ClassName,
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
                FunSpec.builder("load")
                    .addParameter("rules", loadRuleType, KModifier.VARARG)
                    .addStatement("config.loadRules.addAll(rules)")
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
            .addFunction(
                FunSpec.builder("deleteDerivesFromCreate")
                    .addStatement("config.deleteDerivesFromCreate = true")
                    .build(),
            )
            .build()
    }

    private fun buildPolicyScope(
        schemaName: String,
        policyScopeClass: ClassName,
        privacyScopeClass: ClassName,
        configClass: ClassName,
    ): TypeSpec {
        val validationConfigClass = ClassName(packageName, "${schemaName}ValidationConfig")
        val validationScopeClass = ClassName(packageName, "${schemaName}ValidationScope")
        val privacyBlockLambda = com.squareup.kotlinpoet.LambdaTypeName.get(
            receiver = privacyScopeClass,
            returnType = UNIT,
        )
        val validationBlockLambda = com.squareup.kotlinpoet.LambdaTypeName.get(
            receiver = validationScopeClass,
            returnType = UNIT,
        )
        return TypeSpec.classBuilder(policyScopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("privacyConfig", configClass)
                    .addParameter("validationConfig", validationConfigClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("privacyConfig", configClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("privacyConfig")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("validationConfig", validationConfigClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("validationConfig")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("privacy")
                    .addParameter("block", privacyBlockLambda)
                    .addStatement("%T(privacyConfig).apply(block)", privacyScopeClass)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("validation")
                    .addParameter("block", validationBlockLambda)
                    .addStatement("%T(validationConfig).apply(block)", validationScopeClass)
                    .build(),
            )
            .build()
    }
}
