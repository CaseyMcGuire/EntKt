package entkt.codegen.manifest

import entkt.codegen.apiName
import entkt.codegen.generatedStem
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.HelperEligibleM2M
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.scalarFields
import entkt.schema.EdgeKind
import entkt.schema.EntSchema

/**
 * Walk a schema's resolved metadata and populate a
 * [GeneratedMemberManifest] with every name the codegen will emit
 * on each artifact in the V1 coverage scope. generated-member collision checks
 * implementation; detects collisions against this output.
 *
 * V1 coverage:
 *
 * - **entity data class** (`${name}`) — scalar field properties,
 *   FK properties, fixed `id` / `edges` accessors, data-class
 *   synthesized members (`copy`, `equals`, `hashCode`, `toString`,
 *   `componentN` covering id + scalars + fks + the trailing
 *   `edges` constructor slot when the schema has any edges).
 * - **entity companion** (`${name}.Companion`) — fixed `fromRow` /
 *   `TABLE` / `SCHEMA`, plus the id column ref, one column ref
 *   per scalar field (`field.apiName`), one column ref
 *   per FK (`fk.propertyName`), and one edge ref per edge
 *   (`edge.apiName`).
 * - **query class** (`${name}Query`) — the fixed query surface plus
 *   `query{Stem}` / `load{Stem}` / `eager{Stem}` per declared edge.
 * - **entity Edges class** (`${name}.Edges`) — one property per
 *   declared edge (`edge.apiName`) plus the data-class synthesized
 *   members. Only present when the schema declares edges.
 * - **mutation interface** (`${name}Mutation`) — mutable scalar
 *   field properties, mutable FK properties.
 * - **create draft** (`${name}CreateDraft`) — every scalar and FK
 *   setter plus explicit-assignment inspection.
 * - **update builder** (`${name}Update`) — mutable scalar field
 *   setters, mutable FK setters, helper-eligible M2M mutator
 *   properties, fixed builder members (including `id`,
 *   `consistency`). **NOT `unset{X}()` methods** — those live
 *   only on the private hook-facing adapter and are surfaced via
 *   `${name}UpdateMutationView`, never on the public builder.
 * - **create mutation view** (`${name}CreateMutationView`) —
 *   inherited mutation props + create-only immutable scalar /
 *   immutable FK setters.
 * - **update mutation view** (`${name}UpdateMutationView`) —
 *   inherited mutation props + `unset{X}()` methods + the fixed
 *   `pendingEdges` aggregator (unconditionally emitted by
 *   MutationGenerator, regardless of whether the schema has any
 *   helper-eligible M2M edge — so the manifest registers it
 *   unconditionally). **NOT M2M mutator properties** — those
 *   live on the public `${name}Update` builder, never on this
 *   view.
 *
 * Storage names (DB column names, table names) are intentionally
 * absent — those don't share a Kotlin member namespace with
 * generated source members, and they're validated separately.
 */
internal fun buildMemberManifest(
    schemaName: String,
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
    helperEligibleM2M: List<HelperEligibleM2M> = emptyList(),
): GeneratedMemberManifest {
    val manifest = GeneratedMemberManifest(schemaName)

    val scalars = scalarFields(schema)
    val mutableScalars = scalars.filter { !it.immutable }
    val immutableScalars = scalars.filter { it.immutable }
    val fks = computeEdgeFks(schema, schemaNames)
    val mutableFks = fks.filter { !it.immutable }
    val immutableFks = fks.filter { it.immutable }
    // Non-belongsTo edges (hasMany / hasOne / M2M) — collected to
    // detect collisions with FK property names on the entity class.
    val nonFkEdges = schema.edges().filter { it.kind !is EdgeKind.BelongsTo }

    addEntityClassMembers(manifest, schemaName, scalars, fks, nonFkEdges)
    addEdgesClassMembers(manifest, schemaName, schema.edges())
    addQueryClassMembers(manifest, schemaName, schema.edges())
    addEntityCompanionMembers(manifest, schemaName, scalars, fks, schema.edges())
    addMutationInterfaceMembers(manifest, schemaName, mutableScalars, mutableFks)
    addCreateDraftMembers(manifest, schemaName, scalars, fks)
    addUpdateBuilderMembers(manifest, schemaName, mutableScalars, mutableFks, helperEligibleM2M)
    addCreateMutationViewMembers(manifest, schemaName, immutableScalars, immutableFks)
    addUpdateMutationViewMembers(manifest, schemaName, mutableScalars, mutableFks, helperEligibleM2M)

    return manifest
}

