package entkt.codegen.manifest

import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.HelperEligibleM2M
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.scalarFields
import entkt.codegen.toCamelCase
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
 *   per scalar field (`toCamelCase(field.name)`), one column ref
 *   per FK (`fk.propertyName`), and one edge ref per edge
 *   (`toCamelCase(edge.name)`).
 * - **mutation interface** (`${name}Mutation`) — mutable scalar
 *   field properties, mutable FK properties.
 * - **create builder** (`${name}Create`) — every mutable scalar
 *   field setter, every FK setter (immutable FKs are create-only
 *   writable, so they appear here), fixed builder members.
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
    addEntityCompanionMembers(manifest, schemaName, scalars, fks, schema.edges())
    addMutationInterfaceMembers(manifest, schemaName, mutableScalars, mutableFks)
    addCreateBuilderMembers(manifest, schemaName, scalars, fks)
    addUpdateBuilderMembers(manifest, schemaName, mutableScalars, mutableFks, helperEligibleM2M)
    addCreateMutationViewMembers(manifest, schemaName, immutableScalars, immutableFks)
    addUpdateMutationViewMembers(manifest, schemaName, mutableScalars, mutableFks, helperEligibleM2M)

    return manifest
}

// ── Artifact names ────────────────────────────────────────────────

private fun entityArtifact(name: String): String = name
private fun companionArtifact(name: String): String = "$name.Companion"
private fun mutationInterfaceArtifact(name: String): String = "${name}Mutation"
private fun createBuilderArtifact(name: String): String = "${name}Create"
private fun updateBuilderArtifact(name: String): String = "${name}Update"
private fun createViewArtifact(name: String): String = "${name}CreateMutationView"
private fun updateViewArtifact(name: String): String = "${name}UpdateMutationView"

// ── Name derivation helpers ───────────────────────────────────────

private fun fieldPropertyName(fieldName: String): String = toCamelCase(fieldName)
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
            fieldPropertyName(field.name),
            GeneratedMemberKind.PROPERTY,
            "field '${field.name}'",
        )
    }

    // FK properties. Implicit FKs are named `${edgeName}Id`;
    // field-backed FKs are named `toCamelCase(backingColumn)`.
    // The critical collision is when a
    // scalar field's generated property name equals an FK's
    // generated property name on the same entity.
    for (fk in fks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            if (fk.isFieldBacked) "FK for field-backed edge '${fk.edgeName}'"
            else "FK for edge '${fk.edgeName}'",
        )
    }

    // Non-belongsTo edges (hasMany / hasOne / M2M) — these
    // accessors live on `${Entity}Edges`, NOT on the entity
    // class itself, so they don't conflict here. They're tracked
    // in their own artifact if/when we add coverage for it.
    // The entity `edges` accessor is fixed, but per-edge members on
    // the Edges class are intentionally out of scope here.
    @Suppress("UNUSED_PARAMETER") nonFkEdges
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
    // uses toCamelCase(field.name) for the property name).
    for (field in scalars) {
        manifest.add(
            artifact,
            fieldPropertyName(field.name),
            GeneratedMemberKind.PROPERTY,
            "column ref for field '${field.name}'",
        )
    }

    // One column ref per FK (EntityGenerator.buildEdgeColumnRef
    // uses fk.propertyName).
    for (fk in fks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            "column ref for FK edge '${fk.edgeName}'",
        )
    }

    // One edge ref per edge — including non-belongsTo edges
    // (EntityGenerator.buildEdgeRef uses toCamelCase(edge.name)).
    // The edge refs are how the runtime walks `has` / `exists`
    // predicates, so every declared edge contributes one.
    for (edge in allEdges) {
        manifest.add(
            artifact,
            toCamelCase(edge.name),
            GeneratedMemberKind.PROPERTY,
            "edge ref for edge '${edge.name}'",
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
            fieldPropertyName(field.name),
            GeneratedMemberKind.PROPERTY,
            "mutable field '${field.name}'",
        )
    }
    for (fk in mutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            "mutable FK for edge '${fk.edgeName}'",
        )
    }
}

// ── Create builder ───────────────────────────────────────────────

