package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.apiName
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.getter
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema
import entkt.schema.Field

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val INT = Int::class.asClassName()
private val ENT_CLIENT_NAME = "EntClient"
private val CREATE_MUTATION_OPERATIONS =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationOperations")
private val DELETE_MUTATION_INPUT =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationInput")
private val MUTATION_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "MutationOperation")
private val BUILD_UPDATE_MUTATION_OPERATION =
    MemberName("entkt.runtime.mutation.execution", "buildUpdateMutationOperation")
private val BUILD_DELETE_MUTATION_OPERATION =
    MemberName("entkt.runtime.mutation.execution", "buildDeleteMutationOperation")
private val BUILD_DELETE_MANY_MUTATION_OPERATION =
    MemberName("entkt.runtime.mutation.execution", "buildDeleteManyMutationOperation")
private val DELETE_MANY_MUTATION_INPUT =
    ClassName("entkt.runtime.mutation.execution", "DeleteManyMutationInput")
private val UPDATE_MUTATION_INPUT =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationInput")
private val UPDATE_MUTATION_HOOKS =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationHooks")
private val UPDATE_MUTATION_HOOK_STATE_CONVERTER =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationHookStateConverter")
private val GENERATED_ID_REPOSITORY = ClassName("entkt.runtime.repository", "GeneratedIdRepository")
private val EXPLICIT_ID_REPOSITORY = ClassName("entkt.runtime.repository", "ExplicitIdRepository")
private val TRANSACTION_SCOPE = ClassName("entkt.runtime.result", "TransactionScope")
private val TRANSACTION_RESULT = ClassName("entkt.runtime.result", "TransactionResult")

/**
 * Wires a schema's objects into its ID-specific runtime repository base.
 * Common entry points, pending-mutation binding, and executor invocation are inherited;
 * generated members construct schema-specific values and select the transaction-bound repo.
 *
 * The client supplies the repository's runtime context, hooks, privacy,
 * and validation configuration through the constructor. A repository is
 * therefore complete as soon as it is visible; no attach or apply phase is
 * required after construction.
 */
