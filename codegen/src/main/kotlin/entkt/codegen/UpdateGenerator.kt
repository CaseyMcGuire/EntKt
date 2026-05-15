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
private val VALIDATION_EXCEPTION = ClassName("entkt.runtime", "ValidationException")
private val VALIDATION_INVALID = ClassName("entkt.runtime", "ValidationDecision", "Invalid")
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime", "UpdateConsistency")
private val TRANSACTION_REQUIRED_EXCEPTION = ClassName("entkt.runtime", "TransactionRequiredException")
private val UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION = ClassName("entkt.runtime", "UnsupportedDriverCapabilityException")


internal class UpdateGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Update"
        // Backing FK columns are emitted via `edgeFks` so they pick up
        // relationship-FK semantics (non-null type, throw-on-untouched,
        // setter null-reject). Don't double-emit them as scalar fields.
        val allFields = scalarFields(schema)
        val mutableFields = allFields.filter { !it.immutable }
        // Immutable FKs are create-only — the update builder, the
        // hook-facing view, and the patch must not expose a write path
        // for them. `allEdgeFks` is used only for candidate construction
        // (which carries the unchanged value from `entity.before`).
        val allEdgeFks = computeEdgeFks(schema, schemaNames)
        val edgeFks = allEdgeFks.filter { !it.immutable }

        val entityClass = ClassName(packageName, schemaName)
        val updateClass = ClassName(packageName, className)
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val updateMutationViewClass = ClassName(packageName, "${schemaName}UpdateMutationView")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
        val idType = schema.id().type.toTypeName()

        val beforeSaveHookType = hookListType(mutationClass)
        val beforeUpdateHookType = hookListType(updateHookCtxClass)
        val afterUpdateHookType = hookListType(entityClass)

        // The Update builder implements only the shared Mutation
        // interface, not UpdateMutationView. The view (and its
        // `unset{Field}()` methods) lives behind a private inner
        // adapter so it can't be reached from the public DSL block:
        //   client.posts.update(id) {
        //     title = "x"
        //     unsetTitle()  // <-- intentionally won't compile
        //   }
        // Hooks reach unset through `ctx.mutation`, which is typed as
        // the view and constructed from the private adapter.
        val typeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addSuperinterface(mutationClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter("client", clientClass)
                    .addParameter("id", idType)
                    .addParameter("consistency", UPDATE_CONSISTENCY)
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
            // `client` is private. Hooks reach EntClient via
            // `ctx.client`; DSL callers already hold it in scope.
            .addProperty(
                PropertySpec.builder("client", clientClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("client")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("id", idType)
                    .initializer("id")
                    .build()
            )
            // Per-save UpdateConsistency selected by the caller (or
            // inherited from the client's `defaultUpdateConsistency`).
            // Read by save() at the start to choose between the
            // ReadCurrent unlocked byId path and the Pessimistic
            // readRowForUpdate path.
            .addProperty(
                PropertySpec.builder("consistency", UPDATE_CONSISTENCY)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("consistency")
                    .build()
            )
            // Internal state: populated by save() from the byId(id) load.
            // Hooks read the loaded `before` through the hook context
            // (ctx.before), not by poking at the builder. Keeping this
            // public would let direct callers either crash on
            // uninitialized access or observe a stale (pre-update) row
            // after save() completes.
            .addProperty(
                PropertySpec.builder("entity", entityClass)
                    .addModifiers(KModifier.PRIVATE, KModifier.LATEINIT)
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
            .also { builder ->
                for (fk in edgeFks) {
                    if (fk.required) {
                        builder.addProperty(buildRequiredFkStagingProperty(fk))
                    }
                }
            }
            .addProperties(edgeFks.map { buildEdgeFkProperty(it) })
            .addProperty(
                buildMutationViewProperty(
                    schemaName = schemaName,
                    updateMutationViewClass = updateMutationViewClass,
                    mutationClass = mutationClass,
                    mutableFields = mutableFields,
                    edgeFks = edgeFks,
                ),
            )
            .addFunction(buildBuildRequestedPatchFunction(schemaName, mutableFields, edgeFks))
            .addFunction(buildCheckRequiredNotNullFunction(schemaName, mutableFields, edgeFks))
            .addFunction(buildSaveFunction(schemaName, allFields, edgeFks, allEdgeFks))
            .addFunction(buildSaveOrNullFunction(schemaName))
            .addFunction(buildSaveOrThrowFunction(schemaName))
            .addFunction(buildSaveOrErrorFunction(schemaName))
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
            // Reading an untouched update field must throw (per RFC). The
            // builder has no current-state value before save(); for nullable
            // fields, a default-null getter would also collapse Unset and
            // explicit Set(null) into the same observable value. Hooks that
            // need to inspect pending state should read from `ctx.patch`,
            // which has explicit Unset / Set / Set(null) semantics.
            //
            // Note: this means a `beforeSave` hook that reads `m.title`
            // works on creates (returns the staged value) but throws on
            // updates with untouched fields. `beforeSave` is the shared
            // write-only surface; for phase-specific reads use
            // `beforeCreate` (`m.title` returns staged) or `beforeUpdate`
            // (`ctx.patch.title` returns FieldPatch state).
            .getter(
                FunSpec.getterBuilder()
                    .addStatement(
                        "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                        prop,
                        "$prop is not set in this update; read ctx.patch.$prop instead",
                    )
                    .addStatement("return field")
                    .build()
            )
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
        if (fk.required) {
            // Required FKs:
            //   - public property is non-null typed (per RFC "Public Types")
            //   - private nullable staging field holds the value until assigned
            //   - getter throws on untouched read (per RFC); reading
            //     pending update state goes through `ctx.patch.authorId`
            //   - setter rejects null at entry so Java/platform callers
            //     can't put the builder into a dirty+null state
            //   - `_checkRequiredNotNull()` still runs as a backstop for
            //     setter-bypassing paths (reflection writing the backing
            //     field directly)
            val nonNullType = fk.idType.toTypeName().copy(nullable = false)
            val stagingName = stagingFieldName(fk.propertyName)
            val requiredBuilder = PropertySpec.builder(fk.propertyName, nonNullType)
                .addModifiers(KModifier.OVERRIDE)
                .mutable(true)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement(
                            "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                            fk.propertyName,
                            "${fk.propertyName} is not set in this update; read ctx.patch.${fk.propertyName} instead",
                        )
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
                        .addStatement("dirtyFields.add(%S)", fk.propertyName)
                        .build(),
                )
            requiredBuilder.addKdoc("%L", fkPropertyKdoc(fk))
            return requiredBuilder.build()
        }
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        val nullableBuilder = PropertySpec.builder(fk.propertyName, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .initializer("null")
            .getter(
                FunSpec.getterBuilder()
                    .addStatement(
                        "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                        fk.propertyName,
                        "${fk.propertyName} is not set in this update; read ctx.patch.${fk.propertyName} instead",
                    )
                    .addStatement("return field")
                    .build()
            )
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("field = value")
                    .addStatement("dirtyFields.add(%S)", fk.propertyName)
                    .build()
            )
        nullableBuilder.addKdoc("%L", fkPropertyKdoc(fk))
        return nullableBuilder.build()
    }

    private fun buildRequiredFkStagingProperty(fk: EdgeFk): PropertySpec {
        val nullableType = fk.idType.toTypeName().copy(nullable = true)
        return PropertySpec.builder(stagingFieldName(fk.propertyName), nullableType)
            .addModifiers(KModifier.PRIVATE)
            .mutable(true)
            .initializer("null")
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
    /**
     * Build the private `_mutationView` adapter that hooks see as
     * `ctx.mutation`. The adapter implements [updateMutationViewClass]
     * by forwarding each [mutationClass] field/FK property to the
     * outer builder (so reads go through the throw-on-untouched
     * getter and writes flow through the dirty-tracking setter) and
     * declaring `unset{Field}()` overrides directly against the
     * outer's `dirtyFields`. Keeping the view behind a private
     * property keeps `unset` off the public DSL surface.
     */
    private fun buildMutationViewProperty(
        schemaName: String,
        updateMutationViewClass: ClassName,
        mutationClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): PropertySpec {
        val updateClassName = "${schemaName}Update"
        val adapter = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(updateMutationViewClass)
        // Forward each Mutation field/FK property to the outer builder.
        for (field in mutableFields) {
            val propName = toCamelCase(field.name)
            val typeName = field.resolvedTypeName().copy(nullable = true)
            adapter.addProperty(buildAdapterForwarderProperty(updateClassName, propName, typeName))
        }
        for (fk in edgeFks) {
            // Forwarder property type matches the interface (required → non-null).
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            adapter.addProperty(buildAdapterForwarderProperty(updateClassName, fk.propertyName, typeName))
        }
        // unset{Field}() overrides — the whole point of the view.
        for (field in mutableFields) {
            adapter.addFunction(buildAdapterUnsetFunction(updateClassName, toCamelCase(field.name)))
        }
        for (fk in edgeFks) {
            adapter.addFunction(buildAdapterUnsetFunction(updateClassName, fk.propertyName))
        }
        return PropertySpec.builder("_mutationView", updateMutationViewClass)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%L", adapter.build())
            .build()
    }

    private fun buildAdapterForwarderProperty(
        updateClassName: String,
        prop: String,
        typeName: com.squareup.kotlinpoet.TypeName,
    ): PropertySpec {
        return PropertySpec.builder(prop, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .getter(
                FunSpec.getterBuilder()
                    .addStatement("return this@%L.%L", updateClassName, prop)
                    .build(),
            )
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("this@%L.%L = value", updateClassName, prop)
                    .build(),
            )
            .build()
    }

    private fun buildAdapterUnsetFunction(updateClassName: String, prop: String): FunSpec {
        val name = "unset${prop.replaceFirstChar { it.uppercaseChar() }}"
        return FunSpec.builder(name)
            .addModifiers(KModifier.OVERRIDE)
            .addStatement("this@%L.dirtyFields.remove(%S)", updateClassName, prop)
            .build()
    }

    /**
     * Generate a private `_buildRequestedPatch()` member that constructs
     * the requested patch from the current `dirtyFields` snapshot. Used
     * (a) before each beforeUpdate hook to capture the per-hook patch
     * snapshot, and (b) once after all hooks to capture the canonical
     * requested patch fed into update defaults / privacy / validation.
     *
     * The helper is **lenient**: a required field/FK that's been
     * explicitly assigned `null` is represented as `Unset` in the patch
     * rather than throwing. The patch type is `FieldPatch<T>` for
     * required fields (T is non-nullable), so `Set(null)` is not even
     * representable. Hooks that want to inspect the underlying state
     * can read `ctx.mutation.foo` directly. The required-null check
     * fires once after all hooks have run, before the canonical patch
     * is built — this matches the RFC's "field-shape checks after
     * hooks" ordering and lets a hook repair a null assignment via
     * `mutation.unsetFoo()` or by reassigning `mutation.foo`.
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

        val code = CodeBlock.builder()
        code.add("return %T(\n", patchClass)
        for (field in mutableFields) {
            val prop = toCamelCase(field.name)
            if (field.nullable) {
                // Nullable: Set(this.foo) — Set(null) is an explicit clear.
                code.add(
                    "  %L = if (%S in dirtyFields) %T.Set(this.%L) else %T.Unset,\n",
                    prop, prop, FIELD_PATCH, prop, FIELD_PATCH,
                )
            } else {
                // Required: skip the Set entry if the value is null. The
                // post-hook required-null check (called from save()
                // before the canonical patch is built) catches unrepaired
                // nulls.
                code.add(
                    "  %L = if (%S in dirtyFields && this.%L != null) %T.Set(this.%L!!) else %T.Unset,\n",
                    prop, prop, prop, FIELD_PATCH, prop, FIELD_PATCH,
                )
            }
        }
        for (fk in edgeFks) {
            if (fk.required) {
                // Required FKs read from the private staging field rather
                // than the throw-on-untouched getter, so a corrupted
                // dirty+null state (setter-bypassing reflection) lowers
                // to `Unset` here and is caught by
                // `_checkRequiredNotNull()` before the canonical patch.
                val stagingName = stagingFieldName(fk.propertyName)
                code.add(
                    "  %L = if (%S in dirtyFields && this.%L != null) %T.Set(this.%L!!) else %T.Unset,\n",
                    fk.propertyName, fk.propertyName, stagingName, FIELD_PATCH, stagingName, FIELD_PATCH,
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
     * Generate a private `_checkRequiredNotNull()` member that throws
     * `IllegalStateException` for any required field or required edge FK
     * that has been assigned `null` and not repaired. Called from
     * `save()` after all `beforeUpdate` hooks complete, before the
     * canonical requested patch is built. A hook can clear the bad
     * assignment via `mutation.unsetFoo()` (removes the entry from
     * `dirtyFields`) or by reassigning a non-null value.
     */
    private fun buildCheckRequiredNotNullFunction(
        schemaName: String,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        // Failed required-shape checks throw `ValidationException` so
        // `saveOrError()` wraps them into `EntError.ValidationFailed`
        // rather than letting an `IllegalStateException` escape.
        // Short-circuits on the first failure (matches the existing
        // single-throw shape; collect-and-throw is left as a future
        // improvement).
        val builder = FunSpec.builder("_checkRequiredNotNull")
            .addModifiers(KModifier.PRIVATE)
        for (field in mutableFields) {
            if (field.nullable) continue
            val prop = toCamelCase(field.name)
            builder.addStatement(
                "if (%S in dirtyFields && this.%L == null) throw %T(%S, listOf(%T(%S, field = %S)))",
                prop, prop,
                VALIDATION_EXCEPTION, schemaName,
                VALIDATION_INVALID, "${field.name} is required", field.name,
            )
        }
        for (fk in edgeFks) {
            if (!fk.required) continue
            // Read the private staging field directly so a corrupted
            // dirty+null state is caught here rather than triggering the
            // throw-on-untouched getter (which would mask the diagnostic).
            val stagingName = stagingFieldName(fk.propertyName)
            builder.addStatement(
                "if (%S in dirtyFields && this.%L == null) throw %T(%S, listOf(%T(%S, field = %S)))",
                fk.propertyName, stagingName,
                VALIDATION_EXCEPTION, schemaName,
                VALIDATION_INVALID, "${fk.edgeName} is required", fk.columnName,
            )
        }
        return builder.build()
    }

    /**
     * `save()` writes the builder's changes to the driver and returns
     * the refreshed entity — or `null` when the row has been deleted
     * out from under us. `saveOrNull()` is an explicit alias;
     * `saveOrThrow()` lifts the missing-row case into
     * `EntNotFoundException`; `saveOrError()` returns
     * `EntResult<Entity>` for callers that want a structured outcome.
     *
     * **Empty patches throw NoChanges.** A syntactically empty
     * `update(id) { }` throws `EntNoChangesException` *before* the
     * owner-row load — request shape, not database state, classifies
     * the no-op (avoids leaking whether the id exists). A hook-cleared
     * empty patch (where hooks called `unsetX()` for every dirty
     * entry) runs UPDATE privacy on the unchanged candidate and then
     * throws `EntNoChangesException`; update defaults, validation,
     * the driver write, `afterUpdate`, and returned LOAD privacy are
     * all skipped because no state transition is persisted.
     *
     * **Internal current-row load.** Otherwise `save()` loads the
     * current owner row before any hook runs. The path branches on
     * the per-save `consistency` parameter (RFC #4):
     *
     * - `UpdateConsistency.ReadCurrent` (the default) reads the row
     *   via `driver.byId(id)` — no lock; another transaction may
     *   change or delete the row between this read and the write.
     * - `UpdateConsistency.Pessimistic` reads the row via
     *   `driver.readRowForUpdate(id)` — held under a true row lock
     *   until the surrounding transaction commits or rolls back,
     *   so the checked owner state stays stable through the write.
     *   Two preflights run before this load: a missing transaction
     *   throws `TransactionRequiredException` and a driver without
     *   `supportsReadRowForUpdate` throws
     *   `UnsupportedDriverCapabilityException`.
     *
     * Either path bypasses LOAD privacy. If the row no longer exists,
     * `save()` returns `null` before hooks, privacy, validation, or
     * the driver write. The loaded row is the privacy/validation
     * `before` and the fallback for untouched fields in the
     * after-state candidate.
     *
     * **Patch lowering.** The builder's `dirtyFields` are lowered into
     * a `${schemaName}UpdatePatch` (the requested patch). The same
     * helper builds a fresh per-hook snapshot before each
     * `beforeUpdate` invocation, then once more after the hook loop
     * for the canonical patch. Required-field/FK null checks run
     * after the hook loop so a hook can repair an explicit
     * `name = null` via `unsetName()` or by reassigning a value.
     *
     * **Defaults and write set.** Update defaults (e.g.
     * `updatedAt = updateDefaultNow()`) are applied to the canonical
     * requested patch to produce the effective patch — but only on
     * non-empty saves; the hook-cleared empty path skips defaults so
     * a default-only "real" update isn't synthesized. Only the
     * effective patch's `Set` entries are sent to `driver.update(...)`;
     * untouched columns are not round-tripped.
     *
     * **Rule visibility.** Privacy and validation rules see the loaded
     * `before`, the requested patch, the effective patch, and a full
     * after-state write candidate built by folding the effective
     * patch onto `before`.
     */
    private fun buildSaveFunction(
        schemaName: String,
        allFields: List<Field>,
        // Mutable-only — the update builder's writable surface. Used
        // everywhere except candidate construction.
        edgeFks: List<EdgeFk>,
        // All FKs including immutable. Used by candidate construction so
        // immutable FK values come from `entity.before` unchanged.
        allEdgeFks: List<EdgeFk>,
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
        // This also fires before the transaction-requirement preflight
        // below, matching the RFC #4 pipeline ("syntactically empty
        // update classification — report NoChanges before any other
        // observable work, including transaction requirement checks").
        builder.beginControlFlow("if (dirtyFields.isEmpty())")
        builder.addStatement(
            "throw %T(%T.NoChanges(%S, %T.UPDATE, id))",
            ENT_NO_CHANGES_EXCEPTION,
            ENT_ERROR,
            schemaName,
            ENT_OPERATION,
        )
        builder.endControlFlow()

        // ---- Transaction-requirement preflight (RFC #4). Throws
        // TransactionRequiredException before the owner-row load,
        // hooks, defaults, validation, driver writes when the
        // configured TransactionRequirement isn't satisfied. ----
        builder.addStatement("client.checkTransactionRequirement(%S)", "$schemaName update")

        // ---- Pessimistic preflight (RFC #4): require a transaction
        // and a driver with true row-lock support. Both rejections
        // fire before the owner-row load, hooks, privacy, validation,
        // or driver writes. ----
        builder.beginControlFlow(
            "if (consistency == %T.Pessimistic)",
            UPDATE_CONSISTENCY,
        )
        builder.beginControlFlow("if (!driver.inTransaction)")
        builder.addStatement(
            "throw %T(%S)",
            TRANSACTION_REQUIRED_EXCEPTION,
            "$schemaName Pessimistic update requires a transaction-scoped client",
        )
        builder.endControlFlow()
        builder.beginControlFlow("if (!driver.supportsReadRowForUpdate)")
        builder.addStatement(
            "throw %T(%S)",
            UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
            "$schemaName Pessimistic update requires a driver with supportsReadRowForUpdate = true",
        )
        builder.endControlFlow()
        builder.endControlFlow()

        // ---- Internal current-row load. Pessimistic uses
        // readRowForUpdate so the row is held under a true row lock
        // through the rest of the save; ReadCurrent uses byId (the
        // RFC #1 staleness-permitting path). Both bypass LOAD
        // privacy; missing rows short-circuit before hooks/privacy/
        // validation run. ----
        builder.beginControlFlow(
            "val row0 = if (consistency == %T.Pessimistic)",
            UPDATE_CONSISTENCY,
        )
        builder.addStatement(
            "driver.readRowForUpdate(%T.TABLE, id) ?: return null",
            entityClass,
        )
        builder.nextControlFlow("else")
        builder.addStatement(
            "driver.byId(%T.TABLE, id) ?: return null",
            entityClass,
        )
        builder.endControlFlow()
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
            "val ctx = %T(client, entity, snapshot, _mutationView)",
            updateHookCtxClass,
        )
        builder.addStatement("hook(ctx)")
        builder.endControlFlow()

        // ---- Required-null check (after hooks, before canonical patch). ----
        // Per the RFC's pipeline ordering, field-shape and required-edge
        // checks run after beforeUpdate hooks. A hook can repair an
        // explicit `name = null` assignment via `mutation.unsetName()`
        // (removes from dirtyFields) or by reassigning a value;
        // unrepaired assignments throw here.
        builder.addStatement("_checkRequiredNotNull()")

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
            edgeFks = allEdgeFks,
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
            emitPatchEntryValidation(builder, schemaName, field)
        }
        for (fk in edgeFks) {
            if (fk.validators.isEmpty()) continue
            // Backing-field validators apply to the FK on update too —
            // run them on Set entries of the effective patch.
            emitFkPatchEntryValidation(builder, schemaName, fk)
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
            edgeFks = allEdgeFks,
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
    private fun emitPatchEntryValidation(builder: FunSpec.Builder, schemaName: String, field: Field) {
        val prop = toCamelCase(field.name)
        val localName = "${prop}_eff"
        builder.addStatement("val %L = effectivePatch.%L", localName, prop)
        builder.beginControlFlow("if (%L is %T.Set)", localName, FIELD_PATCH)
        if (field.nullable) {
            builder.addStatement("val %L_v = %L.value", prop, localName)
            builder.beginControlFlow("if (%L_v != null)", prop)
            emitFieldValidation(builder, schemaName, "${prop}_v", field.name, field.validators, nullable = false)
            builder.endControlFlow()
        } else {
            builder.addStatement("val %L_v = %L.value", prop, localName)
            emitFieldValidation(builder, schemaName, "${prop}_v", field.name, field.validators, nullable = false)
        }
        builder.endControlFlow()
    }

    /**
     * Apply backing-field validators to a Set entry of the effective
     * patch for an FK. Mirrors [emitPatchEntryValidation] for scalars,
     * keyed off [EdgeFk.required] (whose nullability follows the
     * relationship) rather than scalar `field.nullable`.
     */
    private fun emitFkPatchEntryValidation(builder: FunSpec.Builder, schemaName: String, fk: EdgeFk) {
        val prop = fk.propertyName
        val localName = "${prop}_eff"
        builder.addStatement("val %L = effectivePatch.%L", localName, prop)
        builder.beginControlFlow("if (%L is %T.Set)", localName, FIELD_PATCH)
        if (!fk.required) {
            builder.addStatement("val %L_v = %L.value", prop, localName)
            builder.beginControlFlow("if (%L_v != null)", prop)
            emitFieldValidation(builder, schemaName, "${prop}_v", fk.columnName, fk.validators, nullable = false)
            builder.endControlFlow()
        } else {
            builder.addStatement("val %L_v = %L.value", prop, localName)
            emitFieldValidation(builder, schemaName, "${prop}_v", fk.columnName, fk.validators, nullable = false)
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
            if (fk.immutable) {
                // Immutable FKs are never in the patch — pull the
                // unchanged value straight from the loaded `before` row.
                code.add("  %L = entity.%L,\n", fk.propertyName, fk.propertyName)
            } else {
                code.add(
                    "  %L = effectivePatch.%L.%M(entity.%L),\n",
                    fk.propertyName, fk.propertyName, FIELD_PATCH_OR_ELSE, fk.propertyName,
                )
            }
        }
        code.add(")\n")
        builder.addCode(code.build())
    }

    /**
     * Explicit `OrNull` alias for the canonical [save] entry point. Per
     * the result-variants RFC, `saveOrNull()` returns `null` for
     * expected absence (missing owner row) and throws for everything
     * else — including [EntNoChangesException] for syntactically empty
     * updates, which is classified by request shape rather than
     * database state.
     */
    private fun buildSaveOrNullFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        return FunSpec.builder("saveOrNull")
            .returns(entityClass.copy(nullable = true))
            .addStatement("return save()")
            .build()
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

    /**
     * Structured-result variant: returns [EntResult.Ok] on success or
     * [EntResult.Err] for any recognized failure thrown by the save
     * path. Wraps `NotFound` / `NoChanges` (carried by [EntException])
     * plus the existing [PrivacyDeniedException] and
     * [ValidationException] into their matching [EntError] variants.
     * Constraint violations and driver/transaction errors still
     * propagate as their underlying exception types.
     */
    private fun buildSaveOrErrorFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val resultClass = ClassName("entkt.runtime", "EntResult")
        val entExceptionClass = ClassName("entkt.runtime", "EntException")
        val privacyDeniedClass = ClassName("entkt.runtime", "PrivacyDeniedException")
        val validationClass = ClassName("entkt.runtime", "ValidationException")
        val resultType = resultClass.parameterizedBy(entityClass)
        return FunSpec.builder("saveOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  %T.Ok(saveOrThrow())\n", resultClass)
                    .add("} catch (e: %T) {\n", entExceptionClass)
                    .add("  %T.Err(e.error)\n", resultClass)
                    .add("} catch (e: %T) {\n", privacyDeniedClass)
                    .add(
                        "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                        resultClass,
                        ENT_ERROR,
                        ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", validationClass)
                    .add(
                        "  %T.Err(%T.ValidationFailed(e.entity, %T.UPDATE, e.violations))\n",
                        resultClass,
                        ENT_ERROR,
                        ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
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
