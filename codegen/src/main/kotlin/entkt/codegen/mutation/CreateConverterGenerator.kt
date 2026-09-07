package entkt.codegen.mutation

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import entkt.codegen.apiName
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val CREATE_MUTATION_CONVERTER =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationConverter")
private val CREATE_MUTATION_HOOK_STATE_CONVERTER =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationHookStateConverter")

/** Schema-specific conversions only; runtime owns hook folding, lifecycle ordering, and I/O. */
internal class CreateConverterGenerator(private val packageName: String) {
    private val clientScopeClass = ClassName(packageName, "EntClientScope")

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}CreateConverter"
        val createDraftClass = ClassName(packageName, "${schemaName}CreateDraft")
        val entityClass = ClassName(packageName, schemaName)
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val beforeSaveStateClass = ClassName(packageName, "${schemaName}BeforeSaveState")
        val beforeCreateStateClass = ClassName(packageName, "${schemaName}BeforeCreateState")
        val fields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        val mutableFields = fields.filterNot { it.immutable }
        val mutableEdgeFks = edgeFks.filterNot { it.immutable }
        val converterType = CREATE_MUTATION_HOOK_STATE_CONVERTER.parameterizedBy(
            createDraftClass,
            beforeSaveStateClass,
            beforeCreateStateClass,
        )
        val typeSpec = classType(className) {
            addModifiers(KModifier.INTERNAL)
            addAnnotation(ENTKT_INTERNAL)
            addSuperinterface(converterType)
            addSuperinterface(CREATE_MUTATION_CONVERTER.parameterizedBy(createDraftClass, candidateClass, entityClass))
            primaryConstructor {
                parameter("driver", DRIVER)
                parameter("client", clientScopeClass)
            }
            property("driver", DRIVER) {
                addModifiers(KModifier.PRIVATE)
                initializer("driver")
            }
            property("client", clientScopeClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            function("toBeforeSaveState", beforeSaveStateClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("draft", createDraftClass)
                addCode(codeBlock {
                    add("return %T(\n", beforeSaveStateClass)
                    indent()
                    mutableFields.forEach { field ->
                        add(
                            "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                            field.apiName,
                            entityClass,
                            field.apiName,
                            FIELD_PATCH,
                            field.apiName,
                            FIELD_PATCH,
                        )
                    }
                    mutableEdgeFks.forEach { fk ->
                        add(
                            "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                            fk.propertyName,
                            entityClass,
                            fk.propertyName,
                            FIELD_PATCH,
                            fk.propertyName,
                            FIELD_PATCH,
                        )
                    }
                    unindent()
                    add(")\n")
                })
            }
            function("toBeforeCreateState", beforeCreateStateClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("viewerContext", VIEWER_CONTEXT)
                parameter("draft", createDraftClass)
                parameter("beforeSaveState", beforeSaveStateClass)
                addCode(codeBlock {
                    add("return %T(\n", beforeCreateStateClass)
                    indent()
                    add("client = client,\n")
                    add("viewerContext = viewerContext,\n")
                    fields.forEach { field ->
                        if (field.immutable) {
                            add(
                                "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                                field.apiName,
                                entityClass,
                                field.apiName,
                                FIELD_PATCH,
                                field.apiName,
                                FIELD_PATCH,
                            )
                        } else {
                            add("%L = beforeSaveState.%L,\n", field.apiName, field.apiName)
                        }
                    }
                    edgeFks.forEach { fk ->
                        if (fk.immutable) {
                            add(
                                "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                                fk.propertyName,
                                entityClass,
                                fk.propertyName,
                                FIELD_PATCH,
                                fk.propertyName,
                                FIELD_PATCH,
                            )
                        } else {
                            add("%L = beforeSaveState.%L,\n", fk.propertyName, fk.propertyName)
                        }
                    }
                    unindent()
                    add(")\n")
                })
            }
            function("toPreparationDraft", createDraftClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("originalDraft", createDraftClass)
                parameter("state", beforeCreateStateClass)
                if (idStrategyName(schema) == "EXPLICIT") {
                    statement("val draft = %T(originalDraft.id)", createDraftClass)
                } else {
                    statement("val draft = %T()", createDraftClass)
                }
                (fields.map { it.apiName } + edgeFks.map { it.propertyName }).forEach { property ->
                    addCode(
                        "when (val entry = state.%L) {\n" +
                            "  %T.Unset -> Unit\n" +
                            "  is %T.Set -> draft.%L = entry.value\n" +
                            "}\n",
                        property,
                        FIELD_PATCH,
                        FIELD_PATCH,
                        property,
                    )
                }
                statement("return draft")
            }
            val createGenerator = CreateGenerator(packageName)
            addFunction(createGenerator.buildRequiredInputViolationsFunction(schemaName, schema, schemaNames))
            addFunction(createGenerator.buildResolveFunction(schemaName, schema, schemaNames))
            addFunction(createGenerator.buildCreateFieldViolationsFunction(schemaName, schema, schemaNames))
        }
        return kotlinFile(packageName, className) {
            addAnnotation(entktInternalFileOptIn())
            addType(typeSpec)
        }
    }
}
