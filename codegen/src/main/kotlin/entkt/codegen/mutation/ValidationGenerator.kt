package entkt.codegen.mutation

import com.squareup.kotlinpoet.AnnotationSpec
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

private val VALIDATION_RULE = ClassName("entkt.runtime.validation", "ValidationRule")
private val BATCH_VALIDATION_RULE = ClassName("entkt.runtime.validation", "BatchValidationRule")
private val JVM_NAME = ClassName("kotlin.jvm", "JvmName")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")

/**
 * Emits per-entity validation infrastructure:
 *
 * - `{Entity}ValidationConfig` — internal mutable config holding rule lists
 * - `{Entity}ValidationScope` — DSL scope for declaring rules per operation
 * - `{Entity}{Op}ValidationItem` — per-item snapshots for create/update/delete
 * - `{Entity}{Op}ValidationRule` and `{Entity}{Op}BatchValidationRule` — typealiases for each operation's rule types
 *
 * Unlike privacy, validation has no LOAD operation and its shared rule context
 * does not carry a [PrivacyContext] — validation is viewer-agnostic. The
 * [WriteCandidate] is reused from the privacy generator.
 */
internal class ValidationGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val entityClass = ClassName(packageName, schemaName)
        // The shared ValidationRuleContext exposes the validation-posture read client,
        // not the full EntClient — validator writes are compile errors,
        // not a documentation convention. Generated evaluators construct
        // it via `client.asValidationReadClientForInternalUse()`, which
        // fixes the PrivacyBypass("validation read") context, and the
        // concrete type makes the privacy-bypass read posture visible in
        // helper signatures.
        val clientClass = ClassName(packageName, "EntValidationReadClient")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val edgeChangesViewClass = ClassName(packageName, "${schemaName}EdgeChangesView")
        val configClass = ClassName(packageName, "${schemaName}ValidationConfig")
        val scopeClass = ClassName(packageName, "${schemaName}ValidationScope")

        val fileBuilder = FileSpec.builder(packageName, "${schemaName}Validation")

        // Operation item class names. The runtime ValidationRuleContext holds
        // the shared read client once for the whole evaluation phase.
        val createItem = ClassName(packageName, "${schemaName}CreateValidationItem")
        val updateItem = ClassName(packageName, "${schemaName}UpdateValidationItem")
        val deleteItem = ClassName(packageName, "${schemaName}DeleteValidationItem")

        // Rule typealiases
        val createRule = "${schemaName}CreateValidationRule"
        val updateRule = "${schemaName}UpdateValidationRule"
        val deleteRule = "${schemaName}DeleteValidationRule"
        val createBatchRule = "${schemaName}CreateBatchValidationRule"
        val updateBatchRule = "${schemaName}UpdateBatchValidationRule"
        val deleteBatchRule = "${schemaName}DeleteBatchValidationRule"

        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(createRule, VALIDATION_RULE.parameterizedBy(clientClass, createItem)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(updateRule, VALIDATION_RULE.parameterizedBy(clientClass, updateItem)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(deleteRule, VALIDATION_RULE.parameterizedBy(clientClass, deleteItem)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(createBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, createItem)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(updateBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, updateItem)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(deleteBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, deleteItem)).build(),
        )

        // Operation item data classes
        fileBuilder.addType(buildCreateItem(candidateClass, createItem))
        fileBuilder.addType(
            buildUpdateItem(
                entityClass, candidateClass, patchClass, edgeChangesViewClass, updateItem,
            ),
        )
        fileBuilder.addType(buildDeleteItem(entityClass, candidateClass, deleteItem))

        // ValidationConfig
        fileBuilder.addType(
            buildValidationConfig(
                configClass,
                ClassName(packageName, createBatchRule),
                ClassName(packageName, updateBatchRule),
                ClassName(packageName, deleteBatchRule),
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
                ClassName(packageName, createBatchRule),
                ClassName(packageName, updateBatchRule),
                ClassName(packageName, deleteBatchRule),
            ),
        )

        return fileBuilder.build()
    }

    private fun buildCreateItem(
        candidateClass: ClassName,
        itemClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(itemClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildUpdateItem(
        entityClass: ClassName,
        candidateClass: ClassName,
        patchClass: ClassName,
        edgeChangesViewClass: ClassName,
        itemClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(itemClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("before", entityClass)
                .addParameter("requestedPatch", patchClass)
                .addParameter("effectivePatch", patchClass)
                .addParameter("candidate", candidateClass)
                .addParameter("edgeChanges", edgeChangesViewClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("before", entityClass).initializer("before").build())
        .addProperty(PropertySpec.builder("requestedPatch", patchClass).initializer("requestedPatch").build())
        .addProperty(PropertySpec.builder("effectivePatch", patchClass).initializer("effectivePatch").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .addProperty(PropertySpec.builder("edgeChanges", edgeChangesViewClass).initializer("edgeChanges").build())
        .build()

    private fun buildDeleteItem(
        entityClass: ClassName,
        candidateClass: ClassName,
        itemClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(itemClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("entity", entityClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
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
        createBatchRuleType: ClassName,
        updateBatchRuleType: ClassName,
        deleteBatchRuleType: ClassName,
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
                FunSpec.builder("create")
                    .addAnnotation(jvmName("createBatchRule"))
                    .addParameter("rule", createBatchRuleType)
                    .addStatement("config.createRules.add(rule)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("rules", updateRuleType, KModifier.VARARG)
                    .addStatement("config.updateRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("update")
                    .addAnnotation(jvmName("updateBatchRule"))
                    .addParameter("rule", updateBatchRuleType)
                    .addStatement("config.updateRules.add(rule)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("delete")
                    .addParameter("rules", deleteRuleType, KModifier.VARARG)
                    .addStatement("config.deleteRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("delete")
                    .addAnnotation(jvmName("deleteBatchRule"))
                    .addParameter("rule", deleteBatchRuleType)
                    .addStatement("config.deleteRules.add(rule)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("updateDerivesFromCreate")
                    .addStatement("config.updateDerivesFromCreate = true")
                    .build(),
            )
            .build()
    }

    private fun jvmName(name: String): AnnotationSpec = AnnotationSpec.builder(JVM_NAME)
        .addMember("%S", name)
        .build()
}
