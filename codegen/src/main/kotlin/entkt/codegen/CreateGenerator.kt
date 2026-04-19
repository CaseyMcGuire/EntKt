package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
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

class CreateGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Create"
        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val allFields = fields + mixinFields
        val mutableFields = allFields.filter { !it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val entityClass = ClassName(packageName, schemaName)
        val createClass = ClassName(packageName, className)
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)

        val beforeSaveHookType = hookListType(mutationClass)
        val beforeCreateHookType = hookListType(createClass)
        val afterCreateHookType = hookListType(entityClass)
        val afterUpdateHookType = hookListType(entityClass)

        val typeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addSuperinterface(mutationClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter("client", clientClass)
                    .addParameter("beforeSaveHooks", beforeSaveHookType)
                    .addParameter("beforeCreateHooks", beforeCreateHookType)
                    .addParameter("afterCreateHooks", afterCreateHookType)
                    .addParameter(
                        ParameterSpec.builder("afterUpdateHooks", afterUpdateHookType)
                            .defaultValue("emptyList()")
                            .build()
                    )
                    .build()
            )
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
                PropertySpec.builder("afterUpdateHooks", afterUpdateHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("afterUpdateHooks")
                    .build()
            )
            .addProperties(mutableFields.map { buildProperty(it, override = true) })
            .addProperties(allFields.filter { it.immutable }.map { buildProperty(it, override = false) })
            .addProperties(edgeFks.map { buildEdgeFkProperty(it, override = true) })
            .addProperties(edgeFks.map { buildEdgeEntityProperty(it) })
            .addFunction(buildSaveFunction(schemaName, schema, allFields, edgeFks))
            .addFunction(buildUpsertFunction(schemaName, schema, allFields, edgeFks))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildProperty(field: Field, override: Boolean): PropertySpec {
        val typeName = field.resolvedTypeName().copy(nullable = true)
        val builder = PropertySpec.builder(toCamelCase(field.name), typeName)
            .mutable(true)
            .initializer("null")
        if (override) builder.addModifiers(KModifier.OVERRIDE)
        return builder.build()
    }

    private fun buildEdgeFkProperty(fk: EdgeFk, override: Boolean): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        val builder = PropertySpec.builder(fk.propertyName, typeName)
            .mutable(true)
            .initializer("null")
        if (override) builder.addModifiers(KModifier.OVERRIDE)
        return builder.build()
    }

    /**
     * Convenience property mirroring the edge name: assigning a target
     * entity here also writes its id into the underlying FK property.
     * e.g. `author = alice` sets `authorId = alice.id`.
     */
    private fun buildEdgeEntityProperty(fk: EdgeFk): PropertySpec {
        val targetClass = ClassName(packageName, fk.targetName).copy(nullable = true)
        val edgeProp = toCamelCase(fk.edgeName)
        return PropertySpec.builder(edgeProp, targetClass)
            .mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", targetClass)
                    .addStatement("field = value")
                    .addStatement("%L = value?.id", fk.propertyName)
                    .build()
            )
            .build()
    }

    /**
     * `save()` lowers the builder's accumulated state into a row map and
     * hands it to the driver. The driver minting strategy decides how
     * the id is produced:
     * - `CLIENT_UUID`: we mint a `UUID` here so the caller can see it
     *   before the round trip.
     * - `AUTO_INT` / `AUTO_LONG`: we omit `id` and let the driver pick.
     * - `EXPLICIT`: unsupported in `save()` for now — caller must go
     *   through the driver directly.
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

        val idStrategy = idStrategyName(schema)
        if (idStrategy == "EXPLICIT") {
            builder.addStatement(
                "TODO(%S)",
                "save() on $schemaName requires EXPLICIT id support",
            )
            return builder.build()
        }

        emitCreateBody(builder, schemaName, schema, allFields, edgeFks)
        builder.addStatement(
            "val row = driver.insert(%T.TABLE, values)",
            entityClass,
        )
        builder.addStatement("val entity = %T.fromRow(row)", entityClass)
        builder.addStatement("for (hook in afterCreateHooks) hook(entity)")
        builder.addStatement("return entity")

        return builder.build()
    }

    /**
     * `upsert(onConflict)` collects fields the same way as `save()` but
     * calls `driver.upsert` with the specified conflict columns. On
     * conflict, the driver updates non-id, non-conflict columns and
     * returns the persisted row.
     */
    private fun buildUpsertFunction(
        schemaName: String,
        schema: EntSchema,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val columnClass = ClassName("entkt.query", "Column")
        val builder = FunSpec.builder("upsert")
            .returns(entityClass)
            .addParameter(
                ParameterSpec.builder(
                    "onConflict",
                    columnClass.parameterizedBy(STAR),
                ).addModifiers(KModifier.VARARG).build(),
            )

        val idStrategy = idStrategyName(schema)
        if (idStrategy == "EXPLICIT") {
            builder.addStatement(
                "TODO(%S)",
                "upsert() on $schemaName requires EXPLICIT id support",
            )
            return builder.build()
        }

        emitCreateBody(builder, schemaName, schema, allFields, edgeFks)

        val immutableNames = allFields.filter { it.immutable }.map { it.name }
        if (immutableNames.isEmpty()) {
            builder.addStatement(
                "val result = driver.upsert(%T.TABLE, values, onConflict.map { it.name })",
                entityClass,
            )
        } else {
            builder.addStatement(
                "val result = driver.upsert(%T.TABLE, values, onConflict.map { it.name }, listOf(%L))",
                entityClass,
                immutableNames.joinToString(", ") { "\"$it\"" },
            )
        }
        builder.addStatement("val entity = %T.fromRow(result.row)", entityClass)
        builder.beginControlFlow("if (result.inserted)")
        builder.addStatement("for (hook in afterCreateHooks) hook(entity)")
        builder.nextControlFlow("else")
        builder.addStatement("for (hook in afterUpdateHooks) hook(entity)")
        builder.endControlFlow()
        builder.addStatement("return entity")

        return builder.build()
    }

    /**
     * Emit the common body shared by [buildSaveFunction] and
     * [buildUpsertFunction]: lifecycle hooks, field extraction with
     * defaults and validation, FK validation, and the `values` map.
     * After this method, the caller appends the driver call.
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
        builder.addStatement("for (hook in beforeCreateHooks) hook(this)")

        // ---- Validate and bind each property to a local. ----
        for (field in allFields) {
            val prop = toCamelCase(field.name)
            val required = !field.optional && !field.nillable && field.default == null
            val hasDefault = !field.optional && !field.nillable && field.default != null
            when {
                required -> builder.addStatement(
                    "val %L = this.%L ?: throw IllegalStateException(%S)",
                    prop,
                    prop,
                    "${field.name} is required",
                )
                hasDefault -> builder.addStatement(
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
            val codegenValidators = field.validators.filter { it.spec != null }
            if (codegenValidators.isEmpty()) continue
            val prop = toCamelCase(field.name)
            val nullable = field.optional || field.nillable
            emitFieldValidation(builder, prop, field.name, codegenValidators, nullable)
        }

        for (fk in edgeFks) {
            if (fk.required) {
                builder.addStatement(
                    "val %L = this.%L ?: throw IllegalStateException(%S)",
                    fk.propertyName,
                    fk.propertyName,
                    "${fk.edgeName} is required",
                )
            } else {
                builder.addStatement("val %L = this.%L", fk.propertyName, fk.propertyName)
            }
        }

        // ---- Build the row map. ----
        val rowBuilder = CodeBlock.builder()
            .add("val values: Map<String, Any?> = mapOf(\n")

        if (idStrategy == "CLIENT_UUID") {
            rowBuilder.add("  %S to %T.randomUUID(),\n", "id", UUID_CLASS)
        }

        for (field in allFields) {
            val prop = toCamelCase(field.name)
            if (field.type == FieldType.ENUM && field.enumClass != null) {
                val nullable = field.optional || field.nillable
                if (nullable) {
                    rowBuilder.add("  %S to %L?.name,\n", field.name, prop)
                } else {
                    rowBuilder.add("  %S to %L.name,\n", field.name, prop)
                }
            } else {
                rowBuilder.add("  %S to %L,\n", field.name, prop)
            }
        }
        for (fk in edgeFks) {
            rowBuilder.add("  %S to %L,\n", fk.columnName, fk.propertyName)
        }
        rowBuilder.add(")\n")

        builder.addCode(rowBuilder.build())
    }

    private fun defaultCodeBlock(field: Field): CodeBlock {
        val value = field.default!!
        return when {
            field.type == FieldType.TIME && value == "now" ->
                CodeBlock.of("%T.now()", ClassName("java.time", "Instant"))
            field.type == FieldType.ENUM && field.enumClass != null -> {
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
        val spec = validator.spec ?: continue
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
        is ValidatorSpec.Match -> builder.addStatement(
            "if (!Regex(%S).matches(%L)) throw IllegalStateException(%S)", spec.pattern, prop, errorMsg,
        )
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
