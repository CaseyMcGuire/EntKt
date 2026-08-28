package entkt.codegen.mutation

import entkt.codegen.apiName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.columnName
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.setter
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.ValidatorSpec

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val UUID_CLASS = ClassName("java.util", "UUID")

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
internal val ENT_MUTATION_PRIVACY_DENIED_EXCEPTION =
    ClassName("entkt.runtime.result", "EntMutationPrivacyDeniedException")
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

// Keep generated mutation code on Kotlin's Exception type so KotlinPoet does
// not introduce a `java.lang.Exception as LangException` alias in repo files.
internal val KOTLIN_EXCEPTION = ClassName("kotlin", "Exception")

/**
 * Re-emit [this] one indentation level deeper. Purely cosmetic for the
 * generated output: hand-assembled `add("... {\n")` fragments carry
 * their own literal indentation, so a shared fragment spliced inside a
 * nested block needs re-indenting to line up with its surroundings.
 */
internal fun CodeBlock.indented(): CodeBlock =
    codeBlock {
        indent()
        add(this@indented)
        unindent()
    }

/**
 * The `@file:OptIn(EntktInternal::class)` annotation every generated
 * mutation-side file carries so `MutationResult.failedForInternalUse`
 * (guarded by the error-level [EntktInternal] marker) is callable from
 * generated code compiled in the application module.
 */
internal fun entktInternalFileOptIn(): AnnotationSpec =
    annotation(ClassName("kotlin", "OptIn")) {
        useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
        addMember("%T::class", ENTKT_INTERNAL)
    }

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
    codeBlock {
        add("val exception = ")
        add(exceptionExpr)
        add("\n")
        add("client.recordTransactionMutationFailure(exception)\n")
        add("return %T.failedForInternalUse(exception)\n", MUTATION_RESULT)
    }

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
    codeBlock {
        add(
            recordAndReturnFailure(
                codeBlock {
                    add("%T(", ENT_MUTATION_PRIVACY_DENIED_EXCEPTION)
                    add(writeStateExpr)
                    add(", %S, %T.%L, ", schemaName, MUTATION_ENT_OPERATION, operationName)
                    add(entityKeyExpr)
                    add(", %L)", reasonExpr)
                },
            ),
        )
    }

