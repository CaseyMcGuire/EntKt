package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import entkt.schema.EntSchema
import entkt.schema.Field

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val ENT_ERROR = ClassName("entkt.runtime.result", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val VALIDATION_EXCEPTION = ClassName("entkt.runtime.validation", "ValidationException")
private val VALIDATION_INVALID = ClassName("entkt.runtime.validation", "ValidationDecision", "Invalid")
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val ILLEGAL_STATE_EXCEPTION = ClassName("kotlin", "IllegalStateException")
private val PENDING_EDGE_OPS = ClassName("entkt.runtime.mutation", "PendingEdgeOps")
private val COMPUTE_EDGE_CHANGES = MemberName("entkt.runtime.mutation", "computeEdgeChanges")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP_CLASS = ClassName("entkt.query", "Op")


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

        // Helper-eligible link-table M2M edges. Each gets a
        // nested mutator class on the Update builder and a public
        // property bound to it. The mutator is NOT propagated to the
        // hook-facing `${schemaName}UpdateMutationView` — hooks see
        // pending edge ops through a read-only sidecar.
        val helperEligibleEdges = helperEligibleM2MEdges(schema, schemaNames)

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
                    .addParameter("relationshipLocking", RELATIONSHIP_LOCKING)
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
            // Per-save RelationshipLocking selected by the caller (or
            // inherited from the client's `defaultRelationshipLocking`).
            // Read by save() to decide whether to take the canonical
            // cross-orientation relationship lock for symmetric link-table
            // M2M writes.
            .addProperty(
                PropertySpec.builder("relationshipLocking", RELATIONSHIP_LOCKING)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("relationshipLocking")
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
            // Captured pendingEdges snapshot. The
            // adapter's `pendingEdges` getter routes through this field
            // so that ctx.mutation.pendingEdges returns the *same*
            // snapshot ctx.pendingEdges holds — not a freshly-rebuilt
            // value off the live mutator. save() populates this once
            // after the owner-row read and before any hook fires; the
            // getter throws if accessed before then (adapter touched
            // outside save context). Always emitted (even on schemas
            // without helper-eligible M2M edges) because the adapter's
            // pendingEdges getter is unconditional — UpdateMutationView
            // always carries pendingEdges typed against the (possibly
            // empty) per-entity aggregator.
            .addProperty(
                PropertySpec.builder(
                    "_capturedPendingEdges",
                    ClassName(packageName, "${schemaName}PendingEdgeOps").copy(nullable = true),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build(),
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
                    pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps"),
                ),
            )
            // Private `_beforeSaveView`
            // adapter that implements ONLY the shared `${Schema}Mutation`
            // interface — no `pendingEdges`, no `unsetX()`, none of the
            // update-specific patch operations. Passed to beforeSave
            // hooks so that even a `mutation as ${Schema}UpdateMutationView`
            // cast attempt fails at runtime, not just the more obvious
            // `mutation as ${Schema}Update`. beforeUpdate hooks
            // continue to receive `_mutationView` (the full update view)
            // via `ctx.mutation`.
            .addProperty(
                buildBeforeSaveAdapterProperty(
                    schemaName = schemaName,
                    mutationClass = mutationClass,
                    mutableFields = mutableFields,
                    edgeFks = edgeFks,
                ),
            )
            // Link-table M2M mutator properties. Public
            // DSL surface — callers reach add/remove/set through
            // `update(id) { tags.add(tagId) }`. Constructor is internal
            // so only this builder instantiates the mutator.
            .also { builder ->
                for (edge in helperEligibleEdges) {
                    builder.addProperty(buildEdgeMutatorProperty(edge, updateClass))
                }
            }
            .addFunction(buildBuildRequestedPatchFunction(schemaName, mutableFields, edgeFks))
            .addFunction(buildCheckRequiredNotNullFunction(schemaName, mutableFields, edgeFks))
            .addFunction(buildBuildPendingEdgeOpsFunction(schemaName, helperEligibleEdges))
            .addFunction(buildBuildEdgeChangesFunction(schemaName, helperEligibleEdges))
            // M2M preflight helpers. Only emitted when
            // the schema has at least one helper-eligible link-table
            // M2M edge — schemas without M2M skip the helpers and the
            // save-pipeline preflight block entirely.
            .also { builder ->
                if (helperEligibleEdges.isNotEmpty()) {
                    builder.addFunction(buildHasPendingLinkTableM2MOpsFunction(helperEligibleEdges))
                    builder.addFunction(buildHasPendingLinkTableM2MInsertsFunction(helperEligibleEdges))
                    builder.addFunction(buildCheckLinkTableM2MMixedModeFunction(helperEligibleEdges))
                }
            }
            .addFunction(buildSaveFunction(schemaName, allFields, edgeFks, allEdgeFks, helperEligibleEdges))
            .addFunction(buildSaveOrNullFunction(schemaName))
            .addFunction(buildSaveOrThrowFunction(schemaName))
            .addFunction(buildSaveOrErrorFunction(schemaName))
            // Nested mutator class declarations. Nested
            // inside the Update builder so two entities with the same
            // edge name don't collide on the mutator class symbol.
            .also { builder ->
                for (edge in helperEligibleEdges) {
                    builder.addType(buildEdgeMutatorType(edge))
                }
            }
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
            // Reading an untouched update field must throw (by contract). The
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
            //   - public property is non-null typed (by contract "Public Types")
            //   - private nullable staging field holds the value until assigned
            //   - getter throws on untouched read (by contract); reading
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
        pendingEdgeOpsClass: ClassName,
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
        // Read-only `pendingEdges`
        // forwarder routes through the captured snapshot on the
        // enclosing Update class, so ctx.mutation.pendingEdges returns
        // the SAME object ctx.pendingEdges holds. Rebuilding here
        // from live mutator state would (a) be redundant — hooks
        // can't mutate the underlying op log through documented
        // surfaces — but more importantly (b) silently diverge if
        // any future path (bulk-write codegen, reflection helpers,
        // reused Update instances) ever did reach the mutator
        // post-snapshot. The error path triggers only if the adapter
        // is touched outside a save context (no save() has populated
        // _capturedPendingEdges yet).
        adapter.addProperty(
            PropertySpec.builder("pendingEdges", pendingEdgeOpsClass)
                .addModifiers(KModifier.OVERRIDE)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement(
                            "return this@%L._capturedPendingEdges " +
                                "?: error(%S)",
                            updateClassName,
                            "pendingEdges accessed outside a save() — the captured snapshot is " +
                                "only populated during save() between the owner-row read and the " +
                                "afterUpdate hook block",
                        )
                        .build(),
                )
                .build(),
        )
        return PropertySpec.builder("_mutationView", updateMutationViewClass)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%L", adapter.build())
            .build()
    }

    /**
     * Build the private `_beforeSaveView`
     * adapter. Implements ONLY `${Schema}Mutation` — the shared
     * write surface — without the `pendingEdges` read, the
     * `unsetX()` patch operations, or any other update-specific
     * surface that `${Schema}UpdateMutationView` adds. beforeSave
     * hooks receive this adapter, so a misbehaving hook trying
     * `mutation as ${Schema}UpdateMutationView` fails at runtime.
     *
     * The adapter forwards each mutable scalar field and mutable
     * edge FK property to the outer Update builder, same as the
     * `_mutationView` forwarders.
     */
    private fun buildBeforeSaveAdapterProperty(
        schemaName: String,
        mutationClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): PropertySpec {
        val updateClassName = "${schemaName}Update"
        val adapter = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(mutationClass)
        for (field in mutableFields) {
            val propName = toCamelCase(field.name)
            val typeName = field.resolvedTypeName().copy(nullable = true)
            adapter.addProperty(buildAdapterForwarderProperty(updateClassName, propName, typeName))
        }
        for (fk in edgeFks) {
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            adapter.addProperty(buildAdapterForwarderProperty(updateClassName, fk.propertyName, typeName))
        }
        return PropertySpec.builder("_beforeSaveView", mutationClass)
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
     * is built — this matches the "field-shape checks after
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
     * Build the public `val ${edge}: ${Edge}EdgeMutator`
     * property on the Update builder. The mutator's constructor is
     * `internal`, so only this builder instantiates it; the public DSL
     * surface is `update(id) { tags.add(tagId) }`.
     */
    private fun buildEdgeMutatorProperty(
        edge: HelperEligibleM2M,
        updateClass: ClassName,
    ): PropertySpec {
        val mutatorClass = updateClass.nestedClass(edge.mutatorClassSimpleName)
        return PropertySpec.builder(edge.mutatorPropertyName, mutatorClass)
            .initializer("%T()", mutatorClass)
            .build()
    }

    /**
     * Build the nested `${Edge}EdgeMutator` class that
     * carries the per-edge op log and exposes the public id-only
     * `add(id)` / `remove(id)` / `set(ids)` mutator surface.
     *
     * The op-log fields are `private` so even same-module code can't
     * bypass the per-call invariants by writing `tags._adds.add(...)`
     * directly. Downstream codegen consumes the state through three
     * `internal` accessor methods on the mutator:
     *
     *  - `hasOps(): Boolean` — gate for `_hasPendingLinkTableM2MOps` /
     *    junction-read decision.
     *  - `snapshotOps(): PendingEdgeOps<ID>` — used by
     *    `_buildPendingEdgeOps` to materialize the immutable
     *    aggregator the hook context / privacy / validation surfaces
     *    consume.
     *  - `validateInvariants()` — used by the save-preflight
     *    defense-in-depth check (`_checkLinkTableM2MMixedMode`) to
     *    re-assert both mixed-mode rules against captured state.
     *
     * The constructor is `internal` so callers cannot construct a
     * standalone mutator outside the builder.
     *
     * Two mixed-mode rules fire at the call site:
     *
     *  1. **Replacement vs delta** — `set(...)` and `add(...)` /
     *     `remove(...)` are mutually exclusive within one mutation for
     *     a given edge.
     *  2. **Same-id mixed-direction** — within delta mode, an id may
     *     be the subject of `add(...)` calls *or* `remove(...)` calls,
     *     but not both. `add(a); remove(a)` and the reverse both
     *     throw at the second call.
     *
     * Both throw `IllegalStateException` with a message naming the
     * edge and the conflicting operations. The save-preflight
     * `validateInvariants()` is the backstop for reflection or future
     * generated bulk-write code that could bypass per-call guards;
     * with `private` field visibility there's no documented in-module
     * path that needs the backstop, but it stays as defense-in-depth.
     */
    private fun buildEdgeMutatorType(edge: HelperEligibleM2M): TypeSpec {
        val idType = edge.targetIdTypeName
        val listOfId = LIST.parameterizedBy(idType)
        val mutableListOfId = MUTABLE_LIST.parameterizedBy(idType)
        val pendingEdgeOpsParamed = PENDING_EDGE_OPS.parameterizedBy(idType)
        val mixedModeMessage = "edge '${edge.edgeName}': cannot mix replacement (set) and " +
            "delta (add/remove) operations in one mutation"
        val sameIdAddAfterRemoveMessage = "edge '${edge.edgeName}': cannot add(id) after " +
            "remove(id) for the same id in one mutation"
        val sameIdRemoveAfterAddMessage = "edge '${edge.edgeName}': cannot remove(id) after " +
            "add(id) for the same id in one mutation"
        val sameIdOverlapMessage = "edge '${edge.edgeName}': delta add/remove sets overlap on " +
            "one or more ids — `add(x)` and `remove(x)` for the same x must not coexist"

        return TypeSpec.classBuilder(edge.mutatorClassSimpleName)
            .addKdoc(
                "Link-table M2M mutator for `%L`. Public DSL surface\n" +
                    "is `add(id)` / `remove(id)` / `set(ids)`. Two mixed-mode rules\n" +
                    "fire fail-fast at the call site: replacement-vs-delta (`set` and\n" +
                    "`add`/`remove` are mutually exclusive) and same-id mixed-direction\n" +
                    "(`add(x)` after `remove(x)` and the reverse are rejected). Both\n" +
                    "throw `IllegalStateException`.\n\n" +
                    "Op-log fields are `private` to prevent same-module bypass; downstream\n" +
                    "codegen uses the `internal` accessor methods (`hasOps`,\n" +
                    "`snapshotOps`, `validateInvariants`) instead of reaching into the\n" +
                    "raw lists.",
                edge.edgeName,
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .build(),
            )
            // Op log — `private`. Same-module application code can't
            // bypass the per-call invariants by reaching into these
            // fields. Downstream codegen accesses state through the
            // internal accessors below.
            .addProperty(
                PropertySpec.builder("_requestedSet", listOfId.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("_adds", mutableListOfId)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("_removes", mutableListOfId)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("add")
                    .addParameter("id", idType)
                    .addStatement(
                        "if (_requestedSet != null) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, mixedModeMessage,
                    )
                    .addStatement(
                        "if (_removes.contains(id)) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, sameIdAddAfterRemoveMessage,
                    )
                    .addStatement("_adds.add(id)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("remove")
                    .addParameter("id", idType)
                    .addStatement(
                        "if (_requestedSet != null) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, mixedModeMessage,
                    )
                    .addStatement(
                        "if (_adds.contains(id)) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, sameIdRemoveAfterAddMessage,
                    )
                    .addStatement("_removes.add(id)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("set")
                    .addParameter("ids", listOfId)
                    .addStatement(
                        "if (_adds.isNotEmpty() || _removes.isNotEmpty()) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, mixedModeMessage,
                    )
                    // Defensive copy at entry: store our own immutable
                    // snapshot of the caller's list rather than aliasing
                    // it. Without this, a caller who passes a MutableList
                    // and mutates it between `tags.set(...)` and `save()`
                    // would silently change the persisted relationship —
                    // surprising for a replacement operation and
                    // inconsistent with `add(...)` / `remove(...)`, which
                    // copy by value (Long/UUID id).
                    .addStatement("_requestedSet = ids.toList()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("hasOps")
                    .addModifiers(KModifier.INTERNAL)
                    .returns(BOOLEAN)
                    .addStatement(
                        "return _requestedSet != null || _adds.isNotEmpty() || _removes.isNotEmpty()",
                    )
                    .build(),
            )
            .addFunction(
                // True when this edge has a pending op that can
                // INSERT a junction row — any `add(...)` or `set(...)`.
                // Remove-only mutations cannot insert, so they stay false
                // and skip the `supportsInsertIgnore` preflight. This is a
                // conservative over-approximation: `set(ids)` counts even if
                // the diff turns out to insert nothing, since the actual
                // added set is only known after the current-state read.
                FunSpec.builder("hasInserts")
                    .addModifiers(KModifier.INTERNAL)
                    .returns(BOOLEAN)
                    .addStatement("return _requestedSet != null || _adds.isNotEmpty()")
                    .build(),
            )
            .addFunction(
                // Materializes the immutable PendingEdgeOps snapshot
                // consumed by _buildPendingEdgeOps. Replaces the
                // previous inline field reads in the aggregator
                // constructor; with private fields the codegen can't
                // inline them anyway.
                FunSpec.builder("snapshotOps")
                    .addModifiers(KModifier.INTERNAL)
                    .returns(pendingEdgeOpsParamed)
                    .addStatement(
                        "return %T(\n" +
                            "  requestedSet = _requestedSet?.toSet(),\n" +
                            "  requestedAdds = _adds.toSet(),\n" +
                            "  requestedRemoves = _removes.toSet(),\n" +
                            ")",
                        PENDING_EDGE_OPS,
                    )
                    .build(),
            )
            .addFunction(
                // Save-preflight defense-in-depth check. Re-runs both
                // mixed-mode rules against the captured op-log state.
                // Replaces the inlined field-access checks in the
                // previous _checkLinkTableM2MMixedMode helper, which
                // can no longer access the private fields directly.
                FunSpec.builder("validateInvariants")
                    .addModifiers(KModifier.INTERNAL)
                    .addStatement(
                        "if (_requestedSet != null && (_adds.isNotEmpty() || _removes.isNotEmpty())) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, mixedModeMessage,
                    )
                    .addStatement(
                        "if ((_adds.toSet() intersect _removes.toSet()).isNotEmpty()) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, sameIdOverlapMessage,
                    )
                    .build(),
            )
            .build()
    }

    /**
     * Build `_hasPendingLinkTableM2MOps()`. ORs each
     * helper-eligible mutator's `hasOps()` flag — the gate for the
     * M2M preflight in save(). Generated only when the schema has at
     * least one helper-eligible link-table M2M edge.
     */
    private fun buildHasPendingLinkTableM2MOpsFunction(
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec {
        val builder = FunSpec.builder("_hasPendingLinkTableM2MOps")
            .addModifiers(KModifier.PRIVATE)
            .returns(BOOLEAN)
        // helperEligibleEdges is non-empty (caller-side guard).
        val expr = helperEligibleEdges.joinToString(" || ") { "this.${it.mutatorPropertyName}.hasOps()" }
        builder.addStatement("return %L", expr)
        return builder.build()
    }

    /**
     * Build `_hasPendingLinkTableM2MInserts()`. ORs each
     * helper-eligible mutator's `hasInserts()` flag — the gate for the
     * `supportsInsertIgnore` capability preflight in save(). True when any
     * edge has a pending `add` or `set`; remove-only saves stay false and
     * are exempt from the preflight (they never call `insertIgnore`).
     * Generated only when the schema has at least one helper-eligible
     * link-table M2M edge.
     */
    private fun buildHasPendingLinkTableM2MInsertsFunction(
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec {
        val builder = FunSpec.builder("_hasPendingLinkTableM2MInserts")
            .addModifiers(KModifier.PRIVATE)
            .returns(BOOLEAN)
        // helperEligibleEdges is non-empty (caller-side guard).
        val expr = helperEligibleEdges.joinToString(" || ") { "this.${it.mutatorPropertyName}.hasInserts()" }
        builder.addStatement("return %L", expr)
        return builder.build()
    }

    /**
     * Build `_checkLinkTableM2MMixedMode()`, the
     * defense-in-depth check that re-runs the per-mutator-call mixed-mode
     * rule against the captured op-log state at save preflight. The per-call
     * check on the mutator methods is the fail-fast surface; this is the
     * backstop for setter-bypassing paths (reflection writing the op
     * lists directly, or future bulk-write helpers). Throws the same
     * `IllegalStateException` message shape as the per-call check.
     *
     * Generated only when the schema has at least one helper-eligible
     * link-table M2M edge.
     */
    private fun buildCheckLinkTableM2MMixedModeFunction(
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec {
        // The two mixed-mode invariants live on the mutator itself
        // (`validateInvariants()`) now that the op-log fields are
        // private. This helper just dispatches per edge. Per-call
        // guards in add()/remove()/set() reject violations at the
        // mutator surface under normal usage; this preflight is the
        // defense-in-depth backstop for state that bypassed those
        // guards (reflection or future bulk-write codegen that
        // sidestepped the per-call rule).
        val builder = FunSpec.builder("_checkLinkTableM2MMixedMode")
            .addModifiers(KModifier.PRIVATE)
        for (edge in helperEligibleEdges) {
            builder.addStatement("this.%L.validateInvariants()", edge.mutatorPropertyName)
        }
        return builder.build()
    }

    /**
     * Build `_buildPendingEdgeOps()`, a private method
     * that snapshots each per-edge mutator's op log into the per-entity
     * `${Schema}PendingEdgeOps` aggregator. The result is the read-only
     * view that hooks see through `ctx.pendingEdges` and
     * `ctx.mutation.pendingEdges`.
     *
     * Dedup happens inside the mutator's `snapshotOps()` accessor,
     * which converts the private `_requestedSet: List<ID>?` / `_adds`
     * / `_removes` lists into the immutable `PendingEdgeOps<ID>`
     * sets. By the time this generator emits, the op-log fields are
     * private — the codegen calls `snapshotOps()` rather than reaching
     * into the fields directly.
     *
     * For schemas with no helper-eligible M2M edges the aggregator is
     * empty (no constructor parameters), so this just returns
     * `${Schema}PendingEdgeOps()`.
     */
    private fun buildBuildPendingEdgeOpsFunction(
        schemaName: String,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec {
        val pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps")
        val builder = FunSpec.builder("_buildPendingEdgeOps")
            .addModifiers(KModifier.PRIVATE)
            .returns(pendingEdgeOpsClass)

        if (helperEligibleEdges.isEmpty()) {
            builder.addStatement("return %T()", pendingEdgeOpsClass)
            return builder.build()
        }

        val code = CodeBlock.builder()
        code.add("return %T(\n", pendingEdgeOpsClass)
        for (edge in helperEligibleEdges) {
            val prop = edge.mutatorPropertyName
            code.add("  %L = this.%L.snapshotOps(),\n", prop, prop)
        }
        code.add(")\n")
        builder.addCode(code.build())
        return builder.build()
    }

    /**
     * Build `_buildEdgeChanges()`, the private method
     * that reads current junction rows for each helper-eligible edge
     * with pending ops and computes the per-entity `${Schema}EdgeChangesView`
     * sidecar surfaced through update privacy and validation contexts.
     *
     * Per-edge logic:
     *  - When the edge has no pending ops, skip the junction read and
     *    emit an empty `EdgeChanges()` so the database round-trip is
     *    only paid for edges the caller actually touched.
     *  - When the edge has pending ops, read the junction table for
     *    rows whose source FK matches the owner id, deduplicate the
     *    target ids into a set, and delegate to
     *    [entkt.runtime.mutation.computeEdgeChanges] for the actual added /
     *    removed delta.
     *
     * For schemas with zero helper-eligible M2M edges the function just
     * returns `${Schema}EdgeChangesView()` (no junction work).
     */
    private fun buildBuildEdgeChangesFunction(
        schemaName: String,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec {
        val edgeChangesViewClass = ClassName(packageName, "${schemaName}EdgeChangesView")
        val pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps")
        val builder = FunSpec.builder("_buildEdgeChanges")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("pendingEdges", pendingEdgeOpsClass)
            .returns(edgeChangesViewClass)

        if (helperEligibleEdges.isEmpty()) {
            builder.addStatement("return %T()", edgeChangesViewClass)
            return builder.build()
        }

        val code = CodeBlock.builder()
        for (edge in helperEligibleEdges) {
            val prop = edge.mutatorPropertyName
            val currentVar = "_current_${prop}"
            val targetIdType = edge.targetIdTypeName
            // Read current junction state only when the edge has pending ops.
            // Junction-table query: predicate has no entity scope.
            // Predicate.Leaf<Any> renders the same structural data
            // (field/op/value) and erases at the driver boundary.
            code.add(
                "val %L: Set<%T> = if (this.%L.hasOps()) {\n" +
                    "  driver.query(%S, listOf(%T.Leaf<%T>(%S, %T.EQ, this.id)), emptyList(), null, null)\n" +
                    "    .map { it[%S] as %T }\n" +
                    "    .toSet()\n" +
                    "} else emptySet()\n",
                currentVar, targetIdType, prop,
                edge.junctionTable, PREDICATE, Any::class.asClassName(), edge.junctionSourceColumn, OP_CLASS,
                edge.junctionTargetColumn, targetIdType,
            )
        }
        code.add("return %T(\n", edgeChangesViewClass)
        for (edge in helperEligibleEdges) {
            val prop = edge.mutatorPropertyName
            val currentVar = "_current_${prop}"
            code.add(
                "  %L = %M(pendingEdges.%L, %L),\n",
                prop, COMPUTE_EDGE_CHANGES, prop, currentVar,
            )
        }
        code.add(")\n")
        builder.addCode(code.build())
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
     * the per-save `consistency` parameter (transaction locking):
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
        // Helper-eligible link-table M2M edges. When
        // non-empty, save() emits an M2M preflight block (tx +
        // capability + defense-in-depth mixed-mode) before the
        // owner-row read.
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec = UpdateSaveEmitter(packageName, schemaName, allFields, edgeFks, allEdgeFks, helperEligibleEdges).build()


    /**
     * Explicit `OrNull` alias for the canonical [save] entry point. Per
     * the result variants, `saveOrNull()` returns `null` for
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
     * Throwing variant: delegates to [saveOrError] and unwraps via
     * [EntResult.getOrThrow] so callers get a structured
     * [EntException] subclass for every recognized failure surface,
     * including driver-classified constraint failures.
     *
     * Implemented as a wrapper over `saveOrError()` to keep the
     * NotFound, NoChanges, privacy, validation, and driver-classifier
     * mapping in one place rather than duplicating between the trio's
     * throwing and result variants.
     */
    private fun buildSaveOrThrowFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        return FunSpec.builder("saveOrThrow")
            .returns(entityClass)
            .addStatement(
                "return saveOrError().%M()",
                MemberName("entkt.runtime.result", "getOrThrow"),
            )
            .build()
    }

    /**
     * Structured-result variant: returns [EntResult.Ok] on success or
     * [EntResult.Err] for any recognized failure thrown by the save
     * path. Wraps the OrNull `save()` returning `null` for the
     * vanished-owner-row case into `Err(NotFound)`, `NoChanges`
     * (carried by [EntException]) and the existing
     * [PrivacyDeniedException] and [ValidationException] into their
     * matching [EntError] variants.
     *
     * The trailing `catch (e: Exception)` arm routes uncaught
     * exceptions through [classifyDriverError] so the driver-level
     * classifier emits `Err(ConstraintViolation)` for
     * SQLSTATE 23xxx (Postgres), falling back to `Err(DriverFailure)`
     * with the raw cause attached.
     *
     * [Exception] (not [Throwable]) is the floor: [Error] subclasses
     * (OOME, StackOverflowError) propagate untouched.
     * `TransactionRequiredException` / `UnsupportedDriverCapabilityException`
     * are deterministic programming/configuration errors and re-thrown
     * by [classifyDriverError] rather than wrapped — they escape
     * `saveOrError` as themselves, the same way they escape
     * `saveOrThrow`. Genuine programming bugs from hooks/rules (e.g.
     * `IllegalStateException`, `IllegalArgumentException`, vanilla
     * `RuntimeException`) also re-throw: the classifier only wraps
     * `SQLException` (and subclasses like `PSQLException`) as
     * `DriverFailure`. See `classifyDriverError`'s KDoc for the
     * complete fallback ruleset.
     */
    private fun buildSaveOrErrorFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val resultClass = ClassName("entkt.runtime.result", "EntResult")
        val entExceptionClass = ClassName("entkt.runtime.result", "EntException")
        val privacyDeniedClass = ClassName("entkt.runtime.privacy", "PrivacyDeniedException")
        val validationClass = ClassName("entkt.runtime.validation", "ValidationException")
        val resultType = resultClass.parameterizedBy(entityClass)
        return FunSpec.builder("saveOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add(
                        "  save()?.let { %T.Ok(it) } ?: %T.Err(%T.NotFound(%S, %T.UPDATE, id))\n",
                        resultClass,
                        resultClass,
                        ENT_ERROR,
                        schemaName,
                        ENT_OPERATION,
                    )
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
                        // Map the legacy ValidationDecision.Invalid (the
                        // validation-rule DSL's native shape) into the
                        // canonical EntError.ValidationFailed wire-level
                        // ValidationViolation type — the two carry the
                        // same three fields, and toValidationViolation
                        // is a stable mapper exposed from the runtime.
                        "  %T.Err(%T.ValidationFailed(e.entity, %T.UPDATE, e.violations.map { it.%M() }))\n",
                        resultClass,
                        ENT_ERROR,
                        ENT_OPERATION,
                        MemberName("entkt.runtime.result", "toValidationViolation"),
                    )
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.UPDATE))\n",
                        resultClass,
                        MemberName("entkt.runtime.driver", "classifyDriverError"),
                        schemaName,
                        ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }
}
