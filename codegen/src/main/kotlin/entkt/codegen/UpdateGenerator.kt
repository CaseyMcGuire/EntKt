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
private val ENT_ERROR = ClassName("entkt.runtime", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime", "EntOperation")
private val ENT_NOT_FOUND_EXCEPTION = ClassName("entkt.runtime", "EntNotFoundException")
private val ENT_NO_CHANGES_EXCEPTION = ClassName("entkt.runtime", "EntNoChangesException")


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
        val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
        val idType = schema.id().type.toTypeName()

        val beforeSaveHookType = hookListType(mutationClass)
        val beforeUpdateHookType = hookListType(updateHookCtxClass)
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
            .addFunctions(mutableFields.map { buildUnsetFunction(toCamelCase(it.name)) })
            .addFunctions(edgeFks.map { buildUnsetFunction(it.propertyName) })
            .addFunction(buildBuildRequestedPatchFunction(schemaName, mutableFields, edgeFks))
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
     * Generate `unset{Prop}()` on the update builder. Hooks call this
     * through the hook-facing mutation view to remove an entry from the
     * requested patch — distinct from `Set(null)`, which is an explicit
     * clear for nullable fields/FKs. Removing from `dirtyFields` is
     * sufficient because patch construction reads `dirtyFields` to
     * decide `Set` vs `Unset`.
     */
    private fun buildUnsetFunction(prop: String): FunSpec {
        val name = "unset${prop.replaceFirstChar { it.uppercaseChar() }}"
        return FunSpec.builder(name)
            .addStatement("dirtyFields.remove(%S)", prop)
            .build()
    }

    /**
     * Generate a private `_buildRequestedPatch()` member that constructs
     * the requested patch from the current `dirtyFields` snapshot. Used
     * (a) before each beforeUpdate hook to capture the per-hook patch
     * snapshot, and (b) once after all hooks to capture the canonical
     * requested patch fed into update defaults / privacy / validation.
     *
     * Required-field/FK null checks live inside this helper so they
     * fire whenever a snapshot is constructed: a hook that explicitly
     * sets a required field to `null` triggers the same domain error
     * as a builder that does so.
     */
    private fun buildBuildRequestedPatchFunction(
        schemaName: String,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val builder = FunSpec.builder("_buildRequestedPatch")
            .addModifiers(KModifier.PRIVATE)
            .returns(patchClass)

        // Required-field null checks (only fire when explicitly assigned).
        for (field in mutableFields) {
            if (field.nullable) continue
            val prop = toCamelCase(field.name)
            builder.addStatement(
                "if (%S in dirtyFields && this.%L == null) throw IllegalStateException(%S)",
                prop, prop, "${field.name} is required",
            )
        }
        for (fk in edgeFks) {
            if (!fk.required) continue
            builder.addStatement(
                "if (%S in dirtyFields && this.%L == null) throw IllegalStateException(%S)",
                fk.propertyName, fk.propertyName, "${fk.edgeName} is required",
            )
        }

        val code = CodeBlock.builder()
        code.add("return %T(\n", patchClass)
        for (field in mutableFields) {
            val prop = toCamelCase(field.name)
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
        return builder.build()
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
        val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val mutableFields = allFields.filter { !it.immutable }

        val builder = FunSpec.builder("save")
            .returns(entityClass.copy(nullable = true))

        // ---- Syntactically empty patch: NoChanges before owner-row load. ----
        // Reporting NoChanges before the load avoids existence-leaking
        // `update(missingId) {}` calls. saveOrNull throws here too —
        // NoChanges is not "expected absence" per the result-variants RFC.
        builder.beginControlFlow("if (dirtyFields.isEmpty())")
        builder.addStatement(
            "throw %T(%T.NoChanges(%S, %T.UPDATE, id))",
            ENT_NO_CHANGES_EXCEPTION,
            ENT_ERROR,
            schemaName,
            ENT_OPERATION,
        )
        builder.endControlFlow()

        // ---- Internal current-row load (bypasses LOAD privacy). ----
        // Missing rows short-circuit before hooks/privacy/validation run.
        builder.addStatement(
            "val row0 = driver.byId(%T.TABLE, id) ?: return null",
            entityClass,
        )
        builder.addStatement("entity = %T.fromRow(row0)", entityClass)

        // ---- beforeSave hooks (shared with create — receive Mutation interface). ----
        builder.addStatement("for (hook in beforeSaveHooks) hook(this)")

        // ---- beforeUpdate hooks (receive a per-hook context with snapshot). ----
        // `patch` in the context is a snapshot built *before* the hook
        // runs. Within a hook, writes through the mutation view do not
        // change `patch`. After each hook returns, the next iteration
        // builds a fresh snapshot from the current dirty state.
        builder.beginControlFlow("for (hook in beforeUpdateHooks)")
        builder.addStatement("val snapshot = _buildRequestedPatch()")
        builder.addStatement(
            "val ctx = %T(client, entity, snapshot, this)",
            updateHookCtxClass,
        )
        builder.addStatement("hook(ctx)")
        builder.endControlFlow()

        // ---- Build the canonical requested patch after all before hooks. ----
        builder.addStatement("val requestedPatch = _buildRequestedPatch()")

        // ---- Hook-cleared empty path (must run BEFORE update defaults). ----
        // Per the RFC, "hook-cleared empty updates skip update defaults".
        // dirtyFields.isEmpty() here ⇔ requested patch is all Unset.
        // Build an unchanged effective patch (= requested, all Unset),
        // run UPDATE privacy on the unchanged after-state candidate
        // (a real authorization decision against the loaded `before`),
        // then throw NoChanges. Validation, driver write, after-hooks,
        // and returned LOAD privacy are skipped.
        builder.beginControlFlow("if (dirtyFields.isEmpty())")
        builder.addStatement("val effectivePatch = requestedPatch")
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        emitCandidateConstruction(
            builder,
            candidateClass = candidateClass,
            allFields = allFields,
            edgeFks = edgeFks,
        )
        builder.addStatement(
            "client.%L.evaluateUpdatePrivacy(privacy, entity, requestedPatch, effectivePatch, candidate)",
            repoPropName,
        )
        builder.addStatement(
            "throw %T(%T.NoChanges(%S, %T.UPDATE, id))",
            ENT_NO_CHANGES_EXCEPTION,
            ENT_ERROR,
            schemaName,
            ENT_OPERATION,
        )
        builder.endControlFlow()

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
            // .name for enums needs a null-aware call when the enum is nullable.
            if (field.type == FieldType.ENUM && field.nullable) {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value?.name }\n",
                    prop, FIELD_PATCH, col,
                )
            } else if (field.type == FieldType.ENUM) {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value.name }\n",
                    prop, FIELD_PATCH, col,
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

        // ---- Privacy + validation. ----
        // The hook-cleared empty branch above already handled the
        // requested-empty-after-hooks case, so reaching here means the
        // requested patch had at least one Set entry.
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        emitCandidateConstruction(
            builder,
            candidateClass = candidateClass,
            allFields = allFields,
            edgeFks = edgeFks,
        )
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
     * Non-null variant: throws [EntNotFoundException] when the owner row
     * has vanished (the `OrNull` `save()` returns `null` for that case
     * — `saveOrThrow()` lifts it into a structured failure). For
     * syntactically empty updates the underlying `save()` already
     * throws [EntNoChangesException], which propagates here unchanged.
     */
    private fun buildSaveOrThrowFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        return FunSpec.builder("saveOrThrow")
            .returns(entityClass)
            .addStatement(
                "return save() ?: throw %T(%T.NotFound(%S, %T.UPDATE, id))",
                ENT_NOT_FOUND_EXCEPTION,
                ENT_ERROR,
                schemaName,
                ENT_OPERATION,
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