// ── Artifact names ────────────────────────────────────────────────

private fun entityArtifact(name: String): String = name
private fun companionArtifact(name: String): String = "$name.Companion"
private fun edgesArtifact(name: String): String = "$name.Edges"
private fun queryArtifact(name: String): String = "${name}Query"
private fun mutationInterfaceArtifact(name: String): String = "${name}Mutation"
private fun createDraftArtifact(name: String): String = "${name}CreateDraft"
private fun updateBuilderArtifact(name: String): String = "${name}Update"
private fun createViewArtifact(name: String): String = "${name}CreateMutationView"
private fun updateViewArtifact(name: String): String = "${name}UpdateMutationView"

// ── Name derivation helpers ───────────────────────────────────────

private fun unsetMethodName(propertyName: String): String =
    "unset${propertyName.replaceFirstChar { it.uppercaseChar() }}"

// ── Entity class ─────────────────────────────────────────────────

private fun addEntityClassMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    scalars: List<entkt.schema.Field>,
    fks: List<EdgeFk>,
    nonFkEdges: List<entkt.schema.Edge>,
) {
    val artifact = entityArtifact(schemaName)

    // Fixed members on the entity data class.
    manifest.add(artifact, "id", GeneratedMemberKind.PROPERTY, "fixed entity primary-key property")
    manifest.add(artifact, "edges", GeneratedMemberKind.PROPERTY, "fixed entity edges accessor")

    // Data-class synthesized members. Kotlin generates these from
    // the entity's primary constructor; user-declared property
    // names that collide with them produce confusing compile
    // errors, so cover `copy` and `componentN` explicitly.
    manifest.add(artifact, "copy", GeneratedMemberKind.FUNCTION, "data-class synthesized copy")
    manifest.add(artifact, "equals", GeneratedMemberKind.FUNCTION, "data-class synthesized equals")
    manifest.add(artifact, "hashCode", GeneratedMemberKind.FUNCTION, "data-class synthesized hashCode")
    manifest.add(artifact, "toString", GeneratedMemberKind.FUNCTION, "data-class synthesized toString")

    // componentN: the entity data class's primary constructor has
    // one slot per scalar field + one slot per FK + the id, PLUS
    // one trailing `edges` slot when the schema has any edges
    // (EntityGenerator.kt:216 appends the edges parameter when an
    // Edges inner class is generated, which happens iff
    // schema.edges() is non-empty). Synthesized component
    // functions match constructor order, so we enumerate
    // `component1`..`componentK` for K equal to the constructor
    // arity.
    val hasEdgesSlot = nonFkEdges.isNotEmpty() || fks.isNotEmpty()
    val componentCount = 1 + scalars.size + fks.size + (if (hasEdgesSlot) 1 else 0)
    for (i in 1..componentCount) {
        manifest.add(artifact, "component$i", GeneratedMemberKind.FUNCTION, "data-class synthesized component$i")
    }

    // Scalar field properties (excluding FK-backing columns,
    // which appear via the FK property).
    for (field in scalars) {
        manifest.add(
            artifact,
            field.apiName,
            GeneratedMemberKind.PROPERTY,
            "field '${field.apiName}'",
        )
    }

    // FK properties. Implicit FKs are named `${edgeDeclaration}Id`;
    // field-backed FKs take the backing field's declaration name.
    // The critical collision is when a
    // scalar field's generated property name equals an FK's
    // generated property name on the same entity.
    for (fk in fks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            if (fk.isFieldBacked) "FK for field-backed edge '${fk.edgeApiName}'"
            else "FK for edge '${fk.edgeApiName}'",
        )
    }

    // Non-belongsTo edges (hasMany / hasOne / M2M) accessors live on
    // `${Entity}Edges`, not on the entity class, so they don't conflict
    // here — see addEdgesClassMembers for that artifact.
    @Suppress("UNUSED_PARAMETER") nonFkEdges
}

