package entkt.codegen.mutation

import entkt.codegen.apiName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.NOTHING
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.columnName
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.assignedFieldName
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.fkPropertyKdoc
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.stagingFieldName
import entkt.codegen.metadata.toTypeName
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.ValidatorSpec

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val UUID_CLASS = ClassName("java.util", "UUID")
private val ENT_CLIENT_NAME = "EntClient"

// ── Shared canonical-mutation emission support ───────────────────────
// One home for the runtime type references and emission fragments every
// mutation-side emitter (CreateGenerator, UpdateGenerator /
// UpdateSaveEmitter, RepoGenerator) splices into generated terminals,
// so the MutationResult algebra — typed exception construction, the
// coordinator record on every Failed, driver-exception classification,
// and the CancellationException-rethrowing capture boundary — cannot
// drift between the create, update, and delete pipelines.

internal val MUTATION_RESULT = ClassName("entkt.runtime.result", "MutationResult")
internal val MUTATION_WRITE_STATE = ClassName("entkt.runtime.result", "MutationWriteState")
internal val ENT_MUTATION_EXCEPTION = ClassName("entkt.runtime.result", "EntMutationException")
internal val ENT_TARGET_ABSENT_EXCEPTION = ClassName("entkt.runtime.result", "EntTargetAbsentException")
internal val ENT_MUTATION_PRIVACY_DENIED_EXCEPTION =
    ClassName("entkt.runtime.result", "EntMutationPrivacyDeniedException")
internal val ENT_VALIDATION_EXCEPTION = ClassName("entkt.runtime.result", "EntValidationException")
internal val ENT_UNEXPECTED_MUTATION_EXCEPTION =
    ClassName("entkt.runtime.result", "EntUnexpectedMutationException")
internal val MUTATION_ENTITY_KEY = ClassName("entkt.runtime.result", "EntityKey")
internal val MUTATION_VALIDATION_VIOLATION = ClassName("entkt.runtime.result", "ValidationViolation")
internal val MUTATION_ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
internal val MUTATION_CANCELLATION_EXCEPTION = ClassName("java.util.concurrent", "CancellationException")
internal val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
internal val PREPARED_CREATE = ClassName("entkt.runtime.mutation", "PreparedCreate")
internal val RUN_BATCH_HOOKS_FOR_INTERNAL_USE =
    MemberName("entkt.runtime.hook", "runBatchHooksForInternalUse")

// The read-side emitters reference `kotlin.Exception` (see
// canonicalReadBody); the mutation side must use the SAME ClassName so
// a generated file containing both surfaces (the repo) doesn't force
// KotlinPoet into `java.lang.Exception as LangException` import
// aliasing.
internal val KOTLIN_EXCEPTION = ClassName("kotlin", "Exception")

/**
 * Re-emit [this] one indentation level deeper. Purely cosmetic for the
 * generated output: hand-assembled `add("... {\n")` fragments carry
 * their own literal indentation, so a shared fragment spliced inside a
 * nested block needs re-indenting to line up with its surroundings.
 */
internal fun CodeBlock.indented(): CodeBlock =
    CodeBlock.builder()
        .indent()
        .add(this)
        .unindent()
        .build()

/**
 * The `@file:OptIn(EntktInternal::class)` annotation every generated
 * mutation-side file carries so `MutationResult.failedForInternalUse`
 * (guarded by the error-level [EntktInternal] marker) is callable from
 * generated code compiled in the application module.
 */
internal fun entktInternalFileOptIn(): AnnotationSpec =
    AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
        .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
        .addMember("%T::class", ENTKT_INTERNAL)
        .build()

/**
 * Emission fragment: construct a typed [exceptionExpr] as `exception`,
 * record it on the transaction coordinator (no-op outside a
 * transaction scope), and return `MutationResult.failedForInternalUse`.
 * Every emitted `Failed` construction site goes through this shape so
 * an ignored failure still marks a caller-owned transaction
 * rollback-only. Must be spliced inside its own block scope (the
 * `exception` local is re-declared per site).
 */
internal fun recordAndReturnFailure(exceptionExpr: CodeBlock): CodeBlock =
    CodeBlock.builder()
        .add("val exception = ")
        .add(exceptionExpr)
        .add("\n")
        .add("client.recordTransactionMutationFailure(exception)\n")
        .add("return %T.failedForInternalUse(exception)\n", MUTATION_RESULT)
        .build()

/**
 * Emission fragment: a mutation-privacy denial produced from a
 * DECISION-RETURNING evaluator (never from a caught exception — rule
 * exceptions escape the evaluators and are classified as foreign at
 * the terminal boundary). [writeStateExpr] is the state at this
 * classification point (`MutationWriteState.NotPersisted` literal for
 * pre-write denials, the `writeState` phase local for post-write LOAD
 * disclosure). [entityKeyExpr] is `null` for pre-insert create denials
 * and an `EntityKey("id", ...)` expression when the key is known.
 */
internal fun privacyDeniedFailure(
    writeStateExpr: CodeBlock,
    schemaName: String,
    operationName: String,
    entityKeyExpr: CodeBlock,
    reasonExpr: String,
): CodeBlock =
    CodeBlock.builder()
        .add(
            recordAndReturnFailure(
                CodeBlock.builder()
                    .add("%T(", ENT_MUTATION_PRIVACY_DENIED_EXCEPTION)
                    .add(writeStateExpr)
                    .add(", %S, %T.%L, ", schemaName, MUTATION_ENT_OPERATION, operationName)
                    .add(entityKeyExpr)
                    .add(", %L)", reasonExpr)
                    .build(),
            ),
        )
        .build()

/**
 * Emission fragment: the catch tail for a single driver call inside a
 * mutation pipeline. Opens with `} catch` so the caller supplies the
 * `... = try {` head and the driver-call statement. Rethrows
 * `CancellationException`, then routes the exception through the
 * generated classifier member named by [classifierName] with [fallbackStateName]
 * as the phase-derived fallback: `NotPersisted` for pre-write reads
 * (owner load, delete reload), `PersistenceUnknown` for owner write
 * statements whose effect is unknown (never optimistic NotPersisted),
 * and `TransactionPending` for junction writes, which are
 * preflight-guaranteed to run inside a caller-owned transaction.
 */
