package entkt.codegen

import entkt.schema.EdgeKind
import entkt.schema.EntSchema

/**
 * Walk a schema's resolved metadata and populate a
 * [GeneratedMemberManifest] with every name the codegen will emit
 * on each artifact in the V1 coverage scope. Phase 2 of the RFC 07
 * implementation; Phase 3 detects collisions against this output.
 *
 * V1 coverage:
 *
 * - **entity data class** (`${name}`) — scalar field properties,
 *   FK properties, fixed `id` / `edges` accessors, data-class
 *   synthesized members (`copy`, `equals`, `hashCode`, `toString`,
 *   `componentN`).
 * - **entity companion** (`${name}.Companion`) — `fromRow`,
 *   `TABLE`, `SCHEMA`.
 * - **mutation interface** (`${name}Mutation`) — mutable scalar
 *   field properties, mutable FK properties.
 * - **create builder** (`${name}Create`) — every mutable scalar
 *   field setter, every FK setter (immutable FKs are create-only
 *   writable, so they appear here), fixed builder members.
 * - **update builder** (`${name}Update`) — mutable scalar field
 *   setters, mutable FK setters, `unset{X}()` method for each
 *   mutable scalar field and FK, fixed builder members
 *   (including `id`, `consistency`).
 * - **create mutation view** (`${name}CreateMutationView`) —
 *   inherited mutation props + create-only immutable scalar /
 *   immutable FK setters.
 * - **update mutation view** (`${name}UpdateMutationView`) —
 *   inherited mutation props + `unset{X}()` methods + fixed
 *   `pendingEdges` + helper-eligible M2M mutator properties.
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
    addEntityCompanionMembers(manifest, schemaName)
    addMutationInterfaceMembers(manifest, schemaName, mutableScalars, mutableFks)
    addCreateBuilderMembers(manifest, schemaName, scalars, fks)
    addUpdateBuilderMembers(manifest, schemaName, mutableScalars, mutableFks)
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
    // errors today. The RFC's Test Requirements include `copy` and
    // `componentN`, so we cover them explicitly.
    manifest.add(artifact, "copy", GeneratedMemberKind.FUNCTION, "data-class synthesized copy")
    manifest.add(artifact, "equals", GeneratedMemberKind.FUNCTION, "data-class synthesized equals")
    manifest.add(artifact, "hashCode", GeneratedMemberKind.FUNCTION, "data-class synthesized hashCode")
    manifest.add(artifact, "toString", GeneratedMemberKind.FUNCTION, "data-class synthesized toString")

    // componentN: the entity data class's primary constructor has
    // one slot per scalar field + one slot per FK + the id. The
    // exact ordering of synthesized component functions matches
    // the constructor order; we enumerate `component1`..`componentK`
    // for K = id + scalars + fks so that any user field whose
    // generated name lands on `component1`..`componentK` collides.
    val componentCount = 1 + scalars.size + fks.size
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
    // field-backed FKs are named `toCamelCase(backingColumn)`
    // (pre-RFC-06). The RFC's V1 critical-path collision: a
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
    // V1 leaves the Edges artifact out per the RFC's Required
    // Coverage list (entity `edges` accessor is fixed, but
    // per-edge members on the Edges class are out of V1 scope).
    @Suppress("UNUSED_PARAMETER") nonFkEdges
}

// ── Entity companion ─────────────────────────────────────────────

private fun addEntityCompanionMembers(
    manifest: GeneratedMemberManifest,
    schemaName: String,
) {
    val artifact = companionArtifact(schemaName)
    manifest.add(artifact, "fromRow", GeneratedMemberKind.FUNCTION, "fixed entity companion decoder")
    manifest.add(artifact, "TABLE", GeneratedMemberKind.PROPERTY, "fixed entity companion TABLE")
    manifest.add(artifact, "SCHEMA", GeneratedMemberKind.PROPERTY, "fixed entity companion SCHEMA")
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
) {
    val artifact = updateBuilderArtifact(schemaName)

    addFixedBuilderMembers(manifest, artifact, includeUpdateOnly = true)

    for (field in mutableScalars) {
        val prop = fieldPropertyName(field.name)
        manifest.add(artifact, prop, GeneratedMemberKind.PROPERTY, "field '${field.name}' setter")
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
            "FK setter for edge '${fk.edgeName}'",
        )
        manifest.add(
            artifact,
            unsetMethodName(fk.propertyName),
            GeneratedMemberKind.FUNCTION,
            "unset method for FK edge '${fk.edgeName}'",
        )
    }
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

    // `pendingEdges` aggregator — fixed on schemas with at least
    // one helper-eligible M2M edge. Schemas with no link-table
    // M2M don't get this member; the manifest reflects that so
    // those schemas can use `pendingEdges` as a regular field
    // name without false-positive collisions.
    if (helperEligibleM2M.isNotEmpty()) {
        manifest.add(
            artifact,
            "pendingEdges",
            GeneratedMemberKind.PROPERTY,
            "fixed update-mutation-view pendingEdges aggregator (helper-eligible M2M edges present)",
        )
        // M2M mutator properties — one per helper-eligible M2M
        // edge, named via `HelperEligibleM2M.mutatorPropertyName`
        // (currently `toCamelCase(edge.name)`).
        for (m2m in helperEligibleM2M) {
            manifest.add(
                artifact,
                m2m.mutatorPropertyName,
                GeneratedMemberKind.PROPERTY,
                "M2M edge mutator for edge '${m2m.edgeName}'",
            )
        }
    }
}

// ── Fixed builder members ────────────────────────────────────────

private fun addFixedBuilderMembers(
    manifest: GeneratedMemberManifest,
    artifact: String,
    includeUpdateOnly: Boolean,
) {
    // Shared between Create and Update builders.
    val shared = listOf(
        "save" to GeneratedMemberKind.FUNCTION,
        "saveOrError" to GeneratedMemberKind.FUNCTION,
        "saveOrThrow" to GeneratedMemberKind.FUNCTION,
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
        )
        for ((n, kind) in updateOnly) {
            manifest.add(artifact, n, kind, "fixed update-builder member '$n'")
        }
    } else {
        // Create-only fixed members.
        val createOnly = listOf(
            "beforeCreateHooks" to GeneratedMemberKind.PROPERTY,
            "afterCreateHooks" to GeneratedMemberKind.PROPERTY,
        )
        for ((n, kind) in createOnly) {
            manifest.add(artifact, n, kind, "fixed create-builder member '$n'")
        }
    }
}
