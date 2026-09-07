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
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.HelperEligibleM2M
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.helperEligibleM2MEdges
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.stagingFieldName
import entkt.codegen.metadata.toTypeName
import entkt.codegen.kotlinpoet.annotation
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
private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
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
private val UPDATE_MUTATION_ADAPTER =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationAdapter")


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
        // (which carries the unchanged value from the loaded entity).
        val allEdgeFks = computeEdgeFks(schema, schemaNames)
        val edgeFks = allEdgeFks.filter { !it.immutable }

        val entityClass = ClassName(packageName, schemaName)
        val draftClass = ClassName(packageName, className)
        val adapterClass = ClassName(packageName, "${schemaName}UpdateAdapter")
        val preparedStateClass = adapterClass.nestedClass("PreparedState")
        val beforeUpdateStateClass = ClassName(packageName, "${schemaName}BeforeUpdateState")

        // Helper-eligible link-table M2M edges. Each gets a nested mutator
        // class on the update draft. Before-update hook states expose the
        // captured operations through their read-only pendingEdges property.
        val helperEligibleEdges = helperEligibleM2MEdges(schema, schemaNames)
        val saveArtifacts = UpdateSaveEmitter(
            packageName = packageName,
            schemaName = schemaName,
            preparedStateClass = preparedStateClass,
            allFields = allFields,
            edgeFks = edgeFks,
            allEdgeFks = allEdgeFks,
            helperEligibleEdges = helperEligibleEdges,
        ).build()

        val draftType = classType(className) {
            addAnnotation(annotation(ENTKT_DSL))
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
            addFunction(buildBeforeSaveStateFunction(schemaName, mutableFields, edgeFks))
            addFunction(buildBuildPendingEdgeOpsFunction(schemaName, helperEligibleEdges))
            addFunction(buildHasFieldAssignmentsFunction())
            run {
                if (helperEligibleEdges.isNotEmpty()) {
                    addFunction(buildHasPendingLinkTableM2MOpsFunction(helperEligibleEdges))
                    addFunction(buildHasPendingLinkTableM2MInsertsFunction(helperEligibleEdges))
                }
            }
            run {
                for (edge in helperEligibleEdges) {
                    addType(buildEdgeMutatorType(edge))
                }
            }
        }

        val pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps")
        val adapterType = classType(adapterClass.simpleName) {
            addModifiers(KModifier.INTERNAL)
            addSuperinterface(
                UPDATE_MUTATION_ADAPTER.parameterizedBy(
                    draftClass,
                    entityClass,
                    pendingEdgeOpsClass,
                    preparedStateClass,
                    beforeUpdateStateClass,
                ),
            )
            primaryConstructor {
                parameter("driver", DRIVER)
            }
            property("driver", DRIVER) {
                addModifiers(KModifier.PRIVATE)
                initializer("driver")
            }
            addFunction(buildBuildEdgeChangesFunction(schemaName, helperEligibleEdges))
            saveArtifacts.functions.forEach(::addFunction)
            addType(saveArtifacts.preparedStateType)
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
            mutable(true)
            initializer("null")
            // Reading an untouched update field must throw (by contract). The
            // draft has no current-state value before save(); for nullable
            // fields, a default-null getter would also collapse Unset and
            // explicit Set(null) into the same observable value. Before hooks
            // receive immutable states with explicit FieldPatch entries.
            getter {
                statement(
                        "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                        prop,
                        "$prop is not set in this update",
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
            //   - getter throws on untouched read (by contract)
            //   - setter rejects null at entry so Java/platform callers
            //     can't put the builder into a dirty+null state
            val nonNullType = fk.idType.toTypeName().copy(nullable = false)
            val stagingName = stagingFieldName(fk.propertyName)
            return property(fk.propertyName, nonNullType) {
                mutable(true)
                getter {
                    statement(
                            "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                            fk.propertyName,
                            "${fk.propertyName} is not set in this update",
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
            mutable(true)
            initializer("null")
            getter {
                statement(
                        "if (%S !in dirtyFields) throw IllegalStateException(%S)",
                        fk.propertyName,
                        "${fk.propertyName} is not set in this update",
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

    /** Lower the caller's mutable draft into the first immutable hook state. */
    private fun buildBeforeSaveStateFunction(
        schemaName: String,
        mutableFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val stateClass = ClassName(packageName, "${schemaName}BeforeSaveState")
        return function("_buildBeforeSaveState", stateClass) {
            addModifiers(KModifier.INTERNAL)
            addCode(codeBlock {
                add("return %T(\n", stateClass)
                indent()
                mutableFields.forEach { field ->
                    add(
                        "%L = if (%S in dirtyFields) %T.Set(this.%L) else %T.Unset,\n",
                        field.apiName,
                        field.apiName,
                        FIELD_PATCH,
                        field.apiName,
                        FIELD_PATCH,
                    )
                }
                edgeFks.forEach { fk ->
                    val value = if (fk.required) stagingFieldName(fk.propertyName) else fk.propertyName
                    add(
                        "%L = if (%S in dirtyFields) %T.Set(this.%L) else %T.Unset,\n",
                        fk.propertyName,
                        fk.propertyName,
                        FIELD_PATCH,
                        value,
                        FIELD_PATCH,
                    )
                }
                unindent()
                add(")\n")
            })
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
     *    aggregator the hook state / privacy / validation surfaces
     *    consume.
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
     * edge and the conflicting operations. The private op-log fields
     * prevent same-module code from bypassing these call-site checks.
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
        }
    }

    /**
     * Build `_hasPendingLinkTableM2MOps()`. ORs each
     * helper-eligible mutator's `hasOps()` flag — reported to the
     * runtime relationship requirements. Generated only when the schema has at
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
     * helper-eligible mutator's `hasInserts()` flag — reported to runtime
     * so it can enforce `supportsInsertIgnore`. True when any
     * edge has a pending `add` or `set`; remove-only saves stay false and
     * do not require that capability (they never call `insertIgnore`).
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
     * Build `_buildPendingEdgeOps()`, a private method
     * that snapshots each per-edge mutator's op log into the per-entity
     * `${Schema}PendingEdgeOps` aggregator. The result is the read-only
     * value that hooks see through `state.pendingEdges`.
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
                parameter("id", Any::class.asClassName())
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
                    "val %L: Set<%T> = if (pendingEdges.%L.hasChanges) {\n" +
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
            parameter("id", Any::class.asClassName())
            parameter("pendingEdges", pendingEdgeOpsClass)
            addCode(body)
        }
    }

    private fun buildHasFieldAssignmentsFunction(): FunSpec =
        function("_hasFieldAssignments", BOOLEAN) {
            addModifiers(KModifier.INTERNAL)
            statement("return dirtyFields.isNotEmpty()")
        }

}
