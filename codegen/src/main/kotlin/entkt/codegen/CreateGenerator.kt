package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
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
                    // (RFC: explicit-null-wins for nullable field-backed FKs).
                    if (!fk.required && fk.default != null) {
                        builder.addProperty(buildAssignedFlag(fk))
                    }
                }
            }
            .addProperties(edgeFks.map { buildEdgeFkProperty(it, override = true) })
            .addFunction(buildSaveFunction(schemaName, schema, allFields, edgeFks))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
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
            //   - public property is non-null typed (per RFC "Public Types")
            //   - private nullable staging field holds the value until assigned
            //   - getter throws on unassigned read (per RFC "Resolved FK
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
            return builder.build()
        }
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        val builder = PropertySpec.builder(fk.propertyName, typeName)
            .mutable(true)
            .initializer("null")
        if (override) builder.addModifiers(KModifier.OVERRIDE)
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

        // ---- Lifecycle hooks (before validation so hooks can set fields). ----
        builder.addStatement("for (hook in beforeSaveHooks) hook(this)")
        // beforeCreate hooks receive a CreateHookContext wrapping the
        // restricted view and the client. `this` satisfies the view
        // contract, so it can be passed as the mutation directly.
        val createHookCtxClass = ClassName(packageName, "${schemaName}CreateHookContext")
        builder.addStatement(
            "val createCtx = %T(client, this)",
            createHookCtxClass,
        )
        builder.addStatement("for (hook in beforeCreateHooks) hook(createCtx)")

        // ---- Validate and bind each property to a local. ----
        for (field in allFields) {
            val prop = toCamelCase(field.name)
            val required = !field.nullable && field.default == null
            when {
                required -> builder.addStatement(
                    "val %L = this.%L ?: throw IllegalStateException(%S)",
                    prop,
                    prop,
                    "${field.name} is required",
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
            emitFieldValidation(builder, prop, field.name, field.validators, nullable)
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
                // Nullable + default: explicit-null-wins (per RFC). The
                // setter flips `_<prop>Assigned`, so any explicit write
                // — including `null` — suppresses the default.
                !fk.required && fk.default != null -> builder.addStatement(
                    "val %L = if (this.%L) this.%L else %L",
                    fk.propertyName,
                    assignedFieldName(fk.propertyName),
                    fk.propertyName,
                    fkDefaultCodeBlock(fk),
                )
                // No default: required FKs throw on unassigned via the
                // getter; nullable FKs stay nullable.
                else -> builder.addStatement("val %L = this.%L", fk.propertyName, fk.propertyName)
            }
            // Field-level validators carried from the backing field of
            // a field-backed edge run on the resolved FK value, the
            // same way scalar field validators do above.
            if (fk.validators.isNotEmpty()) {
                emitFieldValidation(
                    builder,
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
        // bytes have no DSL default), and both fit through the literal
        // branch of `kotlinLiteral`.
        val value = fk.default!!
        return CodeBlock.of("%L", kotlinLiteral(value))
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
            else -> CodeBlock.of("%L", kotlinLiteral(value))
        }
    }

    private fun kotlinLiteral(value: Any): String = when (value) {
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> value.toString()
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
     * Emit LOAD privacy enforcement on the returned entity. If denied,
     * the write has already succeeded but the caller gets a
     * [PrivacyDeniedException] explaining why they cannot see it.
     */
    private fun emitLoadPrivacyOnReturn(
        builder: FunSpec.Builder,
        schemaName: String,
        entityVar: String,
    ) {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        builder.addStatement("client.%L.evaluateLoadPrivacy(privacy, %L)", repoPropName, entityVar)
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
}

internal fun hookListType(paramType: ClassName) =
    List::class.asClassName().parameterizedBy(
        LambdaTypeName.get(parameters = arrayOf(paramType), returnType = UNIT),
    )

/**
 * Emit inline validation checks for a single field's validators.
 * When [nullable] is true, the checks are wrapped in `if (prop != null) { ... }`.
 */
internal fun emitFieldValidation(
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
        emitValidatorCheck(builder, prop, fieldName, validator.message, spec)
    }
    if (nullable) {
        builder.endControlFlow()
    }
}

private fun emitValidatorCheck(
    builder: FunSpec.Builder,
    prop: String,
    fieldName: String,
    message: String,
    spec: ValidatorSpec,
) {
    val errorMsg = "$fieldName: $message"
    when (spec) {
        is ValidatorSpec.MinLen -> builder.addStatement(
            "if (%L.length < %L) throw IllegalStateException(%S)", prop, spec.min, errorMsg,
        )
        is ValidatorSpec.MaxLen -> builder.addStatement(
            "if (%L.length > %L) throw IllegalStateException(%S)", prop, spec.max, errorMsg,
        )
        is ValidatorSpec.NotEmpty -> builder.addStatement(
            "if (%L.isEmpty()) throw IllegalStateException(%S)", prop, errorMsg,
        )
        is ValidatorSpec.Match -> {
            if (spec.options.isEmpty()) {
                builder.addStatement(
                    "if (!Regex(%S).matches(%L)) throw IllegalStateException(%S)", spec.pattern, prop, errorMsg,
                )
            } else {
                val optionsLiteral = spec.options.joinToString(", ") { "RegexOption.${it.name}" }
                builder.addStatement(
                    "if (!Regex(%S, setOf($optionsLiteral)).matches(%L)) throw IllegalStateException(%S)",
                    spec.pattern, prop, errorMsg,
                )
            }
        }
        is ValidatorSpec.Min -> builder.addStatement(
            "if (%L < %L) throw IllegalStateException(%S)", prop, spec.min, errorMsg,
        )
        is ValidatorSpec.Max -> builder.addStatement(
            "if (%L > %L) throw IllegalStateException(%S)", prop, spec.max, errorMsg,
        )
        is ValidatorSpec.Positive -> builder.addStatement(
            "if (%L <= 0) throw IllegalStateException(%S)", prop, errorMsg,
        )
        is ValidatorSpec.Negative -> builder.addStatement(
            "if (%L >= 0) throw IllegalStateException(%S)", prop, errorMsg,
        )
        is ValidatorSpec.NonNegative -> builder.addStatement(
            "if (%L < 0) throw IllegalStateException(%S)", prop, errorMsg,
        )
    }
}