// ── Entity Edges class ───────────────────────────────────────────

/**
 * `${name}Edges` — one `EdgeState` property per declared edge, named by
 * the edge's Kotlin declaration, plus the data-class members Kotlin
 * synthesizes from them.
 *
 * This artifact matters because edge member names are now the schema
 * author's Kotlin declarations rather than storage-derived strings, so
 * an edge declared `copy` or `component1` reaches a synthesized member
 * and produces a confusing compile error in generated source.
 *
 * Emitted only when the schema declares at least one edge —
 * EntityGenerator generates the inner class under the same condition.
 */
private fun addEdgesClassMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    allEdges: List<entkt.schema.Edge>,
) {
    if (allEdges.isEmpty()) return
    val artifact = edgesArtifact(schemaName)

    manifest.add(artifact, "copy", GeneratedMemberKind.FUNCTION, "data-class synthesized copy")
    manifest.add(artifact, "equals", GeneratedMemberKind.FUNCTION, "data-class synthesized equals")
    manifest.add(artifact, "hashCode", GeneratedMemberKind.FUNCTION, "data-class synthesized hashCode")
    manifest.add(artifact, "toString", GeneratedMemberKind.FUNCTION, "data-class synthesized toString")
    for (i in 1..allEdges.size) {
        manifest.add(artifact, "component$i", GeneratedMemberKind.FUNCTION, "data-class synthesized component$i")
    }

    for (edge in allEdges) {
        manifest.add(
            artifact,
            edge.apiName,
            GeneratedMemberKind.PROPERTY,
            "edge '${edge.apiName}'",
        )
    }
}

// ── Query class ──────────────────────────────────────────────────

/**
 * Members `QueryGenerator` always emits on `${name}Query`, public and
 * private alike. Private members count because Kotlin does not allow a
 * private and generated public member to share a name on one class.
 *
 * Hand-maintained mirror of `QueryGenerator` — the same contract the
 * other artifact lists in this file follow.
 */
private val FIXED_QUERY_PROPERTIES: List<String> = listOf(
    "_readQueryEvaluator",
    "client",
    "driver",
    "entityQuerySource",
    "orderFields",
    "predicates",
    "queryLimit",
    "queryOffset",
)

private val FIXED_QUERY_FUNCTIONS: List<String> = listOf(
    "all",
    "captureEntityQuery",
    "combinedPredicate",
    "compileEntityQuery",
    "firstOrNull",
    "limit",
    "offset",
    "orderBy",
    "rawAvg",
    "rawAvgBy",
    "rawCount",
    "rawCountBy",
    "rawExists",
    "rawMax",
    "rawMaxBy",
    "rawMin",
    "rawMinBy",
    "rawSum",
    "rawSumBy",
    "readRootQuery",
    "requireClient",
    "setEntityQuerySource",
    "where",
)

private val FIXED_QUERY_NESTED_TYPES: List<String> = listOf(
    "GeneratedEntityMapping",
)

/**
 * `${name}Query` — the fixed query surface plus, per edge, the
 * declaration-derived members: `query{Stem}` traversal, `load{Stem}`
 * edge-load entry point, and the private `eager{Stem}` backing
 * property (with its `FilterVisible` companion).
 *
 * Source phrases name the delegated declaration and the storage edge
 * name distinctly: the storage string does not name this API, so its
 * uniqueness proves nothing about the generated members, but the
 * diagnostic must let the schema author find the declaration by
 * either identity.
 */
