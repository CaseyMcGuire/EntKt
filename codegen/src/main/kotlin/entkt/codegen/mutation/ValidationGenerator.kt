package entkt.codegen.mutation

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.typeAlias
import entkt.schema.EntSchema

private val VALIDATION_RULE = ClassName("entkt.runtime.validation", "ValidationRule")
private val BATCH_VALIDATION_RULE = ClassName("entkt.runtime.validation", "BatchValidationRule")
private val ENTITY_VALIDATION_CONFIG = ClassName("entkt.runtime.validation", "EntityValidationConfig")
private val ENTITY_VALIDATION_SCOPE = ClassName("entkt.runtime.validation", "EntityValidationScope")

/**
 * Emits per-entity validation infrastructure:
 *
 * - `{Entity}ValidationConfig` — binds the runtime config to the entity's rule types
 * - `{Entity}ValidationScope` — binds the runtime registration DSL to the same rule types
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
            superclass(
                ENTITY_VALIDATION_CONFIG.parameterizedBy(
                    createRuleType,
                    updateRuleType,
                    deleteRuleType,
                ),
            )
        }
    }

    private fun buildValidationScope(
        scopeClass: ClassName,
        configClass: ClassName,
        createBatchRuleType: ClassName,
        updateBatchRuleType: ClassName,
        deleteBatchRuleType: ClassName,
    ): TypeSpec {
        return classType(scopeClass) {
            superclass(
                ENTITY_VALIDATION_SCOPE.parameterizedBy(
                    createBatchRuleType,
                    updateBatchRuleType,
                    deleteBatchRuleType,
                ),
            )
            addSuperclassConstructorParameter("config")
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("config", configClass)
            }
        }
    }
}
