package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.ValidatorSpec

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val DRIVER = ClassName("entkt.runtime", "Driver")
private val UUID_CLASS = ClassName("java.util", "UUID")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val ENT_ERROR = ClassName("entkt.runtime", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime", "EntOperation")
private val ENT_RESULT = ClassName("entkt.runtime", "EntResult")
private val ENT_EXCEPTION = ClassName("entkt.runtime", "EntException")
private val PRIVACY_DENIED_EXCEPTION = ClassName("entkt.runtime", "PrivacyDeniedException")
private val VALIDATION_EXCEPTION_CLASS = ClassName("entkt.runtime", "ValidationException")

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
            .addFunction(buildSaveFunction(schemaName, schema, allFields, edgeFks))
            .addFunction(buildSaveOrErrorFunction(schemaName))
            .addFunction(buildSaveOrThrowFunction(schemaName))
            .build()

        return FileSpec.builder(packageName, className)
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
            val propName = toCamelCase(field.name)
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
            val propName = toCamelCase(field.name)
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
        val builder = PropertySpec.builder(toCamelCase(field.name), typeName)
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
                            "${fk.edgeName} is required",
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
                            "${fk.edgeName} is required",
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
     * `save()` lowers the builder's accumulated state into a row map and
     * hands it to the driver. The driver minting strategy decides how
     * the id is produced:
     * - `CLIENT_UUID`: we mint a `UUID` here so the caller can see it
     *   before the round trip.
     * - `AUTO_INT` / `AUTO_LONG`: we omit `id` and let the driver pick.
     * - `EXPLICIT`: the caller must set `id` on the builder before
     *   calling `save()`; an error is thrown if it's missing.
     *
     * After insert, the driver returns the persisted row (including the
     * assigned id), which we feed into `fromRow` to hydrate a typed
     * entity. That avoids any inconsistency between the row we sent and
     * the row the driver actually stored.
     */
    private fun buildSaveFunction(
        schemaName: String,
        schema: EntSchema,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val builder = FunSpec.builder("save")
            .returns(entityClass)

        emitCreateBody(builder, schemaName, schema, allFields, edgeFks)
        emitCreatePrivacy(builder, schemaName, allFields, edgeFks)
        emitCreateValidation(builder, schemaName)
        builder.addStatement(
            "val row = driver.insert(%T.TABLE, values)",
            entityClass,
        )
        builder.addStatement("val entity = %T.fromRow(row)", entityClass)
        builder.addStatement("for (hook in afterCreateHooks) hook(entity)")
        emitLoadPrivacyOnReturn(builder, schemaName, "entity")
        builder.addStatement("return entity")

        return builder.build()
    }

    /**
     * Emit the common body shared by save: lifecycle hooks, field
     * extraction with defaults and validation, FK validation, and the
     * `values` map. After this method, the caller appends the driver call.
     */
    private fun emitCreateBody(
        builder: FunSpec.Builder,
        schemaName: String,
        schema: EntSchema,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val idStrategy = idStrategyName(schema)

        // ---- Transaction-requirement preflight (transaction locking). Throws
        // TransactionRequiredException before any observable work
        // (hooks, defaults, validation, driver writes) when the
        // configured TransactionRequirement isn't satisfied. ----
        builder.addStatement("client.checkTransactionRequirement(%S)", "$schemaName create")

        // ---- Lifecycle hooks (before validation so hooks can set fields). ----
        // create-hook adapter: route through the private `_beforeSaveView` and
        // `_createMutationView` adapters so a misbehaving hook
        // that tries to cast back to `${Schema}Create` (or to a
        // wider sibling view) fails at runtime — matching the
        // runtime-enforced contract the update path uses.
        builder.addStatement("for (hook in beforeSaveHooks) hook(_beforeSaveView)")
        val createHookCtxClass = ClassName(packageName, "${schemaName}CreateHookContext")
        builder.addStatement(
            "val createCtx = %T(client, _createMutationView)",
            createHookCtxClass,
        )
        builder.addStatement("for (hook in beforeCreateHooks) hook(createCtx)")

        // ---- Validate and bind each property to a local. ----
        // Required fields throw `ValidationException` on missing input.
        // The generated `saveOrError()` catches it and wraps into
        // `EntError.ValidationFailed`; `save()` callers see the raw
        // exception. Short-circuits on the first missing required
        // field — collecting all violations across required +
        // validator rules is left as a future improvement.
        for (field in allFields) {
            val prop = toCamelCase(field.name)
            val required = !field.nullable && field.default == null
            when {
                required -> builder.addStatement(
                    "val %L = this.%L ?: throw %T(%S, listOf(%T(%S, field = %S)))",
                    prop,
                    prop,
                    VALIDATION_EXCEPTION,
                    schemaName,
                    VALIDATION_INVALID,
                    "${field.name} is required",
                    field.name,
                )
                field.default != null -> builder.addStatement(
                    "val %L = this.%L ?: %L",
                    prop,
                    prop,
                    defaultCodeBlock(field),
                )
                else -> builder.addStatement("val %L = this.%L", prop, prop)
            }
        }

        // ---- Field-level validation. ----
        for (field in allFields) {
            if (field.validators.isEmpty()) continue
            val prop = toCamelCase(field.name)
            val nullable = field.nullable
            emitFieldValidation(builder, schemaName, prop, field.name, field.validators, nullable)
        }

        for (fk in edgeFks) {
            when {
                // Required + default: read staging directly so an unset
                // value falls back to the default instead of throwing
                // via the public non-null getter.
                fk.required && fk.default != null -> builder.addStatement(
                    "val %L = this.%L ?: %L",
                    fk.propertyName,
                    stagingFieldName(fk.propertyName),
                    fkDefaultCodeBlock(fk),
                )
                // Nullable + default: explicit-null-wins (by contract). The
                // setter flips `_<prop>Assigned`, so any explicit write
                // — including `null` — suppresses the default.
                !fk.required && fk.default != null -> builder.addStatement(
                    "val %L = if (this.%L) this.%L else %L",
                    fk.propertyName,
                    assignedFieldName(fk.propertyName),
                    fk.propertyName,
                    fkDefaultCodeBlock(fk),
                )
                // Required + no default: read staging directly so the
                // missing-input throw is a ValidationException rather
                // than the property getter's IllegalStateException.
                // The property getter stays as ISE for hook/property
                // reads, where an early read is a usage error.
                // (The generated `saveOrError()` catches the
                // ValidationException and wraps into
                // `EntError.ValidationFailed`; `save()` callers see
                // the raw exception.)
                fk.required -> builder.addStatement(
                    "val %L = this.%L ?: throw %T(%S, listOf(%T(%S, field = %S)))",
                    fk.propertyName,
                    stagingFieldName(fk.propertyName),
                    VALIDATION_EXCEPTION,
                    schemaName,
                    VALIDATION_INVALID,
                    "${fk.edgeName} is required",
                    fk.columnName,
                )
                // Nullable + no default: pass through, may be null.
                else -> builder.addStatement("val %L = this.%L", fk.propertyName, fk.propertyName)
            }
            // Field-level validators carried from the backing field of
            // a field-backed edge run on the resolved FK value, the
            // same way scalar field validators do above.
            if (fk.validators.isNotEmpty()) {
                emitFieldValidation(
                    builder,
                    schemaName = schemaName,
                    prop = fk.propertyName,
                    fieldName = fk.columnName,
                    validators = fk.validators,
                    nullable = !fk.required,
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
            val prop = toCamelCase(field.name)
            val col = field.columnName
            if (field.type == FieldType.ENUM) {
                val nullable = field.nullable
                if (nullable) {
                    rowBuilder.add("  %S to %L?.name,\n", col, prop)
                } else {
                    rowBuilder.add("  %S to %L.name,\n", col, prop)
                }
            } else if (field.type == FieldType.PGVECTOR) {
                // Validate the vector's dimension at save() build time, with a
                // field-named error (the driver re-checks defensively at bind).
                val dims = (field.storage as? entkt.schema.ColumnStorage.Native)?.dimensions
                    ?: error("pgvector field '${field.name}' missing dimensions metadata")
                val opt = if (field.nullable) "?" else ""
                rowBuilder.add(
                    "  %S to %L$opt.also { require(it.dimensions == %L) { %S } },\n",
                    col, prop, dims, "${field.name} expects vector($dims)",
                )
            } else {
                rowBuilder.add("  %S to %L,\n", col, prop)
            }
        }
        for (fk in edgeFks) {
            rowBuilder.add("  %S to %L,\n", fk.columnName, fk.propertyName)
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
                    "Typed enum field '${field.name}' must use an enum constant as its default, not a String"
                }
                require(value::class == field.enumClass) {
                    "Typed enum field '${field.name}' default must be a ${field.enumClass!!.simpleName} constant, got ${value::class.simpleName}"
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
        for (field in allFields) {
            args.add("${toCamelCase(field.name)} = ${toCamelCase(field.name)}")
        }
        for (fk in edgeFks) {
            args.add("${fk.propertyName} = ${fk.propertyName}")
        }
        return args
    }

    /**
     * Emit LOAD privacy enforcement on the just-written entity. The
     * write has already succeeded; if LOAD denies, the caller can't
     * read what they wrote.
     *
     * Wraps the raw `PrivacyDeniedException` (which carries
     * `operation = LOAD`) into the structured
     * [EntWriteSucceededLoadDeniedException] carrying the new entity
     * id. This makes the "write happened but you can't see it" case
     * distinguishable from "write rejected up-front" — the former
     * surfaces as `Err(WriteSucceededLoadDenied)` through
     * `saveOrError`, the latter as `Err(PrivacyDenied(CREATE))`.
     * Without the wrap, both would collapse to `PrivacyDenied` and
     * callers couldn't tell whether the write actually happened.
     *
     * The wrap throws a [EntException] subclass, which the existing
     * `saveOrError` `catch (EntException) { Err(e.error) }` arm
     * picks up unchanged — no per-generator wiring needed at the
     * catch site.
     */
    private fun emitLoadPrivacyOnReturn(
        builder: FunSpec.Builder,
        schemaName: String,
        entityVar: String,
    ) {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        builder.beginControlFlow("try")
        builder.addStatement("client.%L.evaluateLoadPrivacy(privacy, %L)", repoPropName, entityVar)
        builder.nextControlFlow("catch (e: %T)", PRIVACY_DENIED_EXCEPTION)
        builder.addStatement(
            "throw %T(%T.WriteSucceededLoadDenied(e.entity, %T.CREATE, %L.id, e.reason))",
            ClassName("entkt.runtime", "EntWriteSucceededLoadDeniedException"),
            ENT_ERROR,
            ENT_OPERATION,
            entityVar,
        )
        builder.endControlFlow()
    }

    /**
     * Emit CREATE validation enforcement: call the repo's
     * evaluateCreateValidation with the already-built candidate.
     */
    private fun emitCreateValidation(
        builder: FunSpec.Builder,
        schemaName: String,
    ) {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        builder.addStatement("client.%L.evaluateCreateValidation(candidate)", repoPropName)
    }

    /**
     * Emit CREATE privacy enforcement: build a WriteCandidate from the
     * resolved field locals and call the repo's evaluateCreatePrivacy.
     */
    private fun emitCreatePrivacy(
        builder: FunSpec.Builder,
        schemaName: String,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        val candidateArgs = buildCandidateArgs(allFields, edgeFks)
        builder.addStatement(
            "val candidate = %T(${candidateArgs.joinToString(", ")})",
            candidateClass,
        )
        builder.addStatement("client.%L.evaluateCreatePrivacy(privacy, candidate)", repoPropName)
    }

    /**
     * Result-variant entry point for the create path. Wraps `save()` in
     * a single try/catch that maps each recognized failure surface into
     * the matching [EntError] variant:
     *
     *  - [PrivacyDeniedException] → `Err(PrivacyDenied)`
     *  - [ValidationException] → `Err(ValidationFailed)` (rule-DSL
     *    `ValidationDecision.Invalid` violations bridged through
     *    `toValidationViolation()`)
     *  - any other [EntException] (e.g. NoChanges from a transitively-
     *    composed save) → carried through via `e.error`
     *  - any remaining [Exception] → routed through
     *    [classifyDriverError] so the driver can emit
     *    `ConstraintViolation` for SQLSTATE 23xxx, falling back to
     *    `DriverFailure` with the raw cause attached.
     *
     * [Exception] (not [Throwable]) is the floor: [Error] subclasses
     * (OOME, StackOverflowError) propagate untouched. Programming
     * bugs from hooks/rules (`IllegalStateException`,
     * `IllegalArgumentException`, vanilla `RuntimeException`,
     * `NullPointerException`, etc.) re-throw via
     * [classifyDriverError] rather than being wrapped — only
     * `SQLException` (and subclasses like `PSQLException`) fall back
     * to `DriverFailure`. The driver classifier is the integration
     * point for SQLSTATE/message-prefix mapping, so adding
     * new constraint codes to a driver does not require regenerating
     * consumer code.
     */
    private fun buildSaveOrErrorFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val resultType = ENT_RESULT.parameterizedBy(entityClass)
        return FunSpec.builder("saveOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  %T.Ok(save())\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", PRIVACY_DENIED_EXCEPTION)
                    .add(
                        "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", VALIDATION_EXCEPTION_CLASS)
                    .add(
                        "  %T.Err(%T.ValidationFailed(e.entity, %T.CREATE, e.violations.map { it.%M() }))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                        MemberName("entkt.runtime", "toValidationViolation"),
                    )
                    .add("} catch (e: %T) {\n", ENT_EXCEPTION)
                    .add("  %T.Err(e.error)\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.CREATE))\n",
                        ENT_RESULT,
                        MemberName("entkt.runtime", "classifyDriverError"),
                        schemaName,
                        ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * Throwing variant: delegates to [saveOrError] and unwraps via
     * [EntResult.getOrThrow] so callers get a structured
     * [EntException] subclass for every recognized failure surface.
     * Implemented as a wrapper over `saveOrError()` to keep the
     * classification/mapping logic in one place.
     */
    private fun buildSaveOrThrowFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        return FunSpec.builder("saveOrThrow")
            .returns(entityClass)
            .addStatement(
                "return saveOrError().%M()",
                MemberName("entkt.runtime", "getOrThrow"),
            )
            .build()
    }
}

internal fun hookListType(paramType: ClassName) =
    List::class.asClassName().parameterizedBy(
        LambdaTypeName.get(parameters = arrayOf(paramType), returnType = UNIT),
    )

private val VALIDATION_EXCEPTION = ClassName("entkt.runtime", "ValidationException")
private val VALIDATION_INVALID = ClassName("entkt.runtime", "ValidationDecision", "Invalid")

/**
 * Emit inline validation checks for a single field's validators.
 * When [nullable] is true, the checks are wrapped in `if (prop != null) { ... }`.
 * Each failed validator throws [ValidationException] with a single-element
 * violations list. On both the create and update paths, the generated
 * `saveOrError()` catches it and wraps into `EntError.ValidationFailed`;
 * callers using the lower-level `save()` see the raw exception.
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
    val throwExpr =
        "throw %T(%S, listOf(%T(%S, field = %S)))"
    when (spec) {
        is ValidatorSpec.MinLength -> builder.addStatement(
            "if (%L.length < %L) $throwExpr",
            prop, spec.min,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
        is ValidatorSpec.MaxLength -> builder.addStatement(
            "if (%L.length > %L) $throwExpr",
            prop, spec.max,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
        is ValidatorSpec.NotEmpty -> builder.addStatement(
            "if (%L.isEmpty()) $throwExpr",
            prop,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
        is ValidatorSpec.Match -> {
            if (spec.options.isEmpty()) {
                builder.addStatement(
                    "if (!Regex(%S).matches(%L)) $throwExpr",
                    spec.pattern, prop,
                    VALIDATION_EXCEPTION, schemaName,
                    VALIDATION_INVALID, message, fieldName,
                )
            } else {
                val optionsLiteral = spec.options.joinToString(", ") { "RegexOption.${it.name}" }
                builder.addStatement(
                    "if (!Regex(%S, setOf($optionsLiteral)).matches(%L)) $throwExpr",
                    spec.pattern, prop,
                    VALIDATION_EXCEPTION, schemaName,
                    VALIDATION_INVALID, message, fieldName,
                )
            }
        }
        is ValidatorSpec.Min -> builder.addStatement(
            "if (%L < %L) $throwExpr",
            prop, spec.min,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
        is ValidatorSpec.Max -> builder.addStatement(
            "if (%L > %L) $throwExpr",
            prop, spec.max,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
        is ValidatorSpec.Positive -> builder.addStatement(
            "if (%L <= 0) $throwExpr",
            prop,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
        is ValidatorSpec.Negative -> builder.addStatement(
            "if (%L >= 0) $throwExpr",
            prop,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
        is ValidatorSpec.NonNegative -> builder.addStatement(
            "if (%L < 0) $throwExpr",
            prop,
            VALIDATION_EXCEPTION, schemaName,
            VALIDATION_INVALID, message, fieldName,
        )
    }
}
