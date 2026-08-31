package entkt.codegen.mutation

import entkt.codegen.apiName
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
import entkt.codegen.columnName
import entkt.codegen.lifecyclePatchSnapshot
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.HelperEligibleM2M
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.helperEligibleM2MEdges
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.stagingFieldName
import entkt.codegen.metadata.toTypeName
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.anonymousType
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.getter
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.setter
import entkt.codegen.kotlinpoet.statement
import entkt.schema.EntSchema
import entkt.schema.Field

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val ENT_CLIENT_NAME = "EntClient"
private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val UPDATE_MUTATION_DRAFT =
    ClassName("entkt.runtime.mutation", "UpdateMutationDraft")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val ILLEGAL_STATE_EXCEPTION = ClassName("kotlin", "IllegalStateException")
private val PENDING_EDGE_OPS = ClassName("entkt.runtime.mutation", "PendingEdgeOps")
private val COMPUTE_EDGE_CHANGES = MemberName("entkt.runtime.mutation", "computeEdgeChanges")
private val IMMUTABLE_SET_SNAPSHOT =
    MemberName("entkt.runtime.mutation", "immutableSetSnapshotForInternalUse")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP_CLASS = ClassName("entkt.query", "Op")
private val UPDATE_MUTATION_REQUEST =
    ClassName("entkt.runtime.mutation", "UpdateMutationRequest")