internal fun driverCallFailureTail(
    fallbackStateName: String,
    classifierName: String = "_classifyDriverFailure",
): CodeBlock =
    CodeBlock.builder()
        .add("} catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
        .add("  throw e\n")
        .add("} catch (e: %T) {\n", KOTLIN_EXCEPTION)
        .add(
            "  val classified = %N(e, %T.%L)\n",
            classifierName,
            MUTATION_WRITE_STATE,
            fallbackStateName,
        )
        .add("  client.recordTransactionMutationFailure(classified)\n")
        .add("  return %T.failedForInternalUse(classified)\n", MUTATION_RESULT)
        .add("}\n")
        .build()

/**
 * Build the private driver-classification member named by [helperName]:
 * the driver-exception classification point for one entity + operation.
 * Consults [entkt.runtime.driver.Driver.classifyMutationException]; a
 * classification carrying a sealed [ENT_MUTATION_EXCEPTION] (a
 * recognized constraint violation or expected conflict, both hardcoded
 * NotPersisted) is used directly, any other classified exception is
 * null classification falls back to the caller's phase-derived
 * [MUTATION_WRITE_STATE] wrapped as an unexpected mutation failure —
 * the returned exception's own writeState IS the classification (no
 * parallel state field).
 */
internal fun buildClassifyDriverFailureHelper(
    schemaName: String,
    operationName: String,
    helperName: String = "_classifyDriverFailure",
): FunSpec =
    FunSpec.builder(helperName)
        .addModifiers(KModifier.PRIVATE)
        .addParameter("e", KOTLIN_EXCEPTION)
        .addParameter("fallback", MUTATION_WRITE_STATE)
        .returns(ENT_MUTATION_EXCEPTION)
        .addCode(
            CodeBlock.builder()
                .add(
                    "return driver.classifyMutationException(e, %S, %T.%L)\n",
                    schemaName, MUTATION_ENT_OPERATION, operationName,
                )
                .add("  ?: %T(fallback, e)\n", ENT_UNEXPECTED_MUTATION_EXCEPTION)
                .build(),
        )
        .build()

/**
 * Build the private `_validationFailed(violations)` member shared by
 * the inline normalize-phase checks (required fields, field
 * validators) and the rule-evaluator call sites. Constructs the typed
 * [ENT_VALIDATION_EXCEPTION] (hardcoded NotPersisted), records it on
 * the coordinator, and returns the Failed result. `MutationResult<Nothing>`
 * so a `return _validationFailed(...)` type-checks in any terminal.
 */
internal fun buildValidationFailedHelper(schemaName: String, operationName: String): FunSpec =
    FunSpec.builder("_validationFailed")
        .addModifiers(KModifier.PRIVATE)
        .addParameter("violations", List::class.asClassName().parameterizedBy(MUTATION_VALIDATION_VIOLATION))
        .returns(MUTATION_RESULT.parameterizedBy(NOTHING))
        .addStatement(
            "val exception = %T(%S, %T.%L, violations)",
            ENT_VALIDATION_EXCEPTION, schemaName, MUTATION_ENT_OPERATION, operationName,
        )
        .addStatement("client.recordTransactionMutationFailure(exception)")
        .addStatement("return %T.failedForInternalUse(exception)", MUTATION_RESULT)
        .build()

internal class CreateGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Create"
        // Backing FK columns are emitted via `edgeFks` so they pick up
        // relationship-FK semantics (non-null type, throw-on-unassigned,
        // setter null-reject). Don't double-emit them as scalar fields.
        val allFields = scalarFields(schema)
        val mutableFields = allFields.filter { !it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val entityClass = ClassName(packageName, schemaName)
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val createMutationViewClass = ClassName(packageName, "${schemaName}CreateMutationView")
        val createHookCtxClass = ClassName(packageName, "${schemaName}CreateHookContext")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)

        val beforeSaveHookType = hookListType(mutationClass)
        // beforeCreate hooks receive the restricted CreateHookContext —
        // a `mutation` view (no save()/driver/hook-list/staging surface)
        // plus `client` for DB queries. Mirrors the update side's
        // UpdateHookContext.
        val beforeCreateHookType = hookListType(createHookCtxClass)
        val afterCreateHookType = hookListType(entityClass)

        val idStrategy = idStrategyName(schema)
        val idType = schema.id().type.toTypeName()

        val constructorBuilder = FunSpec.constructorBuilder()
            .addParameter("driver", DRIVER)
            .addParameter("client", clientClass)
            .addParameter("beforeSaveHooks", beforeSaveHookType)
            .addParameter("beforeCreateHooks", beforeCreateHookType)
            .addParameter("afterCreateHooks", afterCreateHookType)
        if (idStrategy == "EXPLICIT") {
            constructorBuilder.addParameter("id", idType)
        }

        // `CreateMutationView` transitively requires `Mutation`, so the
        // single addSuperinterface here gives Create both contracts.
        val typeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addSuperinterface(createMutationViewClass)
            .primaryConstructor(constructorBuilder.build())
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("client", clientClass)
                    .initializer("client")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("beforeSaveHooks", beforeSaveHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("beforeSaveHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("beforeCreateHooks", beforeCreateHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("beforeCreateHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("afterCreateHooks", afterCreateHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("afterCreateHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("_managedByCreateMany", BOOLEAN)
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("false")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("_managedSaveFailure", ENT_MUTATION_EXCEPTION.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "_managedSaveFailures",
                    MUTABLE_LIST.parameterizedBy(ENT_MUTATION_EXCEPTION).copy(nullable = true),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build(),
            )
            .also { builder ->
                if (idStrategy == "EXPLICIT") {
                    builder.addProperty(
                        PropertySpec.builder("id", idType)
                            .initializer("id")
                            .build()
                    )
                }
            }
            // Immutable scalar fields are declared on `CreateMutationView`
            // (they're create-only writable), so both mutable and immutable
            // scalars override the same view contract.
            .addProperties(mutableFields.map { buildProperty(it) })
            .addProperties(allFields.filter { it.immutable }.map { buildProperty(it) })
            .also { builder ->
                for (fk in edgeFks) {
                    if (fk.required) {
                        builder.addProperty(buildRequiredFkStagingProperty(fk))
                    }
                    // Nullable FK with a default needs an "assigned" flag
                    // so explicit-null assignment can suppress the default
                    // ( explicit-null-wins for nullable field-backed FKs).
                    if (!fk.required && fk.default != null) {
                        builder.addProperty(buildAssignedFlag(fk))
                    }
                }
            }
            .addProperties(edgeFks.map { buildEdgeFkProperty(it, override = true) })
            // create-hook adapter: private hook-facing adapters. `_beforeSaveView`
            // implements ONLY `${Schema}Mutation` for `beforeSave`
            // hooks; `_createMutationView` implements
            // `${Schema}CreateMutationView` for `beforeCreate`
            // hooks via `ctx.mutation`. Both forward to the outer
            // `${Schema}Create` builder. A hook attempting
            // `arg as ${Schema}Create` (or `ctx.mutation as` any
            // other view it shouldn't reach) fails at runtime,
            // matching the runtime-enforced contract the update
            // path has had since transaction locking and link-table M2M helpers.
            .addProperty(
                // _beforeSaveView implements ${Schema}Mutation, which
                // declares ONLY mutable scalar fields + mutable FKs
                // (immutable FKs live on the CreateMutationView side
                // — they're create-only writable). Filter edgeFks
                // to mutables here so the anonymous-object body
                // doesn't emit `override var` for FK properties the
                // interface doesn't declare — Kotlin rejects those
                // with "overrides nothing."
                buildBeforeSaveAdapterProperty(
                    schemaName,
                    mutationClass,
                    mutableFields,
                    edgeFks.filter { !it.immutable },
                ),
            )
            .addProperty(
                buildCreateMutationViewAdapterProperty(
                    schemaName,
                    createMutationViewClass,
                    allFields,
                    edgeFks,
                ),
            )
            .addFunction(buildBeforeSaveHookValueForInternalUseFunction(mutationClass))
            .addFunction(buildBeforeCreateHookValueForInternalUseFunction(createHookCtxClass))
            .addFunction(buildConfigureForCreateManyForInternalUseFunction(schemaName))
            .addFunction(buildPrepareForInternalUseFunction(schemaName, schema, allFields, edgeFks))
            .addFunction(buildExecuteSaveFunction(schemaName, schema.clientName))
            .addFunction(buildSaveFunction(schemaName))
            .addFunction(buildSaveAndLoadFunction(schemaName))
            .addFunction(buildValidationFailedHelper(schemaName, "CREATE"))
            .addFunction(buildClassifyDriverFailureHelper(schemaName, "CREATE"))
            .build()

        // The generated builder constructs `MutationResult.Failed`
        // through the `@EntktInternal`-guarded `failedForInternalUse`
        // factory; the file-level OptIn consumes the requirement at the
        // construction sites without propagating it to application code.
        return FileSpec.builder(packageName, className)
            .addAnnotation(entktInternalFileOptIn())
            .addType(typeSpec)
            .build()
    }

    /**
     * create-hook adapter: private `_beforeSaveView` adapter that implements
     * ONLY `${Schema}Mutation` — the shared writable surface for
     * `beforeSave` hooks across create + update. Excludes
     * immutable-scalar / immutable-FK setters (those are part of
     * the `${Schema}CreateMutationView` surface, which is the
     * create-phase-specific view passed to `beforeCreate`), and
     * has no `${Schema}Create`-specific members like
     * `save()` / `driver` / `client` / hook lists.
     *
     * Mirrors [UpdateGenerator.buildBeforeSaveAdapterProperty] —
     * both create and update saves hand `beforeSave` hooks the
     * same `Mutation`-typed adapter shape, so a hook registered
     * via `onBeforeSave` sees the same restricted surface
     * regardless of the operation phase.
     */
    private fun buildBeforeSaveAdapterProperty(
        schemaName: String,
        mutationClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): PropertySpec {
        val createClassName = "${schemaName}Create"
        val adapter = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(mutationClass)
        for (field in mutableFields) {
            val propName = field.apiName
            val typeName = field.resolvedTypeName().copy(nullable = true)
            adapter.addProperty(buildAdapterForwarderProperty(createClassName, propName, typeName))
        }
        for (fk in edgeFks) {
            // FK type on the Mutation interface follows
            // relationship nullability — required FKs are non-null.
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            adapter.addProperty(buildAdapterForwarderProperty(createClassName, fk.propertyName, typeName))
        }
        return PropertySpec.builder("_beforeSaveView", mutationClass)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%L", adapter.build())
            .build()
    }

    /**
     * create-hook adapter: private `_createMutationView` adapter that
     * implements `${Schema}CreateMutationView` — the
     * create-phase view a `beforeCreate` hook sees through
     * `ctx.mutation`. Forwards every scalar (mutable AND
     * immutable, since immutables are create-only writable
     * and surface on the create view) and every FK (mutable AND
     * immutable for the same reason) to the outer
     * `${Schema}Create` builder.
     *
     * Replaces V0's "hand the concrete builder typed as the
     * view" pattern. A `beforeCreate` hook that attempts
     * `ctx.mutation as ${Schema}Create` now throws
     * `ClassCastException` at runtime — the adapter only
     * implements the view interface.
     */
    private fun buildCreateMutationViewAdapterProperty(
        schemaName: String,
        createMutationViewClass: ClassName,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): PropertySpec {
        val createClassName = "${schemaName}Create"
        val adapter = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(createMutationViewClass)
        // Mutable scalars (inherited from Mutation) + immutable
        // scalars (declared on CreateMutationView itself). The
        // generated `${Schema}Create` class declares both as
        // `var ... = null` properties — the forwarder reads /
        // writes through them.
        for (field in allFields) {
            val propName = field.apiName
            val typeName = field.resolvedTypeName().copy(nullable = true)
            adapter.addProperty(buildAdapterForwarderProperty(createClassName, propName, typeName))
        }
        for (fk in edgeFks) {
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            adapter.addProperty(buildAdapterForwarderProperty(createClassName, fk.propertyName, typeName))
        }
        return PropertySpec.builder("_createMutationView", createMutationViewClass)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%L", adapter.build())
            .build()
    }

    /** Hook-facing mutation value used by both scalar and batch execution. */
    private fun buildBeforeSaveHookValueForInternalUseFunction(mutationClass: ClassName): FunSpec =
        FunSpec.builder("beforeSaveHookValueForInternalUse")
            .addAnnotation(ENTKT_INTERNAL)
            .addModifiers(KModifier.INTERNAL)
            .returns(mutationClass)
            .addStatement("return _beforeSaveView")
            .build()

    /** Hook context used by both scalar and batch execution. */
    private fun buildBeforeCreateHookValueForInternalUseFunction(createHookCtxClass: ClassName): FunSpec =
        FunSpec.builder("beforeCreateHookValueForInternalUse")
            .addAnnotation(ENTKT_INTERNAL)
            .addModifiers(KModifier.INTERNAL)
            .returns(createHookCtxClass)
            .addStatement(
                "return %T(client.hookClientScopeForInternalUse, _createMutationView)",
                createHookCtxClass,
            )
            .build()

    /**
     * Permanently mark a builder as owned by createMany before applying its
     * configuration block. The marker also prevents a later block or escaped
     * reference from persisting this builder independently. A same-builder
     * save attempt is returned through MutationResult even when the block
     * ignored or projected it. Every builder in the logical batch shares one
     * encounter-ordered failure list so cross-block attempts retain the same
     * primary failure as the transaction coordinator.
     */
    private fun buildConfigureForCreateManyForInternalUseFunction(schemaName: String): FunSpec {
        val createClass = ClassName(packageName, "${schemaName}Create")
        val blockType = LambdaTypeName.get(receiver = createClass, returnType = UNIT)
        return FunSpec.builder("configureForCreateManyForInternalUse")
            .addAnnotation(ENTKT_INTERNAL)
            .addModifiers(KModifier.INTERNAL)
            .addParameter("block", blockType)
            .addParameter(
                "managedSaveFailures",
                MUTABLE_LIST.parameterizedBy(ENT_MUTATION_EXCEPTION),
            )
            .returns(MUTATION_RESULT.parameterizedBy(createClass))
            .addStatement("check(!_managedByCreateMany) { %S }", "Builder is already managed by createMany")
            .addStatement("_managedByCreateMany = true")
            .addStatement("_managedSaveFailures = managedSaveFailures")
            .beginControlFlow("try")
            .addStatement("apply(block)")
            .nextControlFlow("catch (e: %T)", MUTATION_CANCELLATION_EXCEPTION)
            .addStatement("throw e")
            .nextControlFlow("catch (e: %T)", KOTLIN_EXCEPTION)
            .addStatement("val failure = _managedSaveFailure ?: throw e")
            .beginControlFlow("if (e !== failure && failure.suppressed.none { it === e })")
            .addStatement("failure.addSuppressed(e)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("val failure = _managedSaveFailure")
            .beginControlFlow("if (failure != null)")
            .addStatement("client.recordTransactionMutationFailure(failure)")
            .addStatement("return %T.failedForInternalUse(failure)", MUTATION_RESULT)
            .endControlFlow()
            .addStatement("return %T.Success(this)", MUTATION_RESULT)
            .build()
    }

    /**
     * Resolve defaults, required values, inline field validators, the
     * driver row map, and the write candidate exactly once. Hooks,
     * privacy, entity-level validation, and I/O deliberately live in
     * the caller so createMany can evaluate each lifecycle phase over
     * the whole input batch.
     */
    private fun buildPrepareForInternalUseFunction(
        schemaName: String,
        schema: EntSchema,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val candidateType = ClassName(packageName, "${schemaName}WriteCandidate")
        val preparedType = PREPARED_CREATE.parameterizedBy(candidateType)
        val builder = FunSpec.builder("prepareForInternalUse")
            .addAnnotation(ENTKT_INTERNAL)
            .addModifiers(KModifier.INTERNAL)
            .returns(MUTATION_RESULT.parameterizedBy(preparedType))

        emitCreatePreparation(builder, schemaName, schema, allFields, edgeFks)
        val candidateArgs = buildCandidateArgs(allFields, edgeFks)
        builder.addStatement(
            "val candidate = %T(${candidateArgs.joinToString(", ")})",
            candidateType,
        )
        builder.addStatement(
            "return %T.Success(%T(values, candidate))",
            MUTATION_RESULT,
            PREPARED_CREATE,
        )
        return builder.build()
    }

    /**
     * One forwarder property on a Create-side adapter. Reads /
     * writes go to the outer `${Schema}Create` instance via
     * `this@${createClassName}.${prop}`. The forwarder property
     * is `override var` because every member declared on the
     * generated view interfaces is a mutable property.
     */
    private fun buildAdapterForwarderProperty(
        createClassName: String,
        prop: String,
        typeName: com.squareup.kotlinpoet.TypeName,
    ): PropertySpec {
        return PropertySpec.builder(prop, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .getter(
                FunSpec.getterBuilder()
                    .addStatement("return this@%L.%L", createClassName, prop)
                    .build(),
            )
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("this@%L.%L = value", createClassName, prop)
                    .build(),
            )
            .build()
    }

    private fun buildProperty(field: Field): PropertySpec {
        val typeName = field.resolvedTypeName().copy(nullable = true)
        val builder = PropertySpec.builder(field.apiName, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .initializer("null")
        val comment = field.comment
        if (comment != null) builder.addKdoc("%L", comment)
        return builder.build()
    }

    private fun buildEdgeFkProperty(fk: EdgeFk, override: Boolean): PropertySpec {
        if (fk.required) {
            // Required FKs:
            //   - public property is non-null typed (by contract "Public Types")
            //   - private nullable staging field holds the value until assigned
            //   - getter throws on unassigned read (by contract "Resolved FK
            //     Getter Behavior"); a hook that reads `m.authorId` before
            //     assigning fails fast
            //   - setter rejects null at entry so Java/platform callers
            //     can't bypass the non-null contract
            val nonNullType = fk.idType.toTypeName().copy(nullable = false)
            val stagingName = stagingFieldName(fk.propertyName)
            val builder = PropertySpec.builder(fk.propertyName, nonNullType)
                .mutable(true)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement(
                            "return %L ?: throw IllegalStateException(%S)",
                            stagingName,
                            "${fk.propertyName} is required",
                        )
                        .build(),
                )
                .setter(
                    FunSpec.setterBuilder()
                        .addParameter("value", nonNullType)
                        .addAnnotation(
                            AnnotationSpec.builder(Suppress::class)
                                .addMember("%S", "SENSELESS_COMPARISON")
                                .build(),
                        )
                        .addStatement(
                            "requireNotNull(value) { %S }",
                            "${fk.propertyName} is required",
                        )
                        .addStatement("%L = value", stagingName)
                        .build(),
                )
            if (override) builder.addModifiers(KModifier.OVERRIDE)
            builder.addKdoc("%L", fkPropertyKdoc(fk))
            return builder.build()
        }
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        val builder = PropertySpec.builder(fk.propertyName, typeName)
            .mutable(true)
            .initializer("null")
        if (override) builder.addModifiers(KModifier.OVERRIDE)
        builder.addKdoc("%L", fkPropertyKdoc(fk))
        // Nullable + default: the setter flips the assigned flag so the
        // save body can distinguish "untouched (default fires)" from
        // "explicitly assigned null (default suppressed)". Without a
        // default, the default getter/setter are fine — there is no
        // explicit-null-vs-untouched distinction to draw on create.
        if (fk.default != null) {
            builder.setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("field = value")
                    .addStatement("%L = true", assignedFieldName(fk.propertyName))
                    .build(),
            )
        }
        return builder.build()
    }

    private fun buildRequiredFkStagingProperty(fk: EdgeFk): PropertySpec {
        val nullableType = fk.idType.toTypeName().copy(nullable = true)
        return PropertySpec.builder(stagingFieldName(fk.propertyName), nullableType)
            .addModifiers(KModifier.PRIVATE)
            .mutable(true)
            .initializer("null")
            .build()
    }

    private fun buildAssignedFlag(fk: EdgeFk): PropertySpec {
        return PropertySpec.builder(assignedFieldName(fk.propertyName), Boolean::class)
            .addModifiers(KModifier.PRIVATE)
            .mutable(true)
            .initializer("false")
            .build()
    }

    /**
     * The single create execution pipeline both public terminals
     * project. Runs the full save order — transaction-requirement
     * preflight, beforeSave/beforeCreate hooks, normalization
     * (defaults + required checks + inline field validators), CREATE
     * privacy, CREATE validation, `driver.insert`, `fromRow`
     * materialization, afterCreate hooks — and classifies every
     * failure positionally into a typed [ENT_MUTATION_EXCEPTION]
     * carrying the phase-accurate [MUTATION_WRITE_STATE]:
     *
     *  - preflight / hook / rule-thrown / materialization exceptions →
     *    `EntUnexpectedMutationException` with the current phase state
     *    (the terminal boundary rethrows `CancellationException` and
     *    never catches `Throwable`);
     *  - a rule-RETURNED privacy Deny (or the fail-closed end of the
     *    rule list) → `EntMutationPrivacyDeniedException(NotPersisted,
     *    CREATE, entityKey = null)` — pre-insert there is no key to
     *    report, and emitters must not reorder id minting to fabricate
     *    one;
     *  - required-field / validator / rule-returned Invalids →
     *    `EntValidationException` (hardcoded NotPersisted);
     *  - `driver.insert` exceptions → `_classifyDriverFailure` with
     *    the write-phase `PersistenceUnknown` fallback;
     *  - after successful insert the state flips to
     *    `TransactionPending` (transaction-scoped driver) or
     *    `Committed` (autocommit).
     *
     * When [applyLoadPrivacy] is set (the `saveAndLoad()` projection)
     * the returned entity additionally passes the LOAD disclosure
     * check; a denial is `EntMutationPrivacyDeniedException` with
     * `operation = LOAD` and the current post-write state. `save()`
     * skips the check because it discloses no entity.
     *
     * The repo's phase-major `createMany` does not call this scalar
     * terminal. It shares the guarded hook-value and preparation seams
     * above, then evaluates each lifecycle phase across the full batch.
     *
     * The driver minting strategy decides how the id is produced:
     * - `CLIENT_UUID`: we mint a `UUID` here so the caller can see it
     *   before the round trip.
     * - `AUTO_INT` / `AUTO_LONG`: we omit `id` and let the driver pick.
     * - `EXPLICIT`: the caller passed `id` to `create(id) { ... }`.
     *
     * After insert, the driver returns the persisted row (including
     * the assigned id), which we feed into `fromRow` to hydrate a
     * typed entity — no inconsistency between the row we sent and the
     * row the driver actually stored.
     */
    private fun buildExecuteSaveFunction(
        schemaName: String,
        clientName: String,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val repoPropName = clientName
        val builder = FunSpec.builder("executeSaveForInternalUse")
            .addModifiers(KModifier.INTERNAL)
            // `internal` alone is no guard: generated and application
            // sources share the app module, so without the error-level
            // opt-in a caller could take the entity without
            // saveAndLoad()'s LOAD disclosure check.
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addParameter("applyLoadPrivacy", BOOLEAN)
            .returns(MUTATION_RESULT.parameterizedBy(entityClass))

        builder.addStatement("var writeState = %T.NotPersisted", MUTATION_WRITE_STATE)
        builder.beginControlFlow("try")
        builder.beginControlFlow("if (_managedByCreateMany)")
        builder.addStatement("val existing = _managedSaveFailure")
        builder.beginControlFlow("val exception = if (existing != null)")
        builder.addStatement("existing")
        builder.nextControlFlow("else")
        builder.addStatement(
            "val created = %T(%T.NotPersisted, %T(%S))",
            ENT_UNEXPECTED_MUTATION_EXCEPTION,
            MUTATION_WRITE_STATE,
            IllegalStateException::class,
            "A builder managed by createMany cannot be persisted independently; let createMany execute the batch",
        )
        builder.addStatement("_managedSaveFailure = created")
        builder.addStatement(
            "checkNotNull(_managedSaveFailures) { %S }.add(created)",
            "createMany-managed builder is missing its batch failure tracker",
        )
        builder.addStatement("created")
        builder.endControlFlow()
        builder.addStatement("client.recordTransactionMutationFailure(exception)")
        builder.addStatement("return %T.failedForInternalUse(exception)", MUTATION_RESULT)
        builder.endControlFlow()
        // Posture snapshot BEFORE persistence but INSIDE the terminal
        // boundary: a posture read that failed after a successful
        // write would otherwise let the boundary misreport a committed
        // write as NotPersisted — and a posture read that fails here,
        // before any work, is captured as Failed(NotPersisted) rather
        // than escaping the algebra.
        builder.addStatement(
            "val postWriteState = if (driver.inTransaction) %T.TransactionPending else %T.Committed",
            MUTATION_WRITE_STATE, MUTATION_WRITE_STATE,
        )

        // ---- Transaction-requirement preflight (transaction locking).
        // This stays ahead of every observable phase, including hooks
        // and default evaluation. ----
        builder.addStatement("client.checkTransactionRequirement(%S)", "$schemaName create")

        // ---- Lifecycle hooks. Both scalar and createMany execution
        // obtain their hook values through the same guarded accessors,
        // so the restricted adapter contract cannot drift. ----
        builder.addStatement(
            "%M(listOf(beforeSaveHookValueForInternalUse()), beforeSaveHooks)",
            RUN_BATCH_HOOKS_FOR_INTERNAL_USE,
        )
        builder.addStatement(
            "%M(listOf(beforeCreateHookValueForInternalUse()), beforeCreateHooks)",
            RUN_BATCH_HOOKS_FOR_INTERNAL_USE,
        )

        // ---- Normalize once. `prepareForInternalUse` owns defaults,
        // required / inline field validation, the row map, and the
        // candidate. A typed validation failure is already recorded by
        // `_validationFailed`; propagate it unchanged. ----
        builder.beginControlFlow("val prepared = when (val result = prepareForInternalUse())")
        builder.addStatement("is %T.Success -> result.value", MUTATION_RESULT)
        builder.addStatement("is %T.Failed -> return result", MUTATION_RESULT)
        builder.endControlFlow()
        builder.addStatement("val values = prepared.values")
        builder.addStatement("val candidate = prepared.candidate")

        emitCreatePrivacy(builder, schemaName, clientName)
        emitCreateValidation(builder, schemaName, clientName)

        // ---- Persist. The insert is the only write statement on the
        // create path; an exception here routes through driver
        // classification with the PersistenceUnknown write-phase
        // fallback (never optimistic NotPersisted). ----
        builder.addCode(
            CodeBlock.builder()
                .add("val row = try {\n")
                .add("  driver.insert(%T.TABLE, values)\n", entityClass)
                .add(driverCallFailureTail("PersistenceUnknown"))
                .build(),
        )
        builder.addStatement(
            "writeState = postWriteState",
        )
        builder.addStatement("val entity = %T.fromRow(row)", entityClass)
        builder.addStatement("%M(listOf(entity), afterCreateHooks)", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)

        // ---- Returned-entity LOAD disclosure (saveAndLoad only). The
        // write has already succeeded; a denial reports the post-write
        // state with operation = LOAD so callers can tell "write
        // happened but you can't see it" from a pre-write rejection. ----
        builder.beginControlFlow("if (applyLoadPrivacy)")
        builder.addStatement("val denial = client.%L.loadDenialOrNull(privacy, entity)", repoPropName)
        builder.beginControlFlow("if (denial != null)")
        builder.addCode(
            privacyDeniedFailure(
                writeStateExpr = CodeBlock.of("writeState"),
                schemaName = schemaName,
                operationName = "LOAD",
                entityKeyExpr = CodeBlock.of("%T(%S, entity.id)", MUTATION_ENTITY_KEY, "id"),
                reasonExpr = "denial.reason",
            ),
        )
        builder.endControlFlow()
        builder.endControlFlow()

        builder.addStatement("return %T.Success(entity)", MUTATION_RESULT)

        // ---- Whole-terminal capture boundary. ----
        builder.nextControlFlow("catch (e: %T)", MUTATION_CANCELLATION_EXCEPTION)
        builder.addStatement("throw e")
        builder.nextControlFlow("catch (e: %T)", KOTLIN_EXCEPTION)
        builder.addCode(
            recordAndReturnFailure(
                CodeBlock.of("%T(writeState, e)", ENT_UNEXPECTED_MUTATION_EXCEPTION),
            ),
        )
        builder.endControlFlow()

        return builder.build()
    }

    /**
     * `save(): MutationResult<Unit>` — acknowledgement-only projection
     * of the shared create pipeline. No parallel implementation: the
     * projection maps [executeSaveForInternalUse]'s result and
     * performs no I/O of its own.
     */
    private fun buildSaveFunction(schemaName: String): FunSpec {
        return FunSpec.builder("save")
            .addKdoc(
                "Persist this builder's state. `Success(Unit)` means the insert\n" +
                    "completed — staged when executed through a transaction-scoped\n" +
                    "client, committed otherwise. `Failed` carries a typed\n" +
                    "[entkt.runtime.result.EntMutationException] whose `writeState`\n" +
                    "records what EntKt knows about the database effect; `Failed` does\n" +
                    "NOT imply the write rolled back. `save()` does not apply\n" +
                    "returned-entity LOAD privacy because it discloses no entity — use\n" +
                    "[saveAndLoad] to materialize the created row. A builder owned by\n" +
                    "`createMany` cannot be persisted independently; such\n" +
                    "an attempt returns `Failed` before lifecycle work or I/O. During\n" +
                    "batch configuration it also fails the enclosing batch even when\n" +
                    "its result is ignored. There is\n" +
                    "deliberately no `orNull()` projection (null cannot distinguish rejection,\n" +
                    "committed failure, and unknown write state); project with\n" +
                    "`getOrThrow()` or match on the result.",
            )
            .returns(MUTATION_RESULT.parameterizedBy(UNIT))
            .addCode(
                CodeBlock.builder()
                    .add("return when (val result = executeSaveForInternalUse(applyLoadPrivacy = false)) {\n")
                    .add("  is %T.Success -> %T.Success(Unit)\n", MUTATION_RESULT, MUTATION_RESULT)
                    .add("  is %T.Failed -> result\n", MUTATION_RESULT)
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * `saveAndLoad(): MutationResult<Entity>` — same single pipeline
     * as [save], additionally materializing the created entity and
     * applying the ordinary post-write LOAD disclosure check.
     */
    private fun buildSaveAndLoadFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        return FunSpec.builder("saveAndLoad")
            .addKdoc(
                "Persist this builder's state and return the materialized entity\n" +
                    "under the ordinary LOAD contract. `Success` always carries a\n" +
                    "non-null entity. A LOAD denial of the returned entity is\n" +
                    "`Failed(EntMutationPrivacyDeniedException)` with\n" +
                    "`operation = LOAD` and the current post-write `writeState`. The\n" +
                    "denial itself does not undo the insert: outside a transaction the\n" +
                    "row is already committed (`Committed`); inside a caller-owned\n" +
                    "transaction the failure is `TransactionPending` and marks the\n" +
                    "scope rollback-only, so the enclosing transaction normally rolls\n" +
                    "the write back at its boundary.\n" +
                    "As with [save], `Failed` does not imply rollback (inspect\n" +
                    "`exception.writeState`), and there is no `orNull()` projection;\n" +
                    "project with `getOrThrow()` or match on the result.",
            )
            .returns(MUTATION_RESULT.parameterizedBy(entityClass))
            .addStatement("return executeSaveForInternalUse(applyLoadPrivacy = true)")
            .build()
    }

    /**
     * Emit the pure preparation body shared by scalar and batch create:
     * field extraction with defaults, required / inline field
     * validation, FK resolution, and the driver `values` map. Hooks,
     * entity rules, privacy, and I/O are deliberately absent.
     */
    private fun emitCreatePreparation(
        builder: FunSpec.Builder,
        schemaName: String,
        schema: EntSchema,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val idStrategy = idStrategyName(schema)
        val preparedValueNames = preparedCreateValueNames(allFields, edgeFks)

        // ---- Validate and bind each property to a local. ----
        // Required fields produce a typed EntValidationException
        // (via the `_validationFailed` helper, which records the
        // failure on the transaction coordinator). Short-circuits on
        // the first missing required field — collecting all violations
        // across required + validator rules is left as a future
        // improvement.
        for (field in allFields) {
            val prop = field.apiName
            val required = !field.nullable && field.default == null
            when {
                required -> builder.addStatement(
                    "val %L = this.%L ?: return·_validationFailed(listOf(%T(%S, field = %S)))",
                    preparationLocal(prop),
                    prop,
                    MUTATION_VALIDATION_VIOLATION,
                    "$prop is required",
                    prop,
                )
                field.default != null -> builder.addStatement(
                    "val %L = this.%L ?: %L",
                    preparationLocal(prop),
                    prop,
                    defaultCodeBlock(field),
                )
                else -> builder.addStatement("val %L = this.%L", preparationLocal(prop), prop)
            }
        }

        // ---- Field-level validation. ----
        for (field in allFields) {
            if (field.validators.isEmpty()) continue
            val prop = field.apiName
            val nullable = field.nullable
            emitFieldValidation(
                builder, schemaName, preparationLocal(prop), prop, field.validators, nullable,
            )
        }

        for (fk in edgeFks) {
            when {
                // Required + default: read staging directly so an unset
                // value falls back to the default instead of throwing
                // via the public non-null getter.
                fk.required && fk.default != null -> builder.addStatement(
                    "val %L = this.%L ?: %L",
                    preparationLocal(fk.propertyName),
                    stagingFieldName(fk.propertyName),
                    fkDefaultCodeBlock(fk),
                )
                // Nullable + default: explicit-null-wins (by contract). The
                // setter flips `_<prop>Assigned`, so any explicit write
                // — including `null` — suppresses the default.
                !fk.required && fk.default != null -> builder.addStatement(
                    "val %L = if (this.%L) this.%L else %L",
                    preparationLocal(fk.propertyName),
                    assignedFieldName(fk.propertyName),
                    fk.propertyName,
                    fkDefaultCodeBlock(fk),
                )
                // Required + no default: read staging directly so the
                // missing-input failure is a typed EntValidationException
                // rather than the property getter's
                // IllegalStateException. The property getter stays as
                // ISE for hook/property reads, where an early read is a
                // usage error.
                fk.required -> builder.addStatement(
                    "val %L = this.%L ?: return·_validationFailed(listOf(%T(%S, field = %S)))",
                    preparationLocal(fk.propertyName),
                    stagingFieldName(fk.propertyName),
                    MUTATION_VALIDATION_VIOLATION,
                    "${fk.propertyName} is required",
                    fk.propertyName,
                )
                // Nullable + no default: pass through, may be null.
                else -> builder.addStatement(
                    "val %L = this.%L", preparationLocal(fk.propertyName), fk.propertyName,
                )
            }
            // Field-level validators carried from the backing field of
            // a field-backed edge run on the resolved FK value, the
            // same way scalar field validators do above.
            if (fk.validators.isNotEmpty()) {
                emitFieldValidation(
                    builder,
                    schemaName = schemaName,
                    prop = preparationLocal(fk.propertyName),
                    fieldName = fk.propertyName,
                    validators = fk.validators,
                    nullable = !fk.required,
                )
            }
        }

        // Detach mutable caller-owned values once at the preparation boundary.
        // Both the driver row and the base WriteCandidate use this same stable
        // value; per-rule snapshots are copied again by the repo evaluator.
        // A callback that retained the caller's original array / JSON graph
        // therefore cannot change a later rule's input or the pending write.
        val entityClass = ClassName(packageName, schemaName)
        for (field in allFields) {
            if (field.type != FieldType.BYTES && field.type != FieldType.JSON) continue
            val prop = field.apiName
            val prepared = preparedValueNames.getValue(field)
            if (field.type == FieldType.BYTES) {
                val nullableAccess = if (field.nullable) "?" else ""
                builder.addStatement(
                    "val %L = %L$nullableAccess.copyOf()", prepared, preparationLocal(prop),
                )
            } else {
                builder.addStatement(
                    "val %L = driver.copyJsonValue(%T.TABLE, %S, %L)",
                    prepared,
                    entityClass,
                    field.columnName,
                    preparationLocal(prop),
                )
            }
        }

        // ---- Build the row map. ----
        val rowBuilder = CodeBlock.builder()
            .add("val values: Map<String, Any?> = mapOf(\n")

        if (idStrategy == "CLIENT_UUID") {
            rowBuilder.add("  %S to %T.randomUUID(),\n", "id", UUID_CLASS)
        } else if (idStrategy == "EXPLICIT") {
            rowBuilder.add("  %S to id,\n", "id")
        }

        for (field in allFields) {
            val prop = field.apiName
            val preparedProp = preparedValueNames.getValue(field)
            val col = field.columnName
            if (field.type == FieldType.ENUM) {
                val nullable = field.nullable
                if (nullable) {
                    rowBuilder.add("  %S to %L?.name,\n", col, preparationLocal(prop))
                } else {
                    rowBuilder.add("  %S to %L.name,\n", col, preparationLocal(prop))
                }
            } else if (field.type == FieldType.PGVECTOR) {
                // Validate the vector's dimension at save() build time, with a
                // field-named error (the driver re-checks defensively at bind).
                val dims = (field.storage as? entkt.schema.ColumnStorage.Native)?.dimensions
                    ?: error("pgvector field '${field.apiName}' missing dimensions metadata")
                val opt = if (field.nullable) "?" else ""
                rowBuilder.add(
                    "  %S to %L$opt.also { require(it.dimensions == %L) { %S } },\n",
                    col, preparationLocal(prop), dims, "$prop expects vector($dims)",
                )
            } else {
                rowBuilder.add("  %S to %L,\n", col, preparedProp)
            }
        }
        for (fk in edgeFks) {
            rowBuilder.add("  %S to %L,\n", fk.columnName, preparationLocal(fk.propertyName))
        }
        rowBuilder.add(")\n")

        builder.addCode(rowBuilder.build())
    }

    private fun fkDefaultCodeBlock(fk: EdgeFk): CodeBlock {
        // Field-backed FK defaults come from the backing field's
        // `.default(...)`. Only Int/Long are reachable today (UUID and
        // bytes have no DSL default), and both render via the
        // KotlinPoet primitive literal path.
        return primitiveLiteralCodeBlock(fk.default!!)
    }

    private fun defaultCodeBlock(field: Field): CodeBlock {
        val value = field.default!!
        return when {
            field.type == FieldType.TIME && value == "now" ->
                CodeBlock.of("%T.now()", ClassName("java.time", "Instant"))
            field.type == FieldType.ENUM -> {
                require(value is Enum<*>) {
                    "Typed enum field '${field.apiName}' must use an enum constant as its default, not a String"
                }
                require(value::class == field.enumClass) {
                    "Typed enum field '${field.apiName}' default must be a ${field.enumClass!!.simpleName} constant, got ${value::class.simpleName}"
                }
                val enumType = field.resolvedTypeName()
                CodeBlock.of("%T.%L", enumType, value.name)
            }
            else -> primitiveLiteralCodeBlock(value)
        }
    }

    /**
     * Render a primitive default value as a Kotlin source literal.
     * Strings go through KotlinPoet's `%S` format, which handles
     * everything the prior hand-rolled escaper missed — `$` (string
     * template interpolation), `\n` / `\r` / `\t` (literal control
     * chars in the source string), unicode escapes — by emitting a
     * properly-quoted Kotlin literal. Numbers and booleans render
     * via `%L` since they have no escape concerns.
     */
    private fun primitiveLiteralCodeBlock(value: Any): CodeBlock = when (value) {
        is String -> CodeBlock.of("%S", value)
        is Boolean -> CodeBlock.of("%L", value)
        is Number -> CodeBlock.of("%L", value)
        else -> CodeBlock.of("%L", value)
    }

    private fun buildCandidateArgs(allFields: List<Field>, edgeFks: List<EdgeFk>): List<String> {
        val args = mutableListOf<String>()
        val preparedValueNames = preparedCreateValueNames(allFields, edgeFks)
        for (field in allFields) {
            args.add("${field.apiName} = ${preparedValueNames.getValue(field)}")
        }
        for (fk in edgeFks) {
            args.add("${fk.propertyName} = ${preparationLocal(fk.propertyName)}")
        }
        return args
    }

    /**
     * Name for a local bound in the create-preparation body.
     *
     * Preparation locals must not be named after the field's API name:
     * the same function also binds fixed locals (`candidate`, `values`,
     * `entity`, `row`, …), so a schema declaring `val values by
     * string("values_col")` would emit two `val values` declarations and
     * generate uncompilable source.
     *
     * The framework's `_` prefix is reserved — declaration names must be
     * lower-camel identifiers — so a prefixed local can never collide
     * with a field, an FK, or a future fixed local that follows the same
     * convention.
     */
    private fun preparationLocal(apiName: String): String =
        "_entktValue${apiName.replaceFirstChar { it.uppercaseChar() }}"

    private fun preparedCreateValueNames(
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): Map<Field, String> {
        val usedNames = buildSet {
            allFields.forEach { add(it.apiName) }
            edgeFks.forEach { add(it.propertyName) }
        }
            .toMutableSet()

        return allFields.associateWith { field ->
            val property = field.apiName
            if (field.type != FieldType.BYTES && field.type != FieldType.JSON) {
                preparationLocal(property)
            } else {
                var generated = "_entktPrepared${property.replaceFirstChar { it.uppercaseChar() }}"
                while (!usedNames.add(generated)) generated += "_"
                generated
            }
        }
    }

    /**
     * Emit CREATE validation enforcement: call the repo's
     * decision-returning evaluateCreateValidation with the
     * already-built candidate; a non-empty violation list becomes a
     * typed EntValidationException via `_validationFailed`. A
     * rule-THROWN exception escapes the evaluator and is classified as
     * foreign at the terminal boundary — never as a typed validation
     * failure.
     */
    private fun emitCreateValidation(
        builder: FunSpec.Builder,
        schemaName: String,
        clientName: String,
    ) {
        val repoPropName = clientName
        builder.addStatement("val violations = client.%L.evaluateCreateValidation(candidate)", repoPropName)
        builder.addStatement("if (violations.isNotEmpty()) return·_validationFailed(violations)")
    }

    /**
     * Emit CREATE privacy enforcement for the already-prepared write
     * candidate. A returned denial reason (a rule's
     * Deny or the fail-closed end of the rule list) becomes
     * EntMutationPrivacyDeniedException(NotPersisted, CREATE) with a
     * null entity key — pre-insert there is no persisted key to
     * report. A rule-THROWN exception escapes to the terminal boundary
     * as a foreign failure.
     */
    private fun emitCreatePrivacy(
        builder: FunSpec.Builder,
        schemaName: String,
        clientName: String,
    ) {
        val repoPropName = clientName
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        builder.addStatement(
            "val denialReason = client.%L.createDenialReasonOrNull(privacy, candidate)",
            repoPropName,
        )
        builder.beginControlFlow("if (denialReason != null)")
        builder.addCode(
            privacyDeniedFailure(
                writeStateExpr = CodeBlock.of("%T.NotPersisted", MUTATION_WRITE_STATE),
                schemaName = schemaName,
                operationName = "CREATE",
                entityKeyExpr = CodeBlock.of("null"),
                reasonExpr = "denialReason",
            ),
        )
        builder.endControlFlow()
    }
}

internal fun hookListType(paramType: ClassName) =
    List::class.asClassName().parameterizedBy(
        ClassName("entkt.runtime.hook", "BatchHook").parameterizedBy(paramType),
    )

/**
 * Emit inline validation checks for a single field's validators.
 * When [nullable] is true, the checks are wrapped in `if (prop != null) { ... }`.
 * Each failed validator returns a typed EntValidationException-bearing
 * `MutationResult.Failed` through the generated `_validationFailed`
 * helper (which also records the failure on the transaction
 * coordinator). Used by both the create normalize phase and the update
 * patch-entry validation phase, so both builders must declare
 * `_validationFailed` (see [buildValidationFailedHelper]).
 */
internal fun emitFieldValidation(
    builder: FunSpec.Builder,
    schemaName: String,
    prop: String,
    fieldName: String,
    validators: List<entkt.schema.Validator>,
    nullable: Boolean,
) {
    if (nullable) {
        builder.beginControlFlow("if (%L != null)", prop)
    }
    for (validator in validators) {
        val spec = validator.spec
            ?: error("Validator '${validator.name}' on field '$fieldName' has no spec — codegen cannot emit it")
        emitValidatorCheck(builder, schemaName, prop, fieldName, validator.message, spec)
    }
    if (nullable) {
        builder.endControlFlow()
    }
}

private fun emitValidatorCheck(
    builder: FunSpec.Builder,
    schemaName: String,
    prop: String,
    fieldName: String,
    message: String,
    spec: ValidatorSpec,
) {
    val failExpr =
        "return·_validationFailed(listOf(%T(%S, field = %S)))"
    when (spec) {
        is ValidatorSpec.MinLength -> builder.addStatement(
            "if (%L.length < %L) $failExpr",
            prop, spec.min,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
        is ValidatorSpec.MaxLength -> builder.addStatement(
            "if (%L.length > %L) $failExpr",
            prop, spec.max,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
        is ValidatorSpec.NotEmpty -> builder.addStatement(
            "if (%L.isEmpty()) $failExpr",
            prop,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
        is ValidatorSpec.Match -> {
            if (spec.options.isEmpty()) {
                builder.addStatement(
                    "if (!Regex(%S).matches(%L)) $failExpr",
                    spec.pattern, prop,
                    MUTATION_VALIDATION_VIOLATION, message, fieldName,
                )
            } else {
                val optionsLiteral = spec.options.joinToString(", ") { "RegexOption.${it.name}" }
                builder.addStatement(
                    "if (!Regex(%S, setOf($optionsLiteral)).matches(%L)) $failExpr",
                    spec.pattern, prop,
                    MUTATION_VALIDATION_VIOLATION, message, fieldName,
                )
            }
        }
        is ValidatorSpec.Min -> builder.addStatement(
            "if (%L < %L) $failExpr",
            prop, spec.min,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
        is ValidatorSpec.Max -> builder.addStatement(
            "if (%L > %L) $failExpr",
            prop, spec.max,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
        is ValidatorSpec.Positive -> builder.addStatement(
            "if (%L <= 0) $failExpr",
            prop,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
        is ValidatorSpec.Negative -> builder.addStatement(
            "if (%L >= 0) $failExpr",
            prop,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
        is ValidatorSpec.NonNegative -> builder.addStatement(
            "if (%L < 0) $failExpr",
            prop,
            MUTATION_VALIDATION_VIOLATION, message, fieldName,
        )
    }
}
