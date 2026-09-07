package entkt.codegen.mutation

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property

/** Emits compound UPDATE/DELETE inputs; CREATE rules receive the write candidate directly. */
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
                "before" to entity,
                "requestedPatch" to patch,
                "effectivePatch" to patch,
                "candidate" to candidate,
                "edgeChanges" to edgeChanges,
            ),
            ruleInputFile(
                ClassName(packageName, "${schemaName}DeleteRuleInput"),
                "entity" to entity,
                "candidate" to candidate,
            ),
        )
    }

    private fun ruleInputFile(
        inputClass: ClassName,
        vararg members: Pair<String, TypeName>,
    ): FileSpec = kotlinFile(packageName, inputClass.simpleName) {
        addType(
            classType(inputClass) {
                addModifiers(KModifier.DATA)
                addKdoc(
                    "Rule inputs share their values without per-rule defensive copies.\n" +
                        "Treat all properties and nested values as read-only.\n",
                )
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