internal class UpdateGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}UpdateDraft"
        // Backing FK columns are emitted via `edgeFks` so they pick up
        // relationship-FK semantics (non-null type, throw-on-untouched,
        // setter null-reject). Don't double-emit them as scalar fields.
        val allFields = scalarFields(schema)
        val mutableFields = allFields.filter { !it.immutable }
        // Immutable FKs are create-only — the update draft, the
        // hook-facing view, and the patch must not expose a write path
        // for them. `allEdgeFks` is used only for candidate construction
        // (which carries the unchanged value from `entity.before`).
        val allEdgeFks = computeEdgeFks(schema, schemaNames)
        val edgeFks = allEdgeFks.filter { !it.immutable }

        val entityClass = ClassName(packageName, schemaName)
        val draftClass = ClassName(packageName, className)
        val adapterClass = ClassName(packageName, "${schemaName}UpdateAdapter")
        val preparedStateClass = adapterClass.nestedClass("PreparedState")
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val updateMutationViewClass = ClassName(packageName, "${schemaName}UpdateMutationView")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
        val idType = schema.id().type.toTypeName()

        val beforeSaveHookType = hookListType(mutationClass)
        val beforeUpdateHookType = hookListType(updateHookCtxClass)
        val afterUpdateHookType = hookListType(entityClass)

        // Helper-eligible link-table M2M edges. Each gets a
        // nested mutator class on the update draft and a public
        // property bound to it. The mutator is NOT propagated to the
        // hook-facing `${schemaName}UpdateMutationView` — hooks see
        // pending edge ops through a read-only sidecar.
        val helperEligibleEdges = helperEligibleM2MEdges(schema, schemaNames)
        val saveArtifacts = UpdateSaveEmitter(
            packageName = packageName,
            schemaName = schemaName,
            clientName = schema.clientName,
            preparedStateClass = preparedStateClass,
            allFields = allFields,
            edgeFks = edgeFks,
            allEdgeFks = allEdgeFks,
            helperEligibleEdges = helperEligibleEdges,
        ).build()

        val draftType = classType(className) {
            addAnnotation(annotation(ENTKT_DSL))
            addSuperinterface(mutationClass)
            addSuperinterface(UPDATE_MUTATION_DRAFT.parameterizedBy(entityClass))
            primaryConstructor {
                addAnnotation(ENTKT_INTERNAL)
            }
            property(
                "dirtyFields",
                ClassName("kotlin.collections", "MutableSet").parameterizedBy(STRING),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer("mutableSetOf()")
            }
            addProperties(mutableFields.map { buildProperty(it) })
            run {
                for (fk in edgeFks) {
                    if (fk.required) {
                        addProperty(buildRequiredFkStagingProperty(fk))
                    }
                }
            }
            addProperties(edgeFks.map { buildEdgeFkProperty(it) })
            run {
                for (edge in helperEligibleEdges) {
                    addProperty(buildEdgeMutatorProperty(edge, draftClass))
                }
            }
            addFunction(buildBuildRequestedPatchFunction(schemaName, mutableFields, edgeFks))
            addFunction(buildCheckRequiredNotNullFunction(schemaName, mutableFields, edgeFks))
            addFunction(buildBuildPendingEdgeOpsFunction(schemaName, helperEligibleEdges))
            addFunction(buildHasFieldAssignmentsFunction())
            addFunction(buildUnsetFieldFunction())
            run {
                if (helperEligibleEdges.isNotEmpty()) {
                    addFunction(buildHasPendingLinkTableM2MOpsFunction(helperEligibleEdges))
                    addFunction(buildHasPendingLinkTableM2MInsertsFunction(helperEligibleEdges))
                    addFunction(buildCheckLinkTableM2MMixedModeFunction(helperEligibleEdges))
                }
            }
            run {
                for (edge in helperEligibleEdges) {
                    addType(buildEdgeMutatorType(edge))
                }
            }
        }

        val requestType = UPDATE_MUTATION_REQUEST.parameterizedBy(draftClass)
        val executionType = classType("Execution") {
            addModifiers(KModifier.PRIVATE, KModifier.INNER)
            primaryConstructor { parameter("request", requestType) }
            property("request", requestType) {
                addModifiers(KModifier.PRIVATE)
                initializer("request")
            }
            property("draft", draftClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("request.draft")
            }
            property("id", idType) {
                addModifiers(KModifier.PRIVATE)
                initializer("request.id as %T", idType)
            }
            property("consistency", UPDATE_CONSISTENCY) {
                addModifiers(KModifier.PRIVATE)
                initializer("request.consistency")
            }
            property("relationshipLocking", RELATIONSHIP_LOCKING) {
                addModifiers(KModifier.PRIVATE)
                initializer("request.relationshipLocking")
            }
            property("entity", entityClass) {
                addModifiers(KModifier.PRIVATE, KModifier.LATEINIT)
                mutable(true)
            }
            property(
                "_capturedPendingEdges",
                ClassName(packageName, "${schemaName}PendingEdgeOps").copy(nullable = true),
            ) {
                addModifiers(KModifier.PRIVATE)
                mutable(true)
                initializer("null")
            }
            addProperty(
                buildMutationViewProperty(
                    outerClassName = "Execution",
                    updateMutationViewClass = updateMutationViewClass,
                    mutableFields = mutableFields,
                    edgeFks = edgeFks,
                    pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps"),
                ),
            )
            addProperty(
                buildBeforeSaveAdapterProperty(
                    outerClassName = "Execution",
                    mutationClass = mutationClass,
                    mutableFields = mutableFields,
                    edgeFks = edgeFks,
                ),
            )
            addFunction(buildBuildEdgeChangesFunction(schemaName, helperEligibleEdges))
            addProperty(saveArtifacts.specProperty)
            saveArtifacts.functions.forEach(::addFunction)
        }

        val adapterType = classType(adapterClass.simpleName) {
            addModifiers(KModifier.INTERNAL)
            primaryConstructor {
                parameter("driver", DRIVER)
                parameter("client", clientClass)
                parameter("beforeSaveHooks", beforeSaveHookType)
                parameter("beforeUpdateHooks", beforeUpdateHookType)
                parameter("afterUpdateHooks", afterUpdateHookType)
            }
            property("driver", DRIVER) {
                addModifiers(KModifier.PRIVATE)
                initializer("driver")
            }
            property("client", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            property("beforeSaveHooks", beforeSaveHookType) {
                addModifiers(KModifier.PRIVATE)
                initializer("beforeSaveHooks")
            }
            property("beforeUpdateHooks", beforeUpdateHookType) {
                addModifiers(KModifier.PRIVATE)
                initializer("beforeUpdateHooks")
            }
            property("afterUpdateHooks", afterUpdateHookType) {
                addModifiers(KModifier.PRIVATE)
                initializer("afterUpdateHooks")
            }
            function("execute", MUTATION_RESULT.parameterizedBy(entityClass)) {
                addModifiers(KModifier.INTERNAL)
                parameter("viewerContext", VIEWER_CONTEXT)
                parameter("request", requestType)
                parameter("applyLoadPrivacy", BOOLEAN)
                statement("return Execution(request).execute(viewerContext, applyLoadPrivacy)")
            }
            addType(saveArtifacts.preparedStateType)
            addType(executionType)
        }

        return kotlinFile(packageName, className) {
            addAnnotation(entktInternalFileOptIn())
            addType(draftType)
            addType(adapterType)
        }
    }

    private fun buildProperty(field: Field): PropertySpec {
        val prop = field.apiName
        val typeName = field.resolvedTypeName().copy(nullable = true)
        return property(prop, typeName) {
            addModifiers(KModifier.OVERRIDE)
            mutable(true)
            initializer("null")
            // Reading an untouched update field must throw (by contract). The
            // draft has no current-state value before save(); for nullable
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
            getter {
                statement(
                        "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                        prop,
                        "$prop is not set in this update; read ctx.patch.$prop instead",
                )
                statement("return field")
            }
            setter {
                parameter("value", typeName)
                statement("field = value")
                statement("dirtyFields.add(%S)", prop)
            }
            field.comment?.let { addKdoc("%L", it) }
        }
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
            return property(fk.propertyName, nonNullType) {
                addModifiers(KModifier.OVERRIDE)
                mutable(true)
                getter {
                    statement(
                            "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                            fk.propertyName,
                            "${fk.propertyName} is not set in this update; read ctx.patch.${fk.propertyName} instead",
                        )
                    statement(
                            "return %L ?: throw IllegalStateException(%S)",
                            stagingName,
                            "${fk.propertyName} is required",
                        )
                }
                setter {
                    parameter("value", nonNullType)
                    addAnnotation(annotation(Suppress::class.asClassName()) {
                        addMember("%S", "SENSELESS_COMPARISON")
                    })
                    statement(
                            "requireNotNull(value) { %S }",
                            "${fk.propertyName} is required",
                        )
                    statement("%L = value", stagingName)
                    statement("dirtyFields.add(%S)", fk.propertyName)
                }
                fk.comment?.let { addKdoc("%L", it) }
            }
        }
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        return property(fk.propertyName, typeName) {
            addModifiers(KModifier.OVERRIDE)
            mutable(true)
            initializer("null")
            getter {
                statement(
                        "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                        fk.propertyName,
                        "${fk.propertyName} is not set in this update; read ctx.patch.${fk.propertyName} instead",
                    )
                statement("return field")
            }
            setter {
                parameter("value", typeName)
                statement("field = value")
                statement("dirtyFields.add(%S)", fk.propertyName)
            }
            fk.comment?.let { addKdoc("%L", it) }
        }
    }

    private fun buildRequiredFkStagingProperty(fk: EdgeFk): PropertySpec {
        val nullableType = fk.idType.toTypeName().copy(nullable = true)
        return property(stagingFieldName(fk.propertyName), nullableType) {
            addModifiers(KModifier.PRIVATE)
            mutable(true)
            initializer("null")
        }
    }

    /**
     * Build the private `_mutationView` adapter that hooks see as
     * `ctx.mutation`. The adapter implements [updateMutationViewClass]
     * by forwarding each [mutationClass] field/FK property to the
     * execution's draft (so reads go through the throw-on-untouched
     * getter and writes flow through the dirty-tracking setter) and
     * declaring `unset{Field}()` overrides that clear the corresponding
     * draft assignment. Keeping the view behind a private
     * property keeps `unset` off the public DSL surface.
     */
    private fun buildMutationViewProperty(
        outerClassName: String,
        updateMutationViewClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
        pendingEdgeOpsClass: ClassName,
    ): PropertySpec {
        val adapter = anonymousType {
            addSuperinterface(updateMutationViewClass)
            // Forward each mutation field/FK property to the operation draft.
            for (field in mutableFields) {
                val propName = field.apiName
                val typeName = field.resolvedTypeName().copy(nullable = true)
                addProperty(buildAdapterForwarderProperty(outerClassName, propName, typeName))
            }
            for (fk in edgeFks) {
                // Forwarder property type matches the interface (required → non-null).
                val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
                addProperty(buildAdapterForwarderProperty(outerClassName, fk.propertyName, typeName))
            }
            for (field in mutableFields) {
                addFunction(buildAdapterUnsetFunction(outerClassName, field.apiName))
            }
            for (fk in edgeFks) {
                addFunction(buildAdapterUnsetFunction(outerClassName, fk.propertyName))
            }
            // Route pendingEdges through the execution's captured snapshot so the
            // hook context and mutation view expose the same immutable value.
            property("pendingEdges", pendingEdgeOpsClass) {
                addModifiers(KModifier.OVERRIDE)
                getter {
                    statement(
                            "return this@%L._capturedPendingEdges " +
                                "?: error(%S)",
                            outerClassName,
                            "pendingEdges accessed outside a save() — the captured snapshot is " +
                                "only populated during save() between the owner-row read and the " +
                                "afterUpdate hook block",
                        )
                }
            }
        }
        return property("_mutationView", updateMutationViewClass) {
            addModifiers(KModifier.PRIVATE)
            initializer("%L", adapter)
        }
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
     * edge FK property to the execution's update draft, same as the
     * `_mutationView` forwarders.
     */
    private fun buildBeforeSaveAdapterProperty(
        outerClassName: String,
        mutationClass: ClassName,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): PropertySpec {
        val adapter = anonymousType {
            addSuperinterface(mutationClass)
            for (field in mutableFields) {
                val propName = field.apiName
                val typeName = field.resolvedTypeName().copy(nullable = true)
                addProperty(buildAdapterForwarderProperty(outerClassName, propName, typeName))
            }
            for (fk in edgeFks) {
                val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
                addProperty(buildAdapterForwarderProperty(outerClassName, fk.propertyName, typeName))
            }
        }
        return property("_beforeSaveView", mutationClass) {
            addModifiers(KModifier.PRIVATE)
            initializer("%L", adapter)
        }
    }

    private fun buildAdapterForwarderProperty(
        outerClassName: String,
        prop: String,
        typeName: com.squareup.kotlinpoet.TypeName,
    ): PropertySpec {
        return property(prop, typeName) {
            addModifiers(KModifier.OVERRIDE)
            mutable(true)
            getter { statement("return this@%L.draft.%L", outerClassName, prop) }
            setter {
                parameter("value", typeName)
                statement("this@%L.draft.%L = value", outerClassName, prop)
            }
        }
    }

    private fun buildAdapterUnsetFunction(outerClassName: String, prop: String): FunSpec {
        val name = "unset${prop.replaceFirstChar { it.uppercaseChar() }}"
        return function(name) {
            addModifiers(KModifier.OVERRIDE)
            statement("this@%L.draft._unsetFieldForInternalUse(%S)", outerClassName, prop)
        }
    }

    /**
     * Generate an internal `_buildRequestedPatch()` member that constructs
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
        val code = codeBlock {
            add("val snapshot = %T(\n", patchClass)
            for (field in mutableFields) {
                val prop = field.apiName
                if (field.nullable) {
                    // Nullable: Set(this.foo) — Set(null) is an explicit clear.
                    add(
                        "  %L = if (%S in dirtyFields) %T.Set(this.%L) else %T.Unset,\n",
                        prop, prop, FIELD_PATCH, prop, FIELD_PATCH,
                    )
                } else {
                    // Required: skip the Set entry if the value is null. The
                    // post-hook required-null check (called from save()
                    // before the canonical patch is built) catches unrepaired
                    // nulls.
                    add(
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
                    add(
                        "  %L = if (%S in dirtyFields && this.%L != null) %T.Set(this.%L!!) else %T.Unset,\n",
                        fk.propertyName, fk.propertyName, stagingName, FIELD_PATCH, stagingName, FIELD_PATCH,
                    )
                } else {
                    add(
                        "  %L = if (%S in dirtyFields) %T.Set(this.%L) else %T.Unset,\n",
                        fk.propertyName, fk.propertyName, FIELD_PATCH, fk.propertyName, FIELD_PATCH,
                    )
                }
            }
            add(")\n")
        }
        return function("_buildRequestedPatch", patchClass) {
            addModifiers(KModifier.INTERNAL)
            parameter("driver", DRIVER)
            addCode(code)
            statement(
                "return %L",
                lifecyclePatchSnapshot("snapshot", mutableFields, ClassName(packageName, schemaName)),
            )
        }
    }

    /**
     * Generate an internal `_checkRequiredNotNull()` member that returns
     * the violation for any required field or required edge FK
     * that has been assigned `null` and not repaired (empty list =
     * all required shapes hold). Called from the save pipeline after
     * all `beforeUpdate` hooks complete, before the canonical
     * requested patch is built; a non-empty result becomes
     * `UpdatePreparation.Invalid`, which the runtime executor turns into a
     * typed `EntValidationException`. A hook can clear the bad assignment via
     * `mutation.unsetFoo()` (removes the entry from `dirtyFields`) or
     * by reassigning a non-null value. Short-circuits on the first
     * failure (matches the existing single-violation shape;
     * collect-all is left as a future improvement).
     */
    private fun buildCheckRequiredNotNullFunction(
        schemaName: String,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        return function(
            "_checkRequiredNotNull",
            LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION),
        ) {
            addModifiers(KModifier.INTERNAL)
            for (field in mutableFields) {
                if (field.nullable) continue
                val prop = field.apiName
                statement(
                    "if (%S in dirtyFields && this.%L == null) return·listOf(%T(%S, field = %S))",
                    prop, prop,
                    MUTATION_VALIDATION_VIOLATION, "$prop is required", prop,
                )
            }
            for (fk in edgeFks) {
                if (!fk.required) continue
                // Read the private staging field directly so a corrupted
                // dirty+null state is caught here rather than triggering the
                // throw-on-untouched getter (which would mask the diagnostic).
                val stagingName = stagingFieldName(fk.propertyName)
                statement(
                    "if (%S in dirtyFields && this.%L == null) return·listOf(%T(%S, field = %S))",
                    fk.propertyName, stagingName,
                    MUTATION_VALIDATION_VIOLATION, "${fk.propertyName} is required", fk.propertyName,
                )
            }
            statement("return emptyList()")
        }
    }

    /**
     * Build the public `val ${edge}: ${Edge}EdgeMutator`
     * property on the update draft. The mutator's constructor is
     * `internal`, so only this builder instantiates it; the public DSL
     * surface is `update(id) { tags.add(tagId) }`.
     */
    private fun buildEdgeMutatorProperty(
        edge: HelperEligibleM2M,
        updateClass: ClassName,
    ): PropertySpec {
        val mutatorClass = updateClass.nestedClass(edge.mutatorClassSimpleName)
        return property(edge.mutatorPropertyName, mutatorClass) {
            initializer("%T()", mutatorClass)
        }
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
        val mixedModeMessage = "edge '${edge.mutatorPropertyName}': cannot mix replacement (set) and " +
            "delta (add/remove) operations in one mutation"
        val sameIdAddAfterRemoveMessage = "edge '${edge.mutatorPropertyName}': cannot add(id) after " +
            "remove(id) for the same id in one mutation"
        val sameIdRemoveAfterAddMessage = "edge '${edge.mutatorPropertyName}': cannot remove(id) after " +
            "add(id) for the same id in one mutation"
        val sameIdOverlapMessage = "edge '${edge.mutatorPropertyName}': delta add/remove sets overlap on " +
            "one or more ids — `add(x)` and `remove(x)` for the same x must not coexist"

        return classType(edge.mutatorClassSimpleName) {
            primaryConstructor { addModifiers(KModifier.INTERNAL) }
            // Op log — `private`. Same-module application code can't
            // bypass the per-call invariants by reaching into these
            // fields. Downstream codegen accesses state through the
            // internal accessors below.
            property("_requestedSet", listOfId.copy(nullable = true)) {
                addModifiers(KModifier.PRIVATE)
                mutable(true)
                initializer("null")
            }
            property("_adds", mutableListOfId) {
                addModifiers(KModifier.PRIVATE)
                initializer("mutableListOf()")
            }
            property("_removes", mutableListOfId) {
                addModifiers(KModifier.PRIVATE)
                initializer("mutableListOf()")
            }
            function("add") {
                parameter("id", idType)
                statement(
                        "if (_requestedSet != null) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, mixedModeMessage,
                    )
                statement(
                        "if (_removes.contains(id)) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, sameIdAddAfterRemoveMessage,
                    )
                statement("_adds.add(id)")
            }
            function("remove") {
                parameter("id", idType)
                statement(
                        "if (_requestedSet != null) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, mixedModeMessage,
                    )
                statement(
                        "if (_adds.contains(id)) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, sameIdRemoveAfterAddMessage,
                    )
                statement("_removes.add(id)")
            }
            function("set") {
                parameter("ids", listOfId)
                statement(
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
                statement("_requestedSet = ids.toList()")
            }
            function("hasOps", BOOLEAN) {
                addModifiers(KModifier.INTERNAL)
                statement(
                        "return _requestedSet != null || _adds.isNotEmpty() || _removes.isNotEmpty()",
                    )
            }
            // True when this edge has a pending op that can INSERT a junction row.
            function("hasInserts", BOOLEAN) {
                addModifiers(KModifier.INTERNAL)
                statement("return _requestedSet != null || _adds.isNotEmpty()")
            }
            // Materialize the immutable PendingEdgeOps snapshot consumed downstream.
            function("snapshotOps", pendingEdgeOpsParamed) {
                addModifiers(KModifier.INTERNAL)
                statement(
                        "return %T(\n" +
                            "  requestedSet = _requestedSet?.let { %M(it) },\n" +
                            "  requestedAdds = %M(_adds),\n" +
                            "  requestedRemoves = %M(_removes),\n" +
                            ")",
                        PENDING_EDGE_OPS,
                        IMMUTABLE_SET_SNAPSHOT,
                        IMMUTABLE_SET_SNAPSHOT,
                        IMMUTABLE_SET_SNAPSHOT,
                )
            }
            // Re-run both mixed-mode rules as a save-preflight backstop.
            function("validateInvariants") {
                addModifiers(KModifier.INTERNAL)
                statement(
                        "if (_requestedSet != null && (_adds.isNotEmpty() || _removes.isNotEmpty())) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, mixedModeMessage,
                    )
                statement(
                        "if ((_adds.toSet() intersect _removes.toSet()).isNotEmpty()) throw %T(%S)",
                        ILLEGAL_STATE_EXCEPTION, sameIdOverlapMessage,
                    )
            }
        }
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
        // helperEligibleEdges is non-empty (caller-side guard).
        val expr = helperEligibleEdges.joinToString(" || ") { "this.${it.mutatorPropertyName}.hasOps()" }
        return function("_hasPendingLinkTableM2MOps", BOOLEAN) {
            addModifiers(KModifier.INTERNAL)
            statement("return %L", expr)
        }
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
        // helperEligibleEdges is non-empty (caller-side guard).
        val expr = helperEligibleEdges.joinToString(" || ") { "this.${it.mutatorPropertyName}.hasInserts()" }
        return function("_hasPendingLinkTableM2MInserts", BOOLEAN) {
            addModifiers(KModifier.INTERNAL)
            statement("return %L", expr)
        }
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
        return function("_checkLinkTableM2MMixedMode") {
            addModifiers(KModifier.INTERNAL)
            for (edge in helperEligibleEdges) {
                statement("this.%L.validateInvariants()", edge.mutatorPropertyName)
            }
        }
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
        if (helperEligibleEdges.isEmpty()) {
            return function("_buildPendingEdgeOps", pendingEdgeOpsClass) {
                addModifiers(KModifier.INTERNAL)
                statement("return %T()", pendingEdgeOpsClass)
            }
        }

        val body = codeBlock {
            add("return %T(\n", pendingEdgeOpsClass)
            for (edge in helperEligibleEdges) {
                val prop = edge.mutatorPropertyName
                add("  %L = this.%L.snapshotOps(),\n", prop, prop)
            }
            add(")\n")
        }
        return function("_buildPendingEdgeOps", pendingEdgeOpsClass) {
            addModifiers(KModifier.INTERNAL)
            addCode(body)
        }
    }

    /**
     * Build `_buildEdgeChanges()`, the private method
     * that reads current junction rows for each helper-eligible edge
     * with pending ops and computes the per-entity `${Schema}EdgeChangesView`
     * sidecar surfaced through update privacy and validation items.
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
        if (helperEligibleEdges.isEmpty()) {
            return function("_buildEdgeChanges", edgeChangesViewClass) {
                addModifiers(KModifier.PRIVATE)
                parameter("pendingEdges", pendingEdgeOpsClass)
                statement("return %T()", edgeChangesViewClass)
            }
        }

        val body = codeBlock {
            for (edge in helperEligibleEdges) {
                val prop = edge.mutatorPropertyName
                val currentVar = "_current_${prop}"
                val targetIdType = edge.targetIdTypeName
                // Read current junction state only when the edge has pending ops.
                // Junction-table query: predicate has no entity scope.
                // Predicate.Leaf<Any> renders the same structural data
                // (field/op/value) and erases at the driver boundary.
                add(
                    "val %L: Set<%T> = if (draft.%L.hasOps()) {\n" +
                        "  driver.query(%S, listOf(%T.Leaf<%T>(%S, %T.EQ, id)), emptyList(), null, null)\n" +
                        "    .map { it[%S] as %T }\n" +
                        "    .toSet()\n" +
                        "} else emptySet()\n",
                    currentVar, targetIdType, prop,
                    edge.junctionTable, PREDICATE, Any::class.asClassName(), edge.junctionSourceColumn, OP_CLASS,
                    edge.junctionTargetColumn, targetIdType,
                )
            }
            add("return %T(\n", edgeChangesViewClass)
            for (edge in helperEligibleEdges) {
                val prop = edge.mutatorPropertyName
                val currentVar = "_current_${prop}"
                add(
                    "  %L = %M(pendingEdges.%L, %L),\n",
                    prop, COMPUTE_EDGE_CHANGES, prop, currentVar,
                )
            }
            add(")\n")
        }
        return function("_buildEdgeChanges", edgeChangesViewClass) {
            addModifiers(KModifier.PRIVATE)
            parameter("pendingEdges", pendingEdgeOpsClass)
            addCode(body)
        }
    }

    private fun buildHasFieldAssignmentsFunction(): FunSpec =
        function("_hasFieldAssignments", BOOLEAN) {
            addModifiers(KModifier.INTERNAL)
            statement("return dirtyFields.isNotEmpty()")
        }

    private fun buildUnsetFieldFunction(): FunSpec = function("_unsetFieldForInternalUse") {
        addModifiers(KModifier.INTERNAL)
        parameter("field", STRING)
        statement("dirtyFields.remove(field)")
    }

}
