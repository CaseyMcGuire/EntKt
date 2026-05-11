package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.UpdateDefault

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val DRIVER = ClassName("entkt.runtime", "Driver")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val FIELD_PATCH = ClassName("entkt.runtime", "FieldPatch")
private val FIELD_PATCH_OR_ELSE = MemberName("entkt.runtime", "orElse")


internal class UpdateGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Update"
        val allFields = schema.fields()
        val mutableFields = allFields.filter { !it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val entityClass = ClassName(packageName, schemaName)
        val updateClass = ClassName(packageName, className)
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val idType = schema.id().type.toTypeName()

        val beforeSaveHookType = hookListType(mutationClass)
        val beforeUpdateHookType = hookListType(updateClass)
        val afterUpdateHookType = hookListType(entityClass)

        val typeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addSuperinterface(mutationClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter("client", clientClass)
                    .addParameter("id", idType)
                    .addParameter("beforeSaveHooks", beforeSaveHookType)
                    .addParameter("beforeUpdateHooks", beforeUpdateHookType)
                    .addParameter("afterUpdateHooks", afterUpdateHookType)
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
                PropertySpec.builder("id", idType)
                    .initializer("id")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("entity", entityClass)
                    .addModifiers(KModifier.LATEINIT)
                    .mutable(true)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("beforeSaveHooks", beforeSaveHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("beforeSaveHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("beforeUpdateHooks", beforeUpdateHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("beforeUpdateHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("afterUpdateHooks", afterUpdateHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("afterUpdateHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "dirtyFields",
                    ClassName("kotlin.collections", "MutableSet").parameterizedBy(STRING),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("mutableSetOf()")
                    .build()
            )
            .addProperties(mutableFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeFkProperty(it) })
            .addProperties(edgeFks.map { buildEdgeEntityProperty(it) })
            .addFunction(buildSaveFunction(schemaName, allFields, edgeFks))
            .addFunction(buildSaveOrThrowFunction(schemaName))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildProperty(field: Field): PropertySpec {
        val prop = toCamelCase(field.name)
        val typeName = field.resolvedTypeName().copy(nullable = true)
        val builder = PropertySpec.builder(prop, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("field = value")
                    .addStatement("dirtyFields.add(%S)", prop)
                    .build()
            )
        val comment = field.comment
        if (comment != null) builder.addKdoc("%L", comment)
        return builder.build()
    }

    private fun buildEdgeFkProperty(fk: EdgeFk): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        return PropertySpec.builder(fk.propertyName, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("field = value")
                    .addStatement("dirtyFields.add(%S)", fk.propertyName)
                    .build()
            )
            .build()
    }

    /**
     * Assigning a target entity here also writes its id into the
     * underlying FK property. e.g. `author = alice` sets
     * `authorId = alice.id`.
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
     * `save()` writes the builder's changes to the driver and returns
     * the refreshed entity — or null when the row has been deleted out
     * from under us.
     *
     * The current owner row is loaded internally at the start of
     * `save()` (bypassing LOAD privacy). If the row no longer exists,
     * `save()` returns `null` before any hook, privacy, validation, or
     * driver write runs. Hooks see the loaded row through the [entity]
     * property.
     *
     * After before hooks, the builder's `dirtyFields` are lowered into
     * a `${schemaName}UpdatePatch` (the requested patch). Update
     * defaults (e.g. `updatedAt = updateDefaultNow()`) are then applied
     * to produce the effective patch, which is the actual database
     * write set: only fields and FKs in the effective patch are sent
     * to `driver.update(...)`. Untouched columns are not written back.
     *
     * Privacy and validation rules see the loaded `before`, the
     * requested patch, the effective patch, and a full after-state
     * write candidate built by folding the effective patch onto
     * `before`.
     *
     * If the effective patch is empty (no dirty fields and no update
     * defaults applied), `save()` returns the loaded entity without
     * issuing a database write. A future phase replaces that with a
     * structured `NoChanges` error.
     */
    private fun buildSaveFunction(
        schemaName: String,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val mutableFields = allFields.filter { !it.immutable }

        val builder = FunSpec.builder("save")
            .returns(entityClass.copy(nullable = true))

        // ---- Internal current-row load (bypasses LOAD privacy). ----
        // Missing rows short-circuit before hooks/privacy/validation run.
        builder.addStatement(
            "val row0 = driver.byId(%T.TABLE, id) ?: return null",
            entityClass,
        )
        builder.addStatement("entity = %T.fromRow(row0)", entityClass)

        // ---- Lifecycle hooks. ----
        // Hooks may set or clear fields on the builder; the requested
        // patch is captured *after* before hooks run so hook writes are
        // included.
        builder.addStatement("for (hook in beforeSaveHooks) hook(this)")
        builder.addStatement("for (hook in beforeUpdateHooks) hook(this)")

        // ---- Required-field/FK null checks for explicitly-cleared writes. ----
        // For required fields, `(dirty + this.foo == null)` means a
        // hook or the builder explicitly assigned null — fail with a
        // domain error before patch construction.
        for (field in mutableFields) {
            if (field.nullable) continue
            val prop = toCamelCase(field.name)
            builder.addStatement(
                "if (%S in dirtyFields && this.%L == null) throw IllegalStateException(%S)",
                prop,
                prop,
                "${field.name} is required",
            )
        }
        for (fk in edgeFks) {
            if (!fk.required) continue
            builder.addStatement(
                "if (%S in dirtyFields && this.%L == null) throw IllegalStateException(%S)",
                fk.propertyName,
                fk.propertyName,
                "${fk.edgeName} is required",
            )
        }

        // ---- Build the requested patch from dirty tracking. ----
        emitPatchConstruction(
            builder,
            valName = "requestedPatch",
            patchClass = patchClass,
            mutableFields = mutableFields,
            edgeFks = edgeFks,
        )

        // ---- Apply update defaults to compute the effective patch. ----
        emitEffectivePatchConstruction(
            builder,
            patchClass = patchClass,
            mutableFields = mutableFields,
            edgeFks = edgeFks,
        )

        // ---- Field-level validation on Set entries of the effective patch. ----
        for (field in mutableFields) {
            if (field.validators.isEmpty()) continue
            emitPatchEntryValidation(builder, field)
        }

        // ---- Build the database write set from the effective patch. ----
        builder.addStatement("val values = mutableMapOf<String, Any?>()")
        for (field in mutableFields) {
            val prop = toCamelCase(field.name)
            val col = field.columnName
            val nameSuffix = if (field.type == FieldType.ENUM) ".name" else ""
            // .name for enums needs a null-aware call when the enum is nullable.
            if (field.type == FieldType.ENUM && field.nullable) {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value?.name }\n",
                    prop, FIELD_PATCH, col,
                )
            } else if (field.type == FieldType.ENUM) {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value%L }\n",
                    prop, FIELD_PATCH, col, nameSuffix,
                )
            } else {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value }\n",
                    prop, FIELD_PATCH, col,
                )
            }
        }
        for (fk in edgeFks) {
            builder.addCode(
                "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value }\n",
                fk.propertyName, FIELD_PATCH, fk.columnName,
            )
        }

        // ---- Empty-effective-patch fast path. ----
        // No fields changed and no update defaults applied: return the
        // loaded `before` without issuing a driver write. Phase 3 will
        // replace this with a structured `NoChanges` error.
        builder.beginControlFlow("if (values.isEmpty())")
        builder.addStatement("return entity")
        builder.endControlFlow()

        // ---- Build the full after-state candidate. ----
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        emitCandidateConstruction(
            builder,
            candidateClass = candidateClass,
            allFields = allFields,
            edgeFks = edgeFks,
        )

        // ---- Privacy + validation. ----
        builder.addStatement(
            "client.%L.evaluateUpdatePrivacy(privacy, entity, requestedPatch, effectivePatch, candidate)",
            repoPropName,
        )
        builder.addStatement(
            "client.%L.evaluateUpdateValidation(entity, requestedPatch, effectivePatch, candidate)",
            repoPropName,
        )

        // ---- Driver write + after hooks + return load privacy. ----
        builder.addStatement(
            "val row = driver.update(%T.TABLE, id, values) ?: return null",
            entityClass,
        )
        builder.addStatement("val updatedEntity = %T.fromRow(row)", entityClass)
        builder.addStatement("for (hook in afterUpdateHooks) hook(updatedEntity)")
        builder.addStatement("client.%L.evaluateLoadPrivacy(privacy, updatedEntity)", repoPropName)
        builder.addStatement("return updatedEntity")

        return builder.build()
    }

    /** Build `requestedPatch` by lowering `dirtyFields` to `FieldPatch` entries. */
    private fun emitPatchConstruction(
        builder: FunSpec.Builder,
        valName: String,
        patchClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val code = CodeBlock.builder()
        code.add("val %L = %T(\n", valName, patchClass)
        for (field in mutableFields) {
            val prop = toCamelCase(field.name)
            // Required fields: `!!` is safe because the dirty+null check
            // already ran. Nullable fields: `Set(this.foo)` permits Set(null).
            if (field.nullable) {
                code.add(
                    "  %L = if (%S in dirtyFields) %T.Set(this.%L) else %T.Unset,\n",
                    prop, prop, FIELD_PATCH, prop, FIELD_PATCH,
                )
            } else {
                code.add(
                    "  %L = if (%S in dirtyFields) %T.Set(this.%L!!) else %T.Unset,\n",
                    prop, prop, FIELD_PATCH, prop, FIELD_PATCH,
                )
            }
        }
        for (fk in edgeFks) {
            if (fk.required) {
                code.add(
                    "  %L = if (%S in dirtyFields) %T.Set(this.%L!!) else %T.Unset,\n",
                    fk.propertyName, fk.propertyName, FIELD_PATCH, fk.propertyName, FIELD_PATCH,
                )
            } else {
                code.add(
                    "  %L = if (%S in dirtyFields) %T.Set(this.%L) else %T.Unset,\n",
                    fk.propertyName, fk.propertyName, FIELD_PATCH, fk.propertyName, FIELD_PATCH,
                )
            }
        }
        code.add(")\n")
        builder.addCode(code.build())
    }

    /**
     * Apply framework update defaults to produce `effectivePatch`. Fields
     * with `updateDefault` whose requested entry is `Unset` get the
     * default applied; explicit caller/hook writes always win.
     */
    private fun emitEffectivePatchConstruction(
        builder: FunSpec.Builder,
        patchClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val hasUpdateDefaults = mutableFields.any { it.updateDefault != null }
        if (!hasUpdateDefaults) {
            // No defaults to apply — the effective patch equals the requested patch.
            builder.addStatement("val effectivePatch = requestedPatch")
            return
        }
        val code = CodeBlock.builder()
        code.add("val effectivePatch = %T(\n", patchClass)
        for (field in mutableFields) {
            val prop = toCamelCase(field.name)
            if (field.updateDefault != null) {
                code.add(
                    "  %L = if (requestedPatch.%L is %T.Set) requestedPatch.%L else %T.Set(%L),\n",
                    prop, prop, FIELD_PATCH, prop, FIELD_PATCH, updateDefaultCodeBlock(field),
                )
            } else {
                code.add("  %L = requestedPatch.%L,\n", prop, prop)
            }
        }
        for (fk in edgeFks) {
            code.add("  %L = requestedPatch.%L,\n", fk.propertyName, fk.propertyName)
        }
        code.add(")\n")
        builder.addCode(code.build())
    }

    /**
     * Apply field validators to a Set entry of the effective patch. The
     * value is non-null for required fields; for nullable fields, the
     * validators only run when the patched value is non-null (validators
     * don't validate null on nullable fields).
     */
    private fun emitPatchEntryValidation(builder: FunSpec.Builder, field: Field) {
        val prop = toCamelCase(field.name)
        val localName = "${prop}_eff"
        builder.addStatement("val %L = effectivePatch.%L", localName, prop)
        builder.beginControlFlow("if (%L is %T.Set)", localName, FIELD_PATCH)
        if (field.nullable) {
            builder.addStatement("val %L_v = %L.value", prop, localName)
            builder.beginControlFlow("if (%L_v != null)", prop)
            emitFieldValidation(builder, "${prop}_v", field.name, field.validators, nullable = false)
            builder.endControlFlow()
        } else {
            builder.addStatement("val %L_v = %L.value", prop, localName)
            emitFieldValidation(builder, "${prop}_v", field.name, field.validators, nullable = false)
        }
        builder.endControlFlow()
    }

    /**
     * Build the full after-state write candidate by folding the
     * effective patch onto the loaded `before` entity. Immutable fields
     * always come from `before` (they're not in the patch). Edge FKs
     * and mutable fields use `effectivePatch.foo.orElse(entity.foo)`.
     */
    private fun emitCandidateConstruction(
        builder: FunSpec.Builder,
        candidateClass: ClassName,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val code = CodeBlock.builder()
        code.add("val candidate = %T(\n", candidateClass)
        for (field in allFields) {
            val prop = toCamelCase(field.name)
            if (field.immutable) {
                code.add("  %L = entity.%L,\n", prop, prop)
            } else {
                code.add(
                    "  %L = effectivePatch.%L.%M(entity.%L),\n",
                    prop, prop, FIELD_PATCH_OR_ELSE, prop,
                )
            }
        }
        for (fk in edgeFks) {
            code.add(
                "  %L = effectivePatch.%L.%M(entity.%L),\n",
                fk.propertyName, fk.propertyName, FIELD_PATCH_OR_ELSE, fk.propertyName,
            )
        }
        code.add(")\n")
        builder.addCode(code.build())
    }

    /**
     * Non-null variant: throws when the row has vanished. Useful from
     * callers that already know the entity exists (e.g. re-saving the
     * result of a recent query) and don't want to deal with the `?`.
     */
    private fun buildSaveOrThrowFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        return FunSpec.builder("saveOrThrow")
            .returns(entityClass)
            .addStatement(
                "return save() ?: throw IllegalStateException(%S)",
                "$schemaName row not found",
            )
            .build()
    }

    private fun updateDefaultCodeBlock(field: Field): CodeBlock {
        return when (field.updateDefault!!) {
            is UpdateDefault.Now -> {
                require(field.type == FieldType.TIME) {
                    "Field '${field.name}' has UpdateDefault.Now but type is ${field.type} — updateDefault is only valid on TIME fields"
                }
                CodeBlock.of("%T.now()", ClassName("java.time", "Instant"))
            }
        }
    }
}