private fun addCreateBuilderMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
    allScalars: List<entkt.schema.Field>,
    allFks: List<EdgeFk>,
) {
    val artifact = createBuilderArtifact(schemaName)

    // Fixed builder members. The hook lists, `client`, `driver`
    // are constructor parameters in the generated class — they
    // share the Kotlin member namespace with declared properties.
    addFixedBuilderMembers(manifest, artifact, includeUpdateOnly = false)

    // Create allows setting every scalar field (mutable + immutable)
    // and every FK (mutable + immutable). Immutability is a
    // "no UPDATE writes" rule, not a "no CREATE writes" rule.
    for (field in allScalars) {
        manifest.add(
            artifact,
            fieldPropertyName(field.name),
            GeneratedMemberKind.PROPERTY,
            "field '${field.name}' setter",
        )
    }
    for (fk in allFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            "FK setter for edge '${fk.edgeName}'",
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

    addFixedBuilderMembers(manifest, artifact, includeUpdateOnly = true)

    for (field in mutableScalars) {
        manifest.add(
            artifact,
            fieldPropertyName(field.name),
            GeneratedMemberKind.PROPERTY,
            "field '${field.name}' setter",
        )
    }
    for (fk in mutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            "FK setter for edge '${fk.edgeName}'",
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
            "M2M edge mutator for edge '${m2m.edgeName}'",
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
            fieldPropertyName(field.name),
            GeneratedMemberKind.PROPERTY,
            "immutable field '${field.name}' (create-only writable)",
        )
    }
    for (fk in immutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            "immutable FK setter for edge '${fk.edgeName}' (create-only writable)",
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
        val prop = fieldPropertyName(field.name)
        manifest.add(artifact, prop, GeneratedMemberKind.PROPERTY, "mutable field '${field.name}'")
        manifest.add(
            artifact,
            unsetMethodName(prop),
            GeneratedMemberKind.FUNCTION,
            "unset method for field '${field.name}'",
        )
    }
    for (fk in mutableFks) {
        manifest.add(
            artifact,
            fk.propertyName,
            GeneratedMemberKind.PROPERTY,
            "mutable FK for edge '${fk.edgeName}'",
        )
        manifest.add(
            artifact,
            unsetMethodName(fk.propertyName),
            GeneratedMemberKind.FUNCTION,
            "unset method for FK edge '${fk.edgeName}'",
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

private fun addFixedBuilderMembers(
    manifest: GeneratedMemberManifest,
    artifact: String,
    includeUpdateOnly: Boolean,
) {
    // Shared between Create and Update builders. The internal /
    // private execution members are registered for the same reason as
    // the `_`-prefixed adapters below: declaration-name capture can
    // produce a user field whose generated property would collide.
    val shared = listOf(
        "save" to GeneratedMemberKind.FUNCTION,
        "saveAndLoad" to GeneratedMemberKind.FUNCTION,
        "client" to GeneratedMemberKind.PROPERTY,
        "driver" to GeneratedMemberKind.PROPERTY,
        "entity" to GeneratedMemberKind.PROPERTY,
        "beforeSaveHooks" to GeneratedMemberKind.PROPERTY,
    )
    for ((n, kind) in shared) {
        manifest.add(artifact, n, kind, "fixed builder member '$n'")
    }
    if (includeUpdateOnly) {
        // Update-only fixed members.
        val updateOnly = listOf(
            "id" to GeneratedMemberKind.PROPERTY,
            "consistency" to GeneratedMemberKind.PROPERTY,
            "dirtyFields" to GeneratedMemberKind.PROPERTY,
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
    } else {
        // Create-only fixed members.
        val createOnly = listOf(
            "beforeCreateHooks" to GeneratedMemberKind.PROPERTY,
            "afterCreateHooks" to GeneratedMemberKind.PROPERTY,
            // create-hook adapter private adapter properties. Same reachability
            // story as the update side above — schemas using declaration-name capture
            // declaration capture can produce a `_beforeSaveView`-
            // or `_createMutationView`-named FK property that would
            // collide with these.
            "_beforeSaveView" to GeneratedMemberKind.PROPERTY,
            "_createMutationView" to GeneratedMemberKind.PROPERTY,
            "executeSaveForInternalUse" to GeneratedMemberKind.FUNCTION,
        )
        for ((n, kind) in createOnly) {
            manifest.add(artifact, n, kind, "fixed create-builder member '$n'")
        }
    }
}
