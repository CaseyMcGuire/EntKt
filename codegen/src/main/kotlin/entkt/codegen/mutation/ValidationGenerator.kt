package entkt.codegen.mutation

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
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
private val VALIDATION_ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val RESOLVED_ENTITY_VALIDATION_CONFIG =
    ClassName("entkt.runtime.validation", "ResolvedEntityValidationConfig")

/**
 * Emits per-entity validation infrastructure:
 *
 * - `{Entity}ValidationConfig` — internal mutable config holding rule lists
 * - `{Entity}ValidationScope` — DSL scope for declaring rules per operation
 * - `{Entity}{Op}ValidationRule` and `{Entity}{Op}BatchValidationRule` — typealiases for each operation's rule types
 *
 * Unlike privacy, validation has no LOAD operation and its shared rule context
 * does not carry a [ViewerContext] — validation is viewer-agnostic. The
 * CREATE, UPDATE, and DELETE rules consume the same generated lifecycle input
 * types as privacy rules.
 */
internal class ValidationGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        // The shared ValidationRuleContext exposes the stable read-only client,
        // not the full EntClient — validator writes are compile errors,
        // not a documentation convention. Generated evaluators reuse the
        // client's stable validation reader; ValidationRuleContext supplies
        // the explicit PrivacyBypass("validation read") context. The concrete
        // type makes the privacy-bypass read posture visible in helper
        // signatures.
        val clientClass = ClassName(packageName, "ReadOnlyEntClient")
        val configClass = ClassName(packageName, "${schemaName}ValidationConfig")
        val scopeClass = ClassName(packageName, "${schemaName}ValidationScope")

        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val updateInput = ClassName(packageName, "${schemaName}UpdateRuleInput")
        val deleteInput = ClassName(packageName, "${schemaName}DeleteRuleInput")

        // Rule typealiases
        val createRule = "${schemaName}CreateValidationRule"
        val updateRule = "${schemaName}UpdateValidationRule"
        val deleteRule = "${schemaName}DeleteValidationRule"
        val createBatchRule = "${schemaName}CreateBatchValidationRule"
        val updateBatchRule = "${schemaName}UpdateBatchValidationRule"
        val deleteBatchRule = "${schemaName}DeleteBatchValidationRule"

        return kotlinFile(packageName, "${schemaName}Validation") {
            typeAlias(createRule, VALIDATION_RULE.parameterizedBy(clientClass, candidateClass)) {
                addKdoc(
                    "CREATE rules receive the prepared write candidate without per-rule defensive copies.\n" +
                        "Treat the candidate and all nested values as read-only.\n",
                )
            }
            typeAlias(updateRule, VALIDATION_RULE.parameterizedBy(clientClass, updateInput))
            typeAlias(deleteRule, VALIDATION_RULE.parameterizedBy(clientClass, deleteInput))
            typeAlias(createBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, candidateClass)) {
                addKdoc(
                    "Batch CREATE rules receive prepared write candidates without per-rule defensive copies.\n" +
                        "Treat the candidates and all nested values as read-only.\n",
                )
            }
            typeAlias(updateBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, updateInput))
            typeAlias(deleteBatchRule, BATCH_VALIDATION_RULE.parameterizedBy(clientClass, deleteInput))

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
            val resolvedType = RESOLVED_ENTITY_VALIDATION_CONFIG.parameterizedBy(
                createRuleType,
                updateRuleType,
                deleteRuleType,
            )
            function("resolveForInternalUse", resolvedType) {
                addAnnotation(VALIDATION_ENTKT_INTERNAL)
                addModifiers(KModifier.INTERNAL)
                statement(
                    "return %T(\n" +
                        "  createRules = createRules,\n" +
                        "  updateRules = updateRules,\n" +
                        "  deleteRules = deleteRules,\n" +
                        "  updateDerivesFromCreate = updateDerivesFromCreate,\n" +
                        ")",
                    resolvedType,
                )
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