private fun addQueryClassMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    allEdges: List<entkt.schema.Edge>,
) {
    val artifact = queryArtifact(schemaName)
    for (fixed in FIXED_QUERY_PROPERTIES) {
        manifest.add(artifact, fixed, GeneratedMemberKind.PROPERTY, "fixed query member")
    }
    for (fixed in FIXED_QUERY_FUNCTIONS) {
        manifest.add(artifact, fixed, GeneratedMemberKind.FUNCTION, "fixed query member")
    }
    for (fixed in FIXED_QUERY_NESTED_TYPES) {
        manifest.add(artifact, fixed, GeneratedMemberKind.NESTED_TYPE, "fixed query nested type")
    }
    for (edge in allEdges) {
        val stem = edge.apiName.generatedStem()
        val identity = "edge declared '${edge.apiName}' (storage '${edge.name}')"
        manifest.add(
            artifact, "query$stem", GeneratedMemberKind.FUNCTION,
            "traversal for $identity",
        )
        manifest.add(
            artifact, "load$stem", GeneratedMemberKind.FUNCTION,
            "edge load for $identity",
        )
        manifest.add(
            artifact, "eager$stem", GeneratedMemberKind.PROPERTY,
            "eager backing property for $identity",
        )
        // The filterVisible opt-in is a second per-edge property, so
        // edges declared `posts` and `postsFilterVisible` both reach
        // `eagerPostsFilterVisible`.
        manifest.add(
            artifact, "eager${stem}FilterVisible", GeneratedMemberKind.PROPERTY,
            "eager filterVisible opt-in for $identity",
        )
        manifest.add(
            artifact, "Generated${stem}EdgeMapping", GeneratedMemberKind.NESTED_TYPE,
            "typed relationship mapping for $identity",
        )
    }
}

// ── Entity companion ─────────────────────────────────────────────

private fun addEntityCompanionMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    scalars: List<entkt.schema.Field>,
    fks: List<EdgeFk>,
    allEdges: List<entkt.schema.Edge>,
) {
    val artifact = companionArtifact(schemaName)

    // Fixed companion members.
    manifest.add(artifact, "fromRow", GeneratedMemberKind.FUNCTION, "fixed entity companion decoder")
    manifest.add(artifact, "TABLE", GeneratedMemberKind.PROPERTY, "fixed entity companion TABLE")
    manifest.add(artifact, "SCHEMA", GeneratedMemberKind.PROPERTY, "fixed entity companion SCHEMA")

    // The id column ref — see EntityGenerator.buildIdColumnRef.
    // Always named exactly "id" since the id column is always the
    // schema's primary key.
    manifest.add(artifact, "id", GeneratedMemberKind.PROPERTY, "id column ref")

    // One column ref per scalar field (EntityGenerator.buildFieldColumnRef
    // uses field.apiName for the property name).
    for (field in scalars) {
        manifest.add(
            artifact,
            field.apiName,
            GeneratedMemberKind.PROPERTY,
            "column ref for field '${field.apiName}'",
        )
    }

    // One column ref per FK (EntityGenerator.buildEdgeColumnRef
    // uses fk.propertyName).
    for (fk in fks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            "column ref for FK edge '${fk.edgeApiName}'",
        )
    }

    // One edge ref per edge — including non-belongsTo edges
    // (EntityGenerator.buildEdgeRef uses edge.apiName).
    // The edge refs are how the runtime walks `has` / `exists`
    // predicates, so every declared edge contributes one.
    for (edge in allEdges) {
        manifest.add(
            artifact,
            edge.apiName,
            GeneratedMemberKind.PROPERTY,
            "edge ref for edge '${edge.apiName}'",
        )
    }
}

// ── Mutation interface (the shared writable surface) ─────────────