internal class CreateGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}CreateDraft"
        // Backing FK columns are emitted via `edgeFks` so their generated
        // API name and assignment token come from the relationship. Don't
        // double-emit them as scalar fields.
        val allFields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val entityClass = ClassName(packageName, schemaName)
        val idStrategy = idStrategyName(schema)
        val idType = schema.id().type.toTypeName()

        val assignedFieldsType = ClassName("entkt.runtime.mutation", "AssignedFields")
        val typeSpec = classType(className) {
            addAnnotation(annotation(ENTKT_DSL))
            primaryConstructor {
                addAnnotation(ENTKT_INTERNAL)
                if (idStrategy == "EXPLICIT") parameter("id", idType)
            }
            property("assignedFields", assignedFieldsType.parameterizedBy(entityClass)) {
                addModifiers(KModifier.PRIVATE)
                initializer("%T()", assignedFieldsType)
            }
            if (idStrategy == "EXPLICIT") {
                property("id", idType) {
                    addAnnotation(ENTKT_INTERNAL)
                    addModifiers(KModifier.INTERNAL)
                    initializer("id")
                }
            }
            addProperties(allFields.map { buildDraftProperty(entityClass, it) })
            addProperties(edgeFks.map { buildDraftEdgeFkProperty(entityClass, it) })
            addFunction(buildIsSetFunction(entityClass))
        }

        return kotlinFile(packageName, className) {
            addAnnotation(entktInternalFileOptIn())
            addType(typeSpec)
        }
    }

    private fun buildDraftProperty(entityClass: ClassName, field: Field): PropertySpec {
        val type = field.resolvedTypeName().copy(nullable = true)
        return property(field.apiName, type) {
            mutable(true)
            initializer("null")
            setter {
                parameter("value", type)
                statement("field = value")
                statement("assignedFields.mark(%T.%L)", entityClass, field.apiName)
            }
            field.comment?.let { addKdoc("%L", it) }
        }
    }

    private fun buildDraftEdgeFkProperty(entityClass: ClassName, fk: EdgeFk): PropertySpec {
        val type = fk.idType.toTypeName().copy(nullable = true)
        return property(fk.propertyName, type) {
            mutable(true)
            initializer("null")
            fk.comment?.let { addKdoc("%L", it) }
            setter {
                parameter("value", type)
                statement("field = value")
                statement("assignedFields.mark(%T.%L)", entityClass, fk.propertyName)
            }
        }
    }

    private fun buildIsSetFunction(entityClass: ClassName): FunSpec =
        function("isSet", returnType = BOOLEAN) {
            parameter(
                "column",
                ClassName("entkt.query", "ColumnReference").parameterizedBy(entityClass),
            )
            statement("return column in assignedFields")
        }

    /** Build the repo-owned required-input check run before draft resolution. */
    fun buildRequiredInputViolationsFunction(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val draftClass = ClassName(packageName, "${schemaName}CreateDraft")
        val entityClass = ClassName(packageName, schemaName)
        val allFields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        return function(
            "requiredInputViolations",
            returnType = List::class.asClassName().parameterizedBy(MUTATION_VALIDATION_VIOLATION),
        ) {
            addModifiers(KModifier.PRIVATE)
            parameter("draft", draftClass)
            for (field in allFields) {
                if (field.nullable) continue
                val prop = field.apiName
                val condition = if (field.default == null) {
                    CodeBlock.of("draft.%L == null", prop)
                } else {
                    CodeBlock.of(
                        "draft.isSet(%T.%L) && draft.%L == null",
                        entityClass,
                        prop,
                        prop,
                    )
                }
                addStatement(
                    "if (%L) return·listOf(%T(%S, field = %S))",
                    condition,
                    MUTATION_VALIDATION_VIOLATION,
                    "$prop is required",
                    prop,
                )
            }
            for (fk in edgeFks) {
                if (!fk.required) continue
                val prop = fk.propertyName
                val condition = if (fk.default == null) {
                    CodeBlock.of("draft.%L == null", prop)
                } else {
                    CodeBlock.of(
                        "draft.isSet(%T.%L) && draft.%L == null",
                        entityClass,
                        prop,
                        prop,
                    )
                }
                addStatement(
                    "if (%L) return·listOf(%T(%S, field = %S))",
                    condition,
                    MUTATION_VALIDATION_VIOLATION,
                    "$prop is required",
                    prop,
                )
            }
            addStatement("return emptyList()")
        }
    }

    /** Build the repo-owned draft resolver used by scalar and batch create. */
    fun buildResolveFunction(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val draftClass = ClassName(packageName, "${schemaName}CreateDraft")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val allFields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        return function(
            "resolve",
            returnType = PREPARED_CREATE.parameterizedBy(candidateClass),
        ) {
            addModifiers(KModifier.PRIVATE)
            parameter("draft", draftClass)
            beginControlFlow("return draft.run")
            emitResolvedCreate(this, schemaName, schema, allFields, edgeFks)
            val candidateArgs = buildCandidateArgs(allFields, edgeFks)
            addStatement("val candidate = %T(${candidateArgs.joinToString(", ")})", candidateClass)
            addCode(codeBlock {
                add("%T(\n", PREPARED_CREATE)
                add("  values = values,\n")
                add("  candidate = candidate,\n")
                add(")\n")
            })
            endControlFlow()
        }
    }

    /** Build schema-field checks over the stable candidate produced by [resolve]. */
    fun buildCreateFieldViolationsFunction(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val allFields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        return function(
            "createFieldViolations",
            returnType = List::class.asClassName().parameterizedBy(MUTATION_VALIDATION_VIOLATION),
        ) {
            addModifiers(KModifier.PRIVATE)
            parameter("candidate", candidateClass)
            for (field in allFields) {
                if (field.validators.isNotEmpty()) {
                    emitFieldViolationChecks(
                        builder = this,
                        prop = "candidate.${field.apiName}",
                        fieldName = field.apiName,
                        validators = field.validators,
                        nullable = field.nullable,
                    )
                }
                if (field.type == FieldType.PGVECTOR) {
                    val dimensions =
                        (field.storage as? entkt.schema.ColumnStorage.Native)?.dimensions
                            ?: error(
                                "pgvector field '${field.apiName}' missing dimensions metadata",
                            )
                    val condition = if (field.nullable) {
                        CodeBlock.of(
                            "candidate.%L != null && candidate.%L.dimensions != %L",
                            field.apiName,
                            field.apiName,
                            dimensions,
                        )
                    } else {
                        CodeBlock.of(
                            "candidate.%L.dimensions != %L",
                            field.apiName,
                            dimensions,
                        )
                    }
                    addStatement(
                        "if (%L) return·listOf(%T(%S, field = %S))",
                        condition,
                        MUTATION_VALIDATION_VIOLATION,
                        "${field.apiName} expects vector($dimensions)",
                        field.apiName,
                    )
                }
            }
            for (fk in edgeFks) {
                if (fk.validators.isEmpty()) continue
                emitFieldViolationChecks(
                    builder = this,
                    prop = "candidate.${fk.propertyName}",
                    fieldName = fk.propertyName,
                    validators = fk.validators,
                    nullable = !fk.required,
                )
            }
            addStatement("return emptyList()")
        }
    }

    /**
     * Emit the pure preparation body shared by scalar and batch create:
     * field extraction with defaults, FK resolution, and the driver
     * `values` map. Hooks, validation, entity rules, privacy, and I/O are
     * deliberately absent.
     */
    private fun emitResolvedCreate(
        builder: FunSpec.Builder,
        schemaName: String,
        schema: EntSchema,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val idStrategy = idStrategyName(schema)
        val preparedValueNames = preparedCreateValueNames(allFields, edgeFks)

        // ---- Bind each property to a local. ----
        // Required inputs have already passed the executor-owned validation phase.
        // checkNotNull therefore asserts an internal lifecycle invariant rather than
        // reporting user input validation from resolution.
        for (field in allFields) {
            val prop = field.apiName
            val required = !field.nullable && field.default == null
            when {
                required -> builder.addStatement(
                    "val %L = checkNotNull(this.%L) { %S }",
                    preparationLocal(prop),
                    prop,
                    "$prop missing after required-input validation",
                )
                field.default != null && field.nullable -> builder.addStatement(
                    "val %L = if (isSet(%T.%L)) this.%L else %L",
                    preparationLocal(prop),
                    ClassName(packageName, schemaName),
                    prop,
                    prop,
                    defaultCodeBlock(field),
                )
                field.default != null -> {
                    builder.addStatement(
                        "val %L = if (isSet(%T.%L)) checkNotNull(this.%L) { %S } else %L",
                        preparationLocal(prop),
                        ClassName(packageName, schemaName),
                        prop,
                        prop,
                        "$prop missing after required-input validation",
                        defaultCodeBlock(field),
                    )
                }
                else -> builder.addStatement("val %L = this.%L", preparationLocal(prop), prop)
            }
        }

        for (fk in edgeFks) {
            when {
                fk.required && fk.default != null -> builder.addStatement(
                    "val %L = if (isSet(%T.%L)) checkNotNull(this.%L) { %S } else %L",
                    preparationLocal(fk.propertyName),
                    ClassName(packageName, schemaName),
                    fk.propertyName,
                    fk.propertyName,
                    "${fk.propertyName} missing after required-input validation",
                    fkDefaultCodeBlock(fk),
                )
                !fk.required && fk.default != null -> builder.addStatement(
                    "val %L = if (isSet(%T.%L)) this.%L else %L",
                    preparationLocal(fk.propertyName),
                    ClassName(packageName, schemaName),
                    fk.propertyName,
                    fk.propertyName,
                    fkDefaultCodeBlock(fk),
                )
                fk.required -> builder.addStatement(
                    "val %L = checkNotNull(this.%L) { %S }",
                    preparationLocal(fk.propertyName),
                    fk.propertyName,
                    "${fk.propertyName} missing after required-input validation",
                )
                // Nullable + no default: pass through, may be null.
                else -> builder.addStatement(
                    "val %L = this.%L", preparationLocal(fk.propertyName), fk.propertyName,
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
        val valuesMap = codeBlock {
            add("val values: Map<String, Any?> = mapOf(\n")

            if (idStrategy == "CLIENT_UUID") {
                add("  %S to %T.randomUUID(),\n", "id", UUID_CLASS)
            } else if (idStrategy == "EXPLICIT") {
                add("  %S to id,\n", "id")
            }

            for (field in allFields) {
                val prop = field.apiName
                val preparedProp = preparedValueNames.getValue(field)
                val col = field.columnName
                if (field.type == FieldType.ENUM) {
                    if (field.nullable) {
                        add("  %S to %L?.name,\n", col, preparationLocal(prop))
                    } else {
                        add("  %S to %L.name,\n", col, preparationLocal(prop))
                    }
                } else if (field.type == FieldType.PGVECTOR) {
                    add("  %S to %L,\n", col, preparationLocal(prop))
                } else {
                    add("  %S to %L,\n", col, preparedProp)
                }
            }
            for (fk in edgeFks) {
                add("  %S to %L,\n", fk.columnName, preparationLocal(fk.propertyName))
            }
            add(")\n")
        }

        builder.addCode(valuesMap)
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

}

internal fun hookListType(paramType: ClassName) =
    List::class.asClassName().parameterizedBy(
        ClassName("entkt.runtime.hook", "BatchHook").parameterizedBy(paramType),
    )

/**
 * Emit inline validation checks for a single field's validators.
 * When [nullable] is true, the checks are wrapped in `if (prop != null) { ... }`.
 * The caller supplies [invalidPreparationType] so the runtime executor can
 * classify and record the preparation failure at the shared lifecycle boundary.
 */
internal fun emitFieldValidation(
    builder: FunSpec.Builder,
    prop: String,
    fieldName: String,
    validators: List<entkt.schema.Validator>,
    nullable: Boolean,
    invalidPreparationType: ClassName,
) {
    if (nullable) {
        builder.beginControlFlow("if (%L != null)", prop)
    }
    for (validator in validators) {
        val spec = validator.spec
            ?: error("Validator '${validator.name}' on field '$fieldName' has no spec — codegen cannot emit it")
        val failure = CodeBlock.of(
            "%T.Invalid(listOf(%T(%S, field = %S)))",
            invalidPreparationType,
            MUTATION_VALIDATION_VIOLATION,
            validator.message,
            fieldName,
        )
        emitValidatorCheck(
            builder,
            prop,
            spec,
            failure,
        )
    }
    if (nullable) {
        builder.endControlFlow()
    }
}

/** Emit field checks whose failure returns validation violations directly. */
private fun emitFieldViolationChecks(
    builder: FunSpec.Builder,
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
        val failure = CodeBlock.of(
            "listOf(%T(%S, field = %S))",
            MUTATION_VALIDATION_VIOLATION,
            validator.message,
            fieldName,
        )
        emitValidatorCheck(builder, prop, spec, failure)
    }
    if (nullable) {
        builder.endControlFlow()
    }
}

private fun emitValidatorCheck(
    builder: FunSpec.Builder,
    prop: String,
    spec: ValidatorSpec,
    failure: CodeBlock,
) {
    when (spec) {
        is ValidatorSpec.MinLength -> builder.addStatement(
            "if (%L.length < %L) return·%L",
            prop, spec.min, failure,
        )
        is ValidatorSpec.MaxLength -> builder.addStatement(
            "if (%L.length > %L) return·%L",
            prop, spec.max, failure,
        )
        is ValidatorSpec.NotEmpty -> builder.addStatement(
            "if (%L.isEmpty()) return·%L",
            prop, failure,
        )
        is ValidatorSpec.Match -> {
            // Regex / RegexOption go through %T, never raw text —
            // kotlin.text is default-imported, so a raw name would
            // resolve against same-package declarations first. (The
            // shadowed-name validation independently rejects entities
            // named after these.)
            val regexClass = ClassName("kotlin.text", "Regex")
            if (spec.options.isEmpty()) {
                builder.addStatement(
                    "if (!%T(%S).matches(%L)) return·%L",
                    regexClass, spec.pattern, prop, failure,
                )
            } else {
                val regexOptionClass = ClassName("kotlin.text", "RegexOption")
                val optionsLiteral = spec.options.joinToString(", ") { "%T.${it.name}" }
                builder.addStatement(
                    "if (!%T(%S, setOf($optionsLiteral)).matches(%L)) return·%L",
                    regexClass, spec.pattern,
                    *spec.options.map { regexOptionClass }.toTypedArray(),
                    prop, failure,
                )
            }
        }
        is ValidatorSpec.Min -> builder.addStatement(
            "if (%L < %L) return·%L",
            prop, spec.min, failure,
        )
        is ValidatorSpec.Max -> builder.addStatement(
            "if (%L > %L) return·%L",
            prop, spec.max, failure,
        )
        is ValidatorSpec.Positive -> builder.addStatement(
            "if (%L <= 0) return·%L",
            prop, failure,
        )
        is ValidatorSpec.Negative -> builder.addStatement(
            "if (%L >= 0) return·%L",
            prop, failure,
        )
        is ValidatorSpec.NonNegative -> builder.addStatement(
            "if (%L < 0) return·%L",
            prop, failure,
        )
    }
}
