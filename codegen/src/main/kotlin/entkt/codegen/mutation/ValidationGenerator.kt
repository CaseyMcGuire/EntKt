package entkt.codegen.mutation

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeName
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.kotlinpoet.typeAlias
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

        return kotlinFile(packageName, "${schemaName}Validation") {
            typeAlias(createRule, VALIDATION_RULE.parameterizedBy(clientClass, createItem))
            typeAlias(updateRule, VALIDATION_RULE.parameterizedBy(clientClass, updateItem))
            typeAlias(deleteRule, VALIDATION_RULE.parameterizedBy(clientClass, deleteItem))
            typeAlias(createBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, createItem))
            typeAlias(updateBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, updateItem))
            typeAlias(deleteBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, deleteItem))

            addType(buildCreateItem(candidateClass, createItem))
            addType(
                buildUpdateItem(
                    entityClass, candidateClass, patchClass, edgeChangesViewClass, updateItem,
                ),
            )
            addType(buildDeleteItem(entityClass, candidateClass, deleteItem))
            addType(
                buildValidationConfig(
                    configClass,
                    ClassName(packageName, createBatchRule),
                    ClassName(packageName, updateBatchRule),
                    ClassName(packageName, deleteBatchRule),
                ),
            )
            addType(
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
        }
    }

    private fun buildCreateItem(
        candidateClass: ClassName,
        itemClass: ClassName,
    ): TypeSpec = validationItem(itemClass, "candidate" to candidateClass)

    private fun buildUpdateItem(
        entityClass: ClassName,
        candidateClass: ClassName,
        patchClass: ClassName,
        edgeChangesViewClass: ClassName,
        itemClass: ClassName,
    ): TypeSpec = validationItem(
        itemClass,
        "before" to entityClass,
        "requestedPatch" to patchClass,
        "effectivePatch" to patchClass,
        "candidate" to candidateClass,
        "edgeChanges" to edgeChangesViewClass,
    )

    private fun buildDeleteItem(
        entityClass: ClassName,
        candidateClass: ClassName,
        itemClass: ClassName,
    ): TypeSpec = validationItem(
        itemClass,
        "entity" to entityClass,
        "candidate" to candidateClass,
    )

    /** Data shape shared by create, update, and delete validation items. */
    private fun validationItem(
        itemClass: ClassName,
        vararg members: Pair<String, TypeName>,
    ): TypeSpec = classType(itemClass) {
        addModifiers(KModifier.DATA)
        primaryConstructor {
            for ((name, type) in members) parameter(name, type)
        }
        for ((name, type) in members) {
            property(name, type) { initializer(name) }
        }
    }

    private fun buildValidationConfig(
        configClass: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
    ): TypeSpec {
        return classType(configClass) {
            for ((name, ruleType) in listOf(
                "createRules" to createRuleType,
                "updateRules" to updateRuleType,
                "deleteRules" to deleteRuleType,
            )) {
                property(name, MUTABLE_LIST.parameterizedBy(ruleType)) {
                    initializer("mutableListOf()")
                }
            }
            property("updateDerivesFromCreate", BOOLEAN) {
                mutable(true)
                initializer("false")
            }
        }
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
        return classType(scopeClass) {
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("config", configClass)
            }
            property("config", configClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("config")
            }
            addRuleFunctions("create", createRuleType, createBatchRuleType)
            addRuleFunctions("update", updateRuleType, updateBatchRuleType)
            addRuleFunctions("delete", deleteRuleType, deleteBatchRuleType)
            function("updateDerivesFromCreate") {
                statement("config.updateDerivesFromCreate = true")
            }
        }
    }

    /** Add scalar and batch overloads for one validation operation. */
    private fun TypeSpec.Builder.addRuleFunctions(
        operation: String,
        ruleType: ClassName,
        batchRuleType: ClassName,
    ) {
        function(operation) {
            addParameter("rules", ruleType, KModifier.VARARG)
            statement("config.%LRules.addAll(rules)", operation)
        }
        function(operation) {
            addAnnotation(jvmName("${operation}BatchRule"))
            parameter("rule", batchRuleType)
            statement("config.%LRules.add(rule)", operation)
        }
    }

    private fun jvmName(name: String): AnnotationSpec = annotation(JVM_NAME) {
        addMember("%S", name)
    }
}
