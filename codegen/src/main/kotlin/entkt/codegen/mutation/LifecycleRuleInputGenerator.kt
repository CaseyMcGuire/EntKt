package entkt.codegen.mutation

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.typeAlias

private val UPDATE_RULE_INPUT = ClassName("entkt.runtime.mutation", "UpdateRuleInput")
private val DELETE_RULE_INPUT = ClassName("entkt.runtime.mutation", "DeleteRuleInput")

private const val RULE_INPUT_KDOC =
    "Rule inputs share their values without per-rule defensive copies.\n" +
        "Treat all properties and nested values as read-only.\n"

/**
 * Emits the UPDATE input class and the DELETE input alias; CREATE rules receive the write candidate directly.
 *
 * UPDATE needs a class because its patch and edge-change types are per-entity. DELETE's entity and candidate
 * fit the runtime `DeleteRuleInput`, which the runtime also reads, so it only needs a per-entity name.
 */
internal class LifecycleRuleInputGenerator(
    private val packageName: String,
) {
    fun generate(schemaName: String): List<FileSpec> {
        val entity = ClassName(packageName, schemaName)
        val candidate = ClassName(packageName, "${schemaName}WriteCandidate")
        val patch = ClassName(packageName, "${schemaName}UpdatePatch")
        val edgeChanges = ClassName(packageName, "${schemaName}EdgeChangesView")

        return listOf(
            ruleInputFile(
                ClassName(packageName, "${schemaName}UpdateRuleInput"),
                UPDATE_RULE_INPUT.parameterizedBy(entity),
                "before" to entity,
                "requestedPatch" to patch,
                "effectivePatch" to patch,
                "candidate" to candidate,
                "edgeChanges" to edgeChanges,
            ),
            kotlinFile(packageName, "${schemaName}DeleteRuleInput") {
                typeAlias("${schemaName}DeleteRuleInput", DELETE_RULE_INPUT.parameterizedBy(entity, candidate)) {
                    addKdoc(RULE_INPUT_KDOC)
                }
            },
        )
    }

    private fun ruleInputFile(
        inputClass: ClassName,
        marker: TypeName,
        vararg members: Pair<String, TypeName>,
    ): FileSpec = kotlinFile(packageName, inputClass.simpleName) {
        addType(
            classType(inputClass) {
                addModifiers(KModifier.DATA)
                addSuperinterface(marker)
                addKdoc(RULE_INPUT_KDOC)
                primaryConstructor {
                    members.forEach { (name, type) -> parameter(name, type) }
                }
                members.forEach { (name, type) ->
                    property(name, type) { initializer(name) }
                }
            },
        )
    }
}