private fun addMutationInterfaceMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    mutableScalars: List<entkt.schema.Field>,
    mutableFks: List<EdgeFk>,
) {
    val artifact = mutationInterfaceArtifact(schemaName)
    for (field in mutableScalars) {
        manifest.add(
            artifact,
            field.apiName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "mutable field '${field.apiName}'",
        )
    }
    for (fk in mutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "mutable FK for edge '${fk.edgeApiName}'",
        )
    }
}

// ── Create draft ─────────────────────────────────────────────────

private fun addCreateDraftMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    allScalars: List<entkt.schema.Field>,
    allFks: List<EdgeFk>,
) {
    val artifact = createDraftArtifact(schemaName)
    manifest.add(
        artifact,
        "assignedFields",
        GeneratedMemberKind.PROPERTY,
        "create-draft assignment tracker",
    )
    manifest.add(
        artifact,
        "isSet",
        GeneratedMemberKind.FUNCTION,
        "create-draft assignment inspection",
    )

    // Create allows setting every scalar field (mutable + immutable)
    // and every FK (mutable + immutable). Immutability is a
    // "no UPDATE writes" rule, not a "no CREATE writes" rule.
    for (field in allScalars) {
        manifest.add(
            artifact,
            field.apiName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "field '${field.apiName}' setter",
        )
    }
    for (fk in allFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "FK setter for edge '${fk.edgeApiName}'",
        )
    }
}

// ── Update builder ───────────────────────────────────────────────

private fun addUpdateBuilderMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    mutableScalars: List<entkt.schema.Field>,
    mutableFks: List<EdgeFk>,
    helperEligibleM2M: List<HelperEligibleM2M>,
) {
    val artifact = updateBuilderArtifact(schemaName)

    addFixedUpdateBuilderMembers(manifest, artifact)

    for (field in mutableScalars) {
        manifest.add(
            artifact,
            field.apiName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "field '${field.apiName}' setter",
        )
    }
    for (fk in mutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "FK setter for edge '${fk.edgeApiName}'",
        )
    }

    // Link-table M2M mutator properties live on
    // the *public* update builder, not on the UpdateMutationView
    // interface — hooks must not reach add/remove/set through
    // ctx.mutation.
    for (m2m in helperEligibleM2M) {
        manifest.add(
            artifact,
            m2m.mutatorPropertyName,
            GeneratedMemberKind.PROPERTY,
            "M2M edge mutator for edge '${m2m.mutatorPropertyName}'",
        )
    }

    // No `unset{X}()` here. The public update builder
    // intentionally does NOT expose unset methods — they live only
    // on the private hook-facing adapter (typed as
    // `${name}UpdateMutationView`), so callers can't write
    // `client.posts.update(id) { unsetName() }`.
}

// ── Create mutation view ─────────────────────────────────────────

private fun addCreateMutationViewMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    immutableScalars: List<entkt.schema.Field>,
    immutableFks: List<EdgeFk>,
) {
    val artifact = createViewArtifact(schemaName)
    // The create view extends `${name}Mutation`, so mutable
    // scalars and mutable FKs are already in the parent namespace.
    // What it ADDS is the create-only writable surface: immutable
    // scalars and immutable field-backed FKs.
    for (field in immutableScalars) {
        manifest.add(
            artifact,
            field.apiName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "immutable field '${field.apiName}' (create-only writable)",
        )
    }
    for (fk in immutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "immutable FK setter for edge '${fk.edgeApiName}' (create-only writable)",
        )
    }
}

// ── Update mutation view ─────────────────────────────────────────

