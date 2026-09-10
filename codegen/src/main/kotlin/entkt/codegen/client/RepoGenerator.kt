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
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
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
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.toTypeName
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val ENT_CLIENT_NAME = "EntClient"
private val BUILD_CREATE_OPERATIONS =
    MemberName("entkt.runtime.mutation.execution", "buildCreateOperations")
private val BUILD_UPDATE_OPERATION =
    MemberName("entkt.runtime.mutation.execution", "buildUpdateOperation")
private val BUILD_DELETE_MUTATION_OPERATION =
    MemberName("entkt.runtime.mutation.execution", "buildDeleteMutationOperation")
private val BUILD_DELETE_MANY_MUTATION_OPERATION =
    MemberName("entkt.runtime.mutation.execution", "buildDeleteManyMutationOperation")
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
                // Supertype arguments use six spaces; expressions add their own nested indentation.
                add("\n")
                repeat(3) { indent() }
                add("entity = %T,\n", entityDescriptorClass)
                add("driver = driver,\n")
                add("mutationRuntime = client,\n")
                add("loadPrivacyRules = configuredPrivacy.loadRules,\n")
                add("createOperations = %L,\n", createOperationsExpression(schemaName))
                add("updateOperation = %L,\n", updateOperationExpression(schemaName))
                add("deleteOperation = %L,\n", deleteOperationExpression(schemaName, many = false))
                add("deleteManyOperation = %L,\n", deleteOperationExpression(schemaName, many = true))
                add("defaultUpdateConsistency = client.defaultUpdateConsistency,\n")
                add("defaultRelationshipLocking = client.defaultRelationshipLocking,\n")
                repeat(3) { unindent() }
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
            addFunction(buildQueryEntry(queryClass, clientRef = "client"))
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

    private fun updateOperationExpression(schemaName: String): CodeBlock {
        val adapterClass = ClassName(packageName, "${schemaName}UpdateAdapter")
        return codeBlock {
            add("%M(\n", BUILD_UPDATE_OPERATION)
            indent()
            add("entity = %T,\n", ClassName(packageName, "${schemaName}Descriptor"))
            add("mutationRuntime = client,\n")
            add("privacy = configuredPrivacy,\n")
            add("validation = configuredValidation,\n")
            add("ruleInput = { state: %T ->\n", adapterClass.nestedClass("PreparedState"))
            indent()
            add("%T(\n", ClassName(packageName, "${schemaName}UpdateRuleInput"))
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
            add("adapter = %T(driver, client.hookClientScopeForInternalUse),\n", adapterClass)
            add("beforeSave = configuredHooks.beforeSave,\n")
            add("beforeUpdate = configuredHooks.beforeUpdate,\n")
            add("afterUpdate = configuredHooks.afterUpdate,\n")
            unindent()
            add(")")
        }
    }

    private fun deleteOperationExpression(schemaName: String, many: Boolean): CodeBlock = codeBlock {
        add("%M(\n", if (many) BUILD_DELETE_MANY_MUTATION_OPERATION else BUILD_DELETE_MUTATION_OPERATION)
        indent()
        add("entity = %T,\n", ClassName(packageName, "${schemaName}Descriptor"))
        add("converter = %T,\n", ClassName(packageName, "${schemaName}DeleteConverter"))
        add("privacy = configuredPrivacy,\n")
        add("validation = configuredValidation,\n")
        add("ruleInput = ::%T,\n", ClassName(packageName, "${schemaName}DeleteRuleInput"))
        if (many) {
            add("driver = driver,\n")
            add("readExecutionHost = client,\n")
        }
        add("beforeDelete = configuredHooks.beforeDelete,\n")
        add("afterDelete = configuredHooks.afterDelete,\n")
        unindent()
        add(")")
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

    private fun createOperationsExpression(schemaName: String): CodeBlock = codeBlock {
        add("%M(\n", BUILD_CREATE_OPERATIONS)
        indent()
        add("entity = %T,\n", ClassName(packageName, "${schemaName}Descriptor"))
        add("mutationRuntime = client,\n")
        add("converter = %T(driver, client.hookClientScopeForInternalUse),\n", ClassName(packageName, "${schemaName}CreateConverter"))
        add("privacy = configuredPrivacy,\n")
        add("validation = configuredValidation,\n")
        add("beforeSave = configuredHooks.beforeSave,\n")
        add("beforeCreate = configuredHooks.beforeCreate,\n")
        add("afterCreate = configuredHooks.afterCreate,\n")
        unindent()
        add(")")
    }
}
