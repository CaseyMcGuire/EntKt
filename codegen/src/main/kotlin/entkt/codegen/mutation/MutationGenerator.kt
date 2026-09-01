package entkt.codegen.mutation

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import entkt.codegen.apiName
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.schema.EntSchema

private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val BEFORE_SAVE_HOOK_STATE =
    ClassName("entkt.runtime.mutation", "BeforeSaveHookState")
private val BEFORE_UPDATE_HOOK_STATE =
    ClassName("entkt.runtime.mutation", "BeforeUpdateHookState")

/** Generates the immutable, schema-typed states transformed by before hooks. */
internal class MutationGenerator(
    private val packageName: String,
) {
    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): List<FileSpec> {
        val fields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        val mutableAssignments = buildList {
            fields.filterNot { it.immutable }.forEach { field ->
                add(
                    Assignment(
                        name = field.apiName,
                        valueType = field.resolvedTypeName().copy(nullable = true),
                        comment = field.comment,
                    ),
                )
            }
            edgeFks.filterNot { it.immutable }.forEach { fk ->
                add(
                    Assignment(
                        name = fk.propertyName,
                        valueType = fk.idType.toTypeName().copy(nullable = true),
                        comment = fk.comment,
                    ),
                )
            }
        }
        val createAssignments = buildList {
            fields.forEach { field ->
                add(
                    Assignment(
                        name = field.apiName,
                        valueType = field.resolvedTypeName().copy(nullable = true),
                        comment = field.comment,
                    ),
                )
            }
            edgeFks.forEach { fk ->
                add(
                    Assignment(
                        name = fk.propertyName,
                        valueType = fk.idType.toTypeName().copy(nullable = true),
                        comment = fk.comment,
                    ),
                )
            }
        }

        val beforeSaveClass = ClassName(packageName, "${schemaName}BeforeSaveState")
        val beforeCreateClass = ClassName(packageName, "${schemaName}BeforeCreateState")
        val beforeUpdateClass = ClassName(packageName, "${schemaName}BeforeUpdateState")
        val entityClass = ClassName(packageName, schemaName)

        return listOf(
            stateFile(
                stateClass = beforeSaveClass,
                marker = BEFORE_SAVE_HOOK_STATE.parameterizedBy(entityClass),
                context = emptyList(),
                assignments = mutableAssignments,
            ),
            stateFile(
                stateClass = beforeCreateClass,
                marker = null,
                context = listOf(
                    StateProperty("client", ClassName(packageName, "EntClientScope")),
                    StateProperty("viewerContext", VIEWER_CONTEXT),
                ),
                assignments = createAssignments,
            ),
            stateFile(
                stateClass = beforeUpdateClass,
                marker = BEFORE_UPDATE_HOOK_STATE.parameterizedBy(entityClass),
                context = listOf(
                    StateProperty("client", ClassName(packageName, "EntClientScope")),
                    StateProperty("viewerContext", VIEWER_CONTEXT),
                    StateProperty("before", entityClass),
                    StateProperty(
                        "pendingEdges",
                        ClassName(packageName, "${schemaName}PendingEdgeOps"),
                    ),
                ),
                assignments = mutableAssignments,
            ),
        )
    }

    private fun stateFile(
        stateClass: ClassName,
        marker: TypeName?,
        context: List<StateProperty>,
        assignments: List<Assignment>,
    ): FileSpec {
        val stateType = classType(stateClass.simpleName) {
            marker?.let(::addSuperinterface)
            primaryConstructor {
                addAnnotation(ENTKT_INTERNAL)
                context.forEach { parameter(it.name, it.type) }
                assignments.forEach { assignment ->
                    parameter(assignment.name, assignment.patchType)
                }
            }
            context.forEach { member ->
                property(member.name, member.type) { initializer(member.name) }
            }
            assignments.forEach { assignment ->
                property(assignment.name, assignment.patchType) {
                    initializer(assignment.name)
                    assignment.comment?.let { addKdoc("%L", it) }
                }
                addFunction(replacementFunction(stateClass, context, assignments, assignment, set = true))
                addFunction(replacementFunction(stateClass, context, assignments, assignment, set = false))
            }
        }
        return kotlinFile(packageName, stateClass.simpleName) {
            addAnnotation(entktInternalFileOptIn())
            addType(stateType)
        }
    }

    private fun replacementFunction(
        stateClass: ClassName,
        context: List<StateProperty>,
        assignments: List<Assignment>,
        target: Assignment,
        set: Boolean,
    ) = function(
        (if (set) "set" else "unset") + target.name.replaceFirstChar { it.uppercaseChar() },
        stateClass,
    ) {
        if (set) parameter("value", target.valueType)
        addCode(codeBlock {
            add("return %T(\n", stateClass)
            indent()
            context.forEach { member -> add("%L = %L,\n", member.name, member.name) }
            assignments.forEach { assignment ->
                when {
                    assignment != target -> add("%L = %L,\n", assignment.name, assignment.name)
                    set -> add("%L = %T.Set(value),\n", assignment.name, FIELD_PATCH)
                    else -> add("%L = %T.Unset,\n", assignment.name, FIELD_PATCH)
                }
            }
            unindent()
            add(")\n")
        })
    }

    private data class StateProperty(
        val name: String,
        val type: TypeName,
    )

    private data class Assignment(
        val name: String,
        val valueType: TypeName,
        val comment: String?,
    ) {
        val patchType: TypeName = FIELD_PATCH.parameterizedBy(valueType)
    }
}