internal class RepoGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Repo"
        val entityClass = ClassName(packageName, schemaName)
        val createDraftClass = ClassName(packageName, "${schemaName}CreateDraft")
        val updateDraftClass = ClassName(packageName, "${schemaName}UpdateDraft")
        val entityDescriptorClass = ClassName(packageName, "${schemaName}Descriptor")
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val indexesClass = ClassName(packageName, "${schemaName}Indexes")
        val beforeSaveStateClass = ClassName(packageName, "${schemaName}BeforeSaveState")
        val entityHooksType = resolvedEntityHooksType(packageName, schemaName)
        val privacyConfigType = resolvedEntityPrivacyConfigType(packageName, schemaName)
        val validationConfigType = resolvedEntityValidationConfigType(packageName, schemaName)
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val ruleClientClass = ClassName(packageName, "ReadOnlyEntClient")
        val idType = schema.id().type.toTypeName()
        val generatedId = idStrategyName(schema) != "EXPLICIT"
        val repositoryBase = (if (generatedId) GENERATED_ID_REPOSITORY else EXPLICIT_ID_REPOSITORY).parameterizedBy(
            entityClass, idType, createDraftClass, updateDraftClass, queryClass, ruleClientClass,
        )

        val typeSpec = classType(className) {
            superclass(repositoryBase)
            addSuperclassConstructorParameter(codeBlock {
                // KotlinPoet's four-space supertype continuation does not indent explicit newlines.
                add("\n")
                add("      entity = %T,\n", entityDescriptorClass)
                add("      driver = driver,\n")
                add("      mutationRuntime = client,\n")
                add("      readExecutionHost = client,\n")
                add("      loadPrivacyRules = configuredPrivacy.loadRules,\n")
                add("      defaultUpdateConsistency = client.defaultUpdateConsistency,\n")
                add("      defaultRelationshipLocking = client.defaultRelationshipLocking,\n")
                add("    ")
            })
            // The repo is the entity's read surface: query terminals reach
            // `hasLoadPrivacy()` / `evaluateLoadPrivacy(...)` through the
            // EntReadRuntime contract's `${prop}: ${Entity}ReadSurface`
            // accessor, which EntClient overrides with this repo.
            addSuperinterface(ClassName(packageName, "${schemaName}ReadSurface"))
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("driver", DRIVER)
                parameter("client", clientClass)
                parameter("configuredHooks", entityHooksType)
                parameter("configuredPrivacy", privacyConfigType)
                parameter("configuredValidation", validationConfigType)
            }
            property("driver", DRIVER) {
                addModifiers(KModifier.PRIVATE)
                initializer("driver")
            }
            // Private so a repository exposed through EntTransactionClient
            // cannot leak its hidden full EntClient and restore the nested
            // transaction entry point.
            property("client", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            property("ruleClient", ruleClientClass) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                getter { statement("return client.readOnlyClient") }
            }
            addProperty(buildUpdateMutationOperationProperty(schemaName))
            val createConverterClass = ClassName(packageName, "${schemaName}CreateConverter")
            property("createConverter", createConverterClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("%T(driver, client.hookClientScopeForInternalUse)", createConverterClass)
            }
            addProperty(
                buildCreateOperationsProperty(
                    createDraftClass = createDraftClass,
                    entityClass = entityClass,
                ),
            )
            property(
                "deleteOperation",
                MUTATION_OPERATION.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                    DELETE_MUTATION_INPUT,
                    Boolean::class.asClassName(),
                ),
            ) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                initializer(codeBlock {
                    add("%M(\n", BUILD_DELETE_MUTATION_OPERATION)
                    indent()
                    add("entity = %T,\n", entityDescriptorClass)
                    add("converter = %T,\n", ClassName(packageName, "${schemaName}DeleteConverter"))
                    add("privacy = configuredPrivacy,\n")
                    add("validation = configuredValidation,\n")
                    add("ruleInput = ::%T,\n", ClassName(packageName, "${schemaName}DeleteRuleInput"))
                    add("beforeDelete = configuredHooks.beforeDelete,\n")
                    add("afterDelete = configuredHooks.afterDelete,\n")
                    unindent()
                    add(")")
                })
            }
            property(
                "deleteManyOperation",
                MUTATION_OPERATION.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                    DELETE_MANY_MUTATION_INPUT.parameterizedBy(entityClass),
                    INT,
                ),
            ) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                initializer(codeBlock {
                    add("%M(\n", BUILD_DELETE_MANY_MUTATION_OPERATION)
                    indent()
                    add("entity = %T,\n", entityDescriptorClass)
                    add("converter = %T,\n", ClassName(packageName, "${schemaName}DeleteConverter"))
                    add("privacy = configuredPrivacy,\n")
                    add("validation = configuredValidation,\n")
                    add("ruleInput = ::%T,\n", ClassName(packageName, "${schemaName}DeleteRuleInput"))
                    add("readQueryExecutor = readQueryExecutor,\n")
                    add("beforeDelete = configuredHooks.beforeDelete,\n")
                    add("afterDelete = configuredHooks.afterDelete,\n")
                    unindent()
                    add(")")
                })
            }
            // Index-helper namespace. Emitted only when the schema has at
            // least one eligible index (matching the conditional
            // `${schemaName}Indexes` file).
            if (indexHelperTree(schema, schemaNames) != null) {
                addProperty(buildIndexesProperty(indexesClass, clientRef = "client"))
            }
            function("newQuery", queryClass) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                statement("return %T(driver, client)", queryClass)
            }
            function("newUpdateDraft", updateDraftClass) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                statement("return %T()", updateDraftClass)
            }
            function("newCreateDraft", createDraftClass) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                if (generatedId) {
                    statement("return %T()", createDraftClass)
                } else {
                    parameter("id", idType)
                    statement("return %T(id = id)", createDraftClass)
                }
            }
            addFunction(buildWithTransaction(repositoryBase, schemaName, schema.clientName))
            addType(
                buildHookStateConverterType(
                    draftClass = updateDraftClass,
                    entityClass = entityClass,
                    beforeSaveStateClass = beforeSaveStateClass,
                    beforeUpdateStateClass = ClassName(packageName, "${schemaName}BeforeUpdateState"),
                    clientClass = clientClass,
                    pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps"),
                    mutableFields = scalarFields(schema).filterNot { it.immutable },
                    edgeFks = computeEdgeFks(schema, schemaNames).filterNot { it.immutable },
                ),
            )
        }

        // The repo class implements the `@EntktInternal`-guarded
        // `${schemaName}ReadSurface`; the file-level OptIn consumes the
        // requirement at the declaration site without propagating it to
        // application code using the repo.
        return kotlinFile(packageName, className) {
            addAnnotation(annotation(ClassName("kotlin", "OptIn")) {
                useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
            })
            addType(typeSpec)
        }
    }

    private fun buildUpdateMutationOperationProperty(schemaName: String): PropertySpec {
        val entityClass = ClassName(packageName, schemaName)
        val entityDescriptorClass = ClassName(packageName, "${schemaName}Descriptor")
        val draftClass = ClassName(packageName, "${schemaName}UpdateDraft")
        val adapterClass = ClassName(packageName, "${schemaName}UpdateAdapter")
        val preparedStateClass = adapterClass.nestedClass("PreparedState")
        val updateRuleInput = ClassName(packageName, "${schemaName}UpdateRuleInput")
        return property(
            "updateOperation",
            MUTATION_OPERATION.parameterizedBy(
                ClassName(packageName, "ReadOnlyEntClient"),
                UPDATE_MUTATION_INPUT.parameterizedBy(draftClass),
                entityClass,
            ),
        ) {
            addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
            initializer(codeBlock {
                add("%M(\n", BUILD_UPDATE_MUTATION_OPERATION)
                indent()
                add("entity = %T,\n", entityDescriptorClass)
                add("mutationRuntime = client,\n")
                add("privacy = configuredPrivacy,\n")
                add("validation = configuredValidation,\n")
                add("ruleInput = { state: %T ->\n", preparedStateClass)
                indent()
                add("%T(\n", updateRuleInput)
                indent()
                add("state.before,\n")
                add("state.requestedPatch,\n")
                add("state.effectivePatch,\n")
                add("state.candidate,\n")
                add("state.edgeChanges,\n")
                unindent()
                add(")\n")
                unindent()
                add("},\n")
                add("adapter = %T(driver),\n", adapterClass)
                add("hooks = %T(\n", UPDATE_MUTATION_HOOKS)
                indent()
                add("converter = UpdateHookStateConverter(client),\n")
                add("beforeSave = configuredHooks.beforeSave,\n")
                add("beforeUpdate = configuredHooks.beforeUpdate,\n")
                add("afterUpdate = configuredHooks.afterUpdate,\n")
                unindent()
                add("),\n")
                unindent()
                add(")")
            })
        }
    }

    private fun buildHookStateConverterType(
        draftClass: ClassName,
        entityClass: ClassName,
        beforeSaveStateClass: ClassName,
        beforeUpdateStateClass: ClassName,
        clientClass: ClassName,
        pendingEdgeOpsClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): TypeSpec {
        val converterType = UPDATE_MUTATION_HOOK_STATE_CONVERTER.parameterizedBy(
            draftClass,
            entityClass,
            pendingEdgeOpsClass,
            beforeSaveStateClass,
            beforeUpdateStateClass,
        )
        return classType("UpdateHookStateConverter") {
            addModifiers(KModifier.PRIVATE)
            addSuperinterface(converterType)
            primaryConstructor {
                parameter("client", clientClass)
            }
            property("client", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            function("toBeforeSaveState", beforeSaveStateClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("draft", draftClass)
                statement("return draft._buildBeforeSaveState()")
            }
            function("toBeforeUpdateState", beforeUpdateStateClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("viewerContext", VIEWER_CONTEXT)
                parameter("before", entityClass)
                parameter("pendingEdges", pendingEdgeOpsClass)
                parameter("beforeSaveState", beforeSaveStateClass)
                addCode(codeBlock {
                    add("return %T(\n", beforeUpdateStateClass)
                    indent()
                    add("client = client.hookClientScopeForInternalUse,\n")
                    add("viewerContext = viewerContext,\n")
                    add("before = before,\n")
                    add("pendingEdges = pendingEdges,\n")
                    mutableFields.forEach { field ->
                        add("%L = beforeSaveState.%L,\n", field.apiName, field.apiName)
                    }
                    edgeFks.forEach { fk ->
                        add("%L = beforeSaveState.%L,\n", fk.propertyName, fk.propertyName)
                    }
                    unindent()
                    add(")\n")
                })
            }
        }
    }

    /** One schema-specific transaction lookup shared by all inherited bulk terminals. */
    private fun buildWithTransaction(repositoryBase: TypeName, schemaName: String, clientName: String): FunSpec {
        val names = NameAllocator()
        names.newName(schemaName)
        val result = TypeVariableName(names.newName("Result"))
        return function("withTransaction", TRANSACTION_RESULT.parameterizedBy(result)) {
            addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
            addTypeVariable(result)
            parameter(
                "block",
                LambdaTypeName.get(
                    receiver = TRANSACTION_SCOPE,
                    parameters = listOf(ParameterSpec.unnamed(repositoryBase)),
                    returnType = result,
                ),
            )
            statement("return client.withTransaction { tx -> block(tx.%N) }", clientName)
        }
    }

    /** Bind this entity's CREATE dependencies once for its scalar and bulk runtime operations. */
    private fun buildCreateOperationsProperty(
        createDraftClass: ClassName,
        entityClass: ClassName,
    ): PropertySpec {
        val operationType = CREATE_MUTATION_OPERATIONS.parameterizedBy(
            ClassName(packageName, "ReadOnlyEntClient"),
            createDraftClass,
            entityClass,
        )
        return property("createOperations", operationType) {
            addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
            initializer(codeBlock {
                add("buildCreateOperations(\n")
                indent()
                add("converter = createConverter,\n")
                add("privacy = configuredPrivacy,\n")
                add("validation = configuredValidation,\n")
                add("hookStateConverter = createConverter,\n")
                add("beforeSave = configuredHooks.beforeSave,\n")
                add("beforeCreate = configuredHooks.beforeCreate,\n")
                add("afterCreate = configuredHooks.afterCreate,\n")
                unindent()
                add(")")
            })
        }
    }

}