private fun addUpdateMutationViewMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    mutableScalars: List<entkt.schema.Field>,
    mutableFks: List<EdgeFk>,
    helperEligibleM2M: List<HelperEligibleM2M>,
) {
    val artifact = updateViewArtifact(schemaName)

    // Mutable scalars + mutable FKs come through the inherited
    // `${name}Mutation` interface; they're also added here so the
    // artifact namespace is complete and a user-declared field
    // colliding with one of them on THIS artifact fires the
    // diagnostic. (Mutation interface coverage handles the
    // separate concern of two mutables sharing a name there.)
    for (field in mutableScalars) {
        val prop = field.apiName
        manifest.add(artifact, prop, GeneratedMemberKind.MUTABLE_PROPERTY, "mutable field '${field.apiName}'")
        manifest.add(
            artifact,
            unsetMethodName(prop),
            GeneratedMemberKind.FUNCTION,
            "unset method for field '${field.apiName}'",
        )
    }
    for (fk in mutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.MUTABLE_PROPERTY,
            "mutable FK for edge '${fk.edgeApiName}'",
        )
        manifest.add(
            artifact,
            unsetMethodName(fk.propertyName),
            GeneratedMemberKind.FUNCTION,
            "unset method for FK edge '${fk.edgeApiName}'",
        )
    }

    // `pendingEdges` aggregator — unconditionally emitted on
    // UpdateMutationView by MutationGenerator.kt:153 regardless of
    // whether the schema has any helper-eligible M2M edge. Even a
    // schema with no link-table M2M ends up with an empty-shape
    // `pendingEdges: ${name}PendingEdgeOps` property on the
    // interface, so the manifest must register it unconditionally
    // — otherwise `val pendingEdges = string(...)` on a non-M2M
    // schema would slip past the collision check and surface as a
    // Kotlin compile error downstream.
    //
    // The per-edge mutator properties (`tags.add(...)` etc.)
    // intentionally do NOT land here — they live on the public
    // `${name}Update` builder, not on UpdateMutationView. See
    // [addUpdateBuilderMembers].
    @Suppress("UNUSED_PARAMETER") helperEligibleM2M
    manifest.add(
        artifact,
        "pendingEdges",
        GeneratedMemberKind.PROPERTY,
        "fixed update-mutation-view pendingEdges aggregator",
    )
}

// ── Fixed builder members ────────────────────────────────────────

private fun addFixedUpdateBuilderMembers(
    manifest: GeneratedMemberManifest,
    artifact: String,
) {
    val shared = listOf(
        "save" to GeneratedMemberKind.FUNCTION,
        "saveAndLoad" to GeneratedMemberKind.FUNCTION,
        "client" to GeneratedMemberKind.PROPERTY,
    )
    for ((n, kind) in shared) {
        manifest.add(artifact, n, kind, "fixed builder member '$n'")
    }
    val updateOnly = listOf(
            "id" to GeneratedMemberKind.PROPERTY,
            "driver" to GeneratedMemberKind.PROPERTY,
            "entity" to GeneratedMemberKind.PROPERTY,
            "beforeSaveHooks" to GeneratedMemberKind.PROPERTY,
            "consistency" to GeneratedMemberKind.PROPERTY,
            "dirtyFields" to GeneratedMemberKind.PROPERTY,
            "relationshipLocking" to GeneratedMemberKind.PROPERTY,
            "beforeUpdateHooks" to GeneratedMemberKind.PROPERTY,
            "afterUpdateHooks" to GeneratedMemberKind.PROPERTY,
            // Private adapter and snapshot properties used by generated hooks.
            // Schema field names can't start with `_` (the snake_case
            // regex forbids it), but declaration capture picks
            // up Kotlin val names verbatim — and Kotlin allows `val
            // _foo`. A schema like `val _mutationView = uuid("x");
            // val rel = belongsTo<X>("rel").field(_mutationView)`
            // produces a generated FK property named `_mutationView`
            // that would collide with the private adapter on the
            // update builder. Manifest registers these so the generated-member collision checks
            // collision check rejects the schema with the actionable
            // diagnostic.
            "_mutationView" to GeneratedMemberKind.PROPERTY,
            "_beforeSaveView" to GeneratedMemberKind.PROPERTY,
            "_capturedPendingEdges" to GeneratedMemberKind.PROPERTY,
            "executeSave" to GeneratedMemberKind.FUNCTION,
    )
    for ((n, kind) in updateOnly) {
        manifest.add(artifact, n, kind, "fixed update-builder member '$n'")
    }
}
