package entkt.codegen

import com.squareup.kotlinpoet.FileSpec
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.ManyToManyThrough
import entkt.schema.OnDelete
import java.nio.file.Path
import kotlin.reflect.KClass

data class SchemaInput(
    val name: String,
    val schema: EntSchema,
)

/**
 * Finalize all schemas in the list if they haven't been finalized yet,
 * then run every cross-schema validation check. This is a no-op for
 * finalization when schemas were already finalized (e.g. by
 * [scanForSchemas]), but the validation checks always run.
 */
internal fun ensureFinalized(schemas: List<SchemaInput>) {
    finalizeSchemas(schemas)
    validateEdgeTargetIdentity(schemas)
    validateUniqueNamesAndTables(schemas)
    val schemaNames = schemas.associate { it.schema to it.name }
    // Generated-member-name collision detection (generated-member collision checks) is run by
    // [EntGenerator.generate] after this finalization step via
    // [runMemberCollisionCheck], producing per-artifact diagnostics.
    // The older single-namespace `validateMemberNames` was removed
    // because the manifest-based check is a strict superset on the
    // cases it covered and reports the schema, artifact, and source
    // for each collision rather than a single "both generate
    // property" message.
    validateRelationNames(schemas, schemaNames)
    validateM2MOrientation(schemas, schemaNames)
    validateThroughLinkJunctions(schemas, schemaNames)
}

/**
 * Reject duplicate schema classes, build the registry, and finalize
 * any schemas that haven't been finalized yet.
 */
private fun finalizeSchemas(schemas: List<SchemaInput>) {
    val byClass = schemas.groupBy { it.schema::class }
    for ((klass, group) in byClass) {
        if (group.size > 1) {
            error(
                "Multiple SchemaInput entries use the same class '${klass.simpleName}' — " +
                    "each schema class must appear exactly once",
            )
        }
    }
    if (schemas.any { !it.schema.isFinalized }) {
        val registry: Map<KClass<out EntSchema>, EntSchema> =
            schemas.associate { it.schema::class to it.schema }
        for (input in schemas) {
            if (!input.schema.isFinalized) {
                input.schema.finalize(registry)
            }
        }
    }
}

/**
 * Verify that all edge targets and M2M junction targets are instances
 * in the current schema set. Pre-finalized schemas may have been
 * resolved against a different registry whose instances are not the
 * same objects, causing identity-based lookups to silently miss.
 */
private fun validateEdgeTargetIdentity(schemas: List<SchemaInput>) {
    val instanceSet = schemas.map { it.schema }.toSet()
    for (input in schemas) {
        for (edge in input.schema.edges()) {
            if (edge.target !in instanceSet) {
                error(
                    "Edge '${edge.name}' on schema '${input.name}' resolved to a target " +
                        "instance not in the current schema set — this typically means a " +
                        "pre-finalized schema was mixed with freshly-constructed peers. " +
                        "Pass all schemas unfinalized and let ensureFinalized() resolve them together.",
                )
            }
            val m2m = edge.kind as? EdgeKind.ManyToMany
            if (m2m != null && m2m.through.junction !in instanceSet) {
                error(
                    "Edge '${edge.name}' on schema '${input.name}' has a ManyToMany junction " +
                        "schema instance (table '${m2m.through.junction.tableName}') not in the " +
                        "current schema set — this typically means a pre-finalized schema was " +
                        "mixed with a freshly-constructed junction. Pass all schemas unfinalized " +
                        "and let ensureFinalized() resolve them together.",
                )
            }
        }
    }
}

/** Reject duplicate schema class names and duplicate table names. */
private fun validateUniqueNamesAndTables(schemas: List<SchemaInput>) {
    val byName = schemas.groupBy { it.name }
    for ((name, group) in byName) {
        if (group.size > 1) {
            val tables = group.joinToString(", ") { it.schema.tableName }
            error("Multiple schemas share the name '$name' (tables: $tables) — schema class names must be unique")
        }
    }
    val byTable = schemas.groupBy { it.schema.tableName }
    for ((table, group) in byTable) {
        if (group.size > 1) {
            val names = group.joinToString(", ") { it.name }
            error("Multiple schemas map to table '$table': $names")
        }
    }
}

/**
 * Reject generated member-name collisions across fields, edge
 * convenience properties, and synthesized FK properties. Raw schema
 * names may differ but still derive to the same Kotlin identifier
 * (e.g. field "author_id" -> authorId, edge "author" FK -> authorId).
 */
/**
 * PostgreSQL relation names (tables, indexes, sequences) share a
 * namespace. Collect every name that will become a Postgres relation
 * and reject collisions: table names, explicit index names, and
 * synthesized unique-column index names (idx_<table>_<col>_unique).
 */
private fun validateRelationNames(
    schemas: List<SchemaInput>,
    schemaNames: Map<EntSchema, String>,
) {
    val relationOwners = mutableMapOf<String, String>()
    for (input in schemas) {
        relationOwners[input.schema.tableName] = "table '${input.name}'"
    }
    for (input in schemas) {
        val table = input.schema.tableName
        // Explicit index names from index("name", ...)
        for (idx in input.schema.indexes()) {
            val prev = relationOwners.put(idx.name, "index '${idx.name}' on '${input.name}'")
            if (prev != null) {
                error(
                    "Index name '${idx.name}' on schema '${input.name}' collides with " +
                        "$prev — relation names must be globally unique",
                )
            }
        }
        // Synthesized unique-column index names. The Postgres driver
        // emits CREATE UNIQUE INDEX idx_<table>_<col>_unique for every
        // non-PK unique column — including fields that gain uniqueness
        // from a .unique() edge via .field(handle).
        val columns = columnMetadataFor(input.schema, schemaNames)
        for (col in columns) {
            if (!col.unique || col.primaryKey) continue
            val synth = "idx_${table}_${col.name}_unique"
            val prev = relationOwners.put(synth, "synthesized unique index '$synth' on '${input.name}'")
            if (prev != null) {
                error(
                    "Synthesized unique index '$synth' for column '${col.name}' on " +
                        "schema '${input.name}' collides with $prev — " +
                        "relation names must be globally unique",
                )
            }
        }
    }
}

/**
 * Canonical relationship identity for an M2M declaration: the junction
 * schema name plus the *unordered* junction-edge pair (sorted), so both
 * orientations of the same link table map to the same key. Shared by
 * [validateM2MOrientation] and [validateThroughLinkJunctions] so they agree
 * on what counts as "the same relationship" / a two-sided declaration.
 */
private fun m2mCanonicalKey(junctionName: String, sourceEdge: String, targetEdge: String): Triple<String, String, String> {
    val (lo, hi) = if (sourceEdge <= targetEdge) sourceEdge to targetEdge else targetEdge to sourceEdge
    return Triple(junctionName, lo, hi)
}

/**
 * Reject incompatible M2M declarations that share a canonical relationship
 * identity (same junction class plus the same unordered pair of junction
 * `belongsTo` edges), for both `throughLink` and `throughEntity`.
 *
 * Per relationship identity:
 * - **One** declaration: always allowed (the lone-side case).
 * - **Two** declarations: allowed **only if pair-swapped** — one's
 *   `(sourceEdge, targetEdge)` is the other's `(targetEdge, sourceEdge)`.
 *   This is how both orientations of a symmetric link table are declared.
 *   Two declarations with the **same** orientation are rejected as
 *   a same-orientation alias.
 * - **Three or more** declarations: rejected (only two distinct orientations
 *   exist over a 2-edge junction, so 3+ necessarily repeats one).
 * - **Mixed** `throughLink` + `throughEntity` over one identity: rejected —
 *   the same junction edge pair must be modelled consistently.
 *
 * `ManyToManyBuilder.resolve()` already enforces that `sourceEdge` points back
 * at the declaring schema and `targetEdge` at the M2M target type parameter, so
 * a same-orientation alias across two distinct declaring schemas is unreachable
 * (the junction `belongsTo` cannot target both schemas) — same-orientation
 * aliases only need detecting within a single declaring schema.
 */
private fun validateM2MOrientation(
    schemas: List<SchemaInput>,
    schemaNames: Map<EntSchema, String>,
) {
    data class M2MDecl(
        val declaringSchema: String,
        val edgeName: String,
        val junctionName: String,
        val sourceEdge: String,
        val targetEdge: String,
        val mode: String, // "throughLink" or "throughEntity"
    )

    fun canonicalKey(d: M2MDecl): Triple<String, String, String> =
        m2mCanonicalKey(d.junctionName, d.sourceEdge, d.targetEdge)

    val declarations = mutableListOf<M2MDecl>()
    for (input in schemas) {
        for (edge in input.schema.edges()) {
            val m2m = edge.kind as? EdgeKind.ManyToMany ?: continue
            val through = m2m.through
            val junctionName = schemaNames[through.junction]
                ?: error(
                    "M2M edge '${edge.name}' on schema '${input.name}' references junction " +
                        "${through.junction.tableName} which is not in the current schema set",
                )
            val mode = when (through) {
                is ManyToManyThrough.LinkTable -> "throughLink"
                is ManyToManyThrough.ThroughEntity -> "throughEntity"
            }
            declarations += M2MDecl(
                declaringSchema = input.name,
                edgeName = edge.name,
                junctionName = junctionName,
                sourceEdge = through.sourceEdge,
                targetEdge = through.targetEdge,
                mode = mode,
            )
        }
    }

    val byCanonical = declarations.groupBy { canonicalKey(it) }
    for ((_, group) in byCanonical) {
        if (group.size < 2) continue

        val modes = group.map { it.mode }.toSet()
        if (modes.size > 1) {
            val descriptions = group.joinToString(", ") {
                "${it.mode} on '${it.declaringSchema}.${it.edgeName}' (${it.sourceEdge}, ${it.targetEdge})"
            }
            error(
                "Mixed M2M write models for the same canonical relationship over junction " +
                    "'${group[0].junctionName}' with edge pair {${group[0].sourceEdge}, ${group[0].targetEdge}}: " +
                    "$descriptions. The same junction edge pair must be modelled consistently — " +
                    "both sides as throughLink (pair-swapped) or both as throughEntity, not one of each.",
            )
        }

        // Same-mode group (throughLink or throughEntity): two declarations
        // are allowed only when they are pair-swapped — one (source, target),
        // the other (target, source) — the two endpoints of one relationship.
        // Same-orientation declarations are alias traversal names over the
        // same relationship and direction; reject them. (Three or more over a
        // two-element edge pair always force a same-orientation collision by
        // pigeonhole, so this also covers the ≥3 case.) symmetric link-table writes makes
        // throughLink consistent with the throughEntity bidirectional shape.
        val byOrientation = group.groupBy { it.sourceEdge to it.targetEdge }
        for ((orientation, sameOrientation) in byOrientation) {
            if (sameOrientation.size < 2) continue
            val descriptions = sameOrientation.joinToString(", ") {
                "'${it.declaringSchema}.${it.edgeName}'"
            }
            error(
                "Same-orientation alias M2M declarations on junction '${group[0].junctionName}' " +
                    "with orientation key (${orientation.first}, ${orientation.second}): $descriptions. " +
                    "Two declarations over one junction must be pair-swapped (the two endpoints, " +
                    "one (source, target) and the other (target, source)); multiple declarations " +
                    "sharing the same (sourceEdge, targetEdge) alias the same relationship and " +
                    "direction and aren't supported.",
            )
        }
    }
}

/**
 * Verify that every `throughLink(...)` M2M edge points at a junction
 * schema satisfying the direct-helper safety constraints from M2M schema modeling
 * (junction-shape rules). Schemas that fail any rule are rejected with
 * a message naming the failing junction and the rule that didn't hold;
 * the caller's options are to fix the junction shape or switch the
 * edge to `throughEntity(...)`.
 *
 * Rules enforced here:
 * 1. Junction has exactly `id` + the two FK columns (no payload fields).
 * 2. Both junction `belongsTo` edges are non-null.
 * 3. The FK backing fields carry no write-time modifiers (validators,
 *    sensitive, default, updateDefault, immutable).
 * 4. Both junction `belongsTo` edges declare `OnDelete.CASCADE`
 *    explicitly.
 * 5. Junction id strategy is not `EXPLICIT` (auto-numeric or
 *    client-UUID only).
 * 6. Junction declares a non-partial unique index on the unordered FK
 *    pair `{sourceFkColumn, targetFkColumn}` (either column order).
 * 6b. For a relationship declared from both endpoints, each side
 *    additionally needs a non-partial index leading with its own source FK.
 */
private fun validateThroughLinkJunctions(
    schemas: List<SchemaInput>,
    schemaNames: Map<EntSchema, String>,
) {
    // Canonical relationship identities (junction + unordered edge pair)
    // declared from BOTH endpoints. Each side
    // of such a declaration reads by its OWN source FK, so each needs a
    // source-FK-leading index (Rule 6b). A lone declaration is exempt.
    val twoSidedKeys: Set<Triple<String, String, String>> = run {
        val counts = mutableMapOf<Triple<String, String, String>, Int>()
        for (input in schemas) {
            for (edge in input.schema.edges()) {
                val m2m = edge.kind as? EdgeKind.ManyToMany ?: continue
                val through = m2m.through as? ManyToManyThrough.LinkTable ?: continue
                val jn = schemaNames[through.junction] ?: continue
                val k = m2mCanonicalKey(jn, through.sourceEdge, through.targetEdge)
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        counts.filterValues { it == 2 }.keys
    }

    for (input in schemas) {
        for (edge in input.schema.edges()) {
            val m2m = edge.kind as? EdgeKind.ManyToMany ?: continue
            val through = m2m.through as? ManyToManyThrough.LinkTable ?: continue

            val junction = through.junction
            val junctionName = schemaNames[junction]
                ?: error(
                    "throughLink edge '${input.name}.${edge.name}' references junction " +
                        "${junction.tableName} which is not in the current schema set",
                )
            val ctx = "throughLink edge '${input.name}.${edge.name}' (junction '$junctionName')"

            val junctionEdges = junction.edges()
            val sourceJunctionEdge = junctionEdges.firstOrNull { it.name == through.sourceEdge }
                ?: error("$ctx: sourceEdge '${through.sourceEdge}' not found on junction (codegen invariant)")
            val targetJunctionEdge = junctionEdges.firstOrNull { it.name == through.targetEdge }
                ?: error("$ctx: targetEdge '${through.targetEdge}' not found on junction (codegen invariant)")
            val sourceBt = sourceJunctionEdge.kind as EdgeKind.BelongsTo
            val targetBt = targetJunctionEdge.kind as EdgeKind.BelongsTo

            // Rule 2: non-null junction FKs.
            if (!sourceBt.required) {
                error(
                    "$ctx: source junction edge '${sourceJunctionEdge.name}' is nullable; " +
                        "throughLink requires both junction belongsTo edges to be non-null. " +
                        "Drop `.nullable()` on the junction, or model this relationship as throughEntity.",
                )
            }
            if (!targetBt.required) {
                error(
                    "$ctx: target junction edge '${targetJunctionEdge.name}' is nullable; " +
                        "throughLink requires both junction belongsTo edges to be non-null. " +
                        "Drop `.nullable()` on the junction, or model this relationship as throughEntity.",
                )
            }

            // Rule 4: OnDelete.CASCADE explicit.
            if (sourceBt.onDelete != OnDelete.CASCADE) {
                error(
                    "$ctx: source junction edge '${sourceJunctionEdge.name}' has onDelete=" +
                        "${sourceBt.onDelete}; throughLink requires explicit OnDelete.CASCADE on " +
                        "both junction belongsTo edges. Add `.onDelete(OnDelete.CASCADE)` or " +
                        "model this relationship as throughEntity.",
                )
            }
            if (targetBt.onDelete != OnDelete.CASCADE) {
                error(
                    "$ctx: target junction edge '${targetJunctionEdge.name}' has onDelete=" +
                        "${targetBt.onDelete}; throughLink requires explicit OnDelete.CASCADE on " +
                        "both junction belongsTo edges. Add `.onDelete(OnDelete.CASCADE)` or " +
                        "model this relationship as throughEntity.",
                )
            }

            // Rule 4a: junction belongsTo edges must not be `.unique()` —
            // that would constrain each source/target endpoint to at most
            // one junction row, turning the M2M into 1:1 and breaking
            // helper semantics like repeated `tags.add(...)` calls.
            if (sourceBt.unique) {
                error(
                    "$ctx: source junction edge '${sourceJunctionEdge.name}' is `.unique()`; this " +
                        "constrains each ${schemaNames[sourceJunctionEdge.target] ?: sourceJunctionEdge.target.tableName} " +
                        "to at most one junction row, turning the relationship into 1:1 and breaking " +
                        "M2M helper semantics. Drop `.unique()` (the composite unique pair " +
                        "index from Rule 6 already enforces pair uniqueness) or model this " +
                        "relationship as throughEntity / hasOne+belongsTo.",
                )
            }
            if (targetBt.unique) {
                error(
                    "$ctx: target junction edge '${targetJunctionEdge.name}' is `.unique()`; this " +
                        "constrains each ${schemaNames[targetJunctionEdge.target] ?: targetJunctionEdge.target.tableName} " +
                        "to at most one junction row, turning the relationship into 1:1 and breaking " +
                        "M2M helper semantics. Drop `.unique()` (the composite unique pair " +
                        "index from Rule 6 already enforces pair uniqueness) or model this " +
                        "relationship as throughEntity / hasOne+belongsTo.",
                )
            }

            // Resolve FK column names — after `.field(handle)` if explicit,
            // otherwise the synthesized `${edgeName}_id`.
            val sourceFkCol = sourceBt.field ?: "${sourceJunctionEdge.name}_id"
            val targetFkCol = targetBt.field ?: "${targetJunctionEdge.name}_id"

            // Rule 1a: no extra belongsTo edges beyond the two named in
            // throughLink. An extra junction belongsTo adds an FK column
            // (synthesized or field-backed) the helpers will not populate.
            // `scalarFields()` filters out *all* belongsTo backing columns
            // — including the unnamed extra one — so without this check a
            // third belongsTo would silently slip past Rule 1b's payload
            // scan even though the junction has three FK columns.
            val extraBelongsTo = junctionEdges
                .filter { it.kind is EdgeKind.BelongsTo }
                .filter { it !== sourceJunctionEdge && it !== targetJunctionEdge }
            if (extraBelongsTo.isNotEmpty()) {
                val names = extraBelongsTo.joinToString(", ") {
                    val bt = it.kind as EdgeKind.BelongsTo
                    val col = bt.field ?: "${it.name}_id"
                    "'${it.name}' (FK column '$col' → ${schemaNames[it.target] ?: it.target.tableName})"
                }
                error(
                    "$ctx: junction declares extra belongsTo edge(s) beyond the named " +
                        "sourceEdge/targetEdge: $names. throughLink helpers only populate the two " +
                        "FK columns named in the manyToMany declaration, so any additional " +
                        "junction FK would be left unset on insert. Drop the extra edge(s) or " +
                        "model this relationship as throughEntity.",
                )
            }

            // Rule 1b: no payload scalar fields. `scalarFields()` excludes
            // columns that back a belongsTo edge; with Rule 1a above, the
            // only remaining belongsTo-backed columns are the two named
            // FKs, so any leftover scalar here is genuine payload.
            val payload = scalarFields(junction)
            if (payload.isNotEmpty()) {
                val names = payload.joinToString(", ") { "'${it.name}'" }
                error(
                    "$ctx: junction carries payload field(s) $names besides the id and the two " +
                        "FK columns; throughLink helpers bypass the junction repo, so payload " +
                        "fields would silently skip defaults/hooks/validation. Move these fields " +
                        "to a domain-entity junction declared with throughEntity, or drop them.",
                )
            }

            // Rule 3: no write-time modifiers on FK backing fields.
            val fieldsByName = junction.fields().associateBy { it.name }
            for ((label, fkCol) in listOf("source" to sourceFkCol, "target" to targetFkCol)) {
                val backing = fieldsByName[fkCol] ?: continue // synthesized FK has no backing Field, nothing to check
                if (backing.validators.isNotEmpty()) {
                    error(
                        "$ctx: $label FK backing field '${backing.name}' carries " +
                            "${backing.validators.size} validator(s); throughLink helpers bypass " +
                            "junction CREATE validation, so the validator would silently not run. " +
                            "Move the field to a throughEntity junction or drop the validator.",
                    )
                }
                if (backing.sensitive) {
                    error(
                        "$ctx: $label FK backing field '${backing.name}' is `.sensitive()`; " +
                            "throughLink helpers bypass junction toString redaction, so the " +
                            "modifier would silently not apply. Drop it or use throughEntity.",
                    )
                }
                if (backing.default != null) {
                    error(
                        "$ctx: $label FK backing field '${backing.name}' has a `.default(...)`; " +
                            "throughLink helpers insert junction rows directly through the driver " +
                            "and never apply field defaults. Drop the default or use throughEntity.",
                    )
                }
                if (backing.updateDefault != null) {
                    error(
                        "$ctx: $label FK backing field '${backing.name}' has an " +
                            "`.updateDefault(...)`; throughLink helpers do not update junction " +
                            "rows after insert, so the marker is inert. Drop it or use throughEntity.",
                    )
                }
                if (backing.immutable) {
                    error(
                        "$ctx: $label FK backing field '${backing.name}' is `.immutable()`; " +
                            "throughLink helpers never update junction rows, so the marker adds " +
                            "no enforcement on this path. Drop it or use throughEntity.",
                    )
                }
                // Note: a `.unique()` on the backing field can't survive
                // schema finalization unless the belongsTo edge is also
                // `.unique()` (the existing edge/field consistency check
                // from to-one FK nullability rejects the mismatch). When the edge IS
                // `.unique()`, Rule 4a above fires before we ever look
                // at the backing field — so a separate Rule 3 unique
                // check would be dead code.
            }

            // Rule 5: id strategy not EXPLICIT.
            if (idStrategyName(junction) == "EXPLICIT") {
                error(
                    "$ctx: junction id strategy is EXPLICIT (caller-provided id); throughLink " +
                        "helpers cannot mint caller ids. Use an auto-numeric id (EntId.long() / " +
                        "EntId.int()) or a client-UUID id (EntId.uuid()), or model this " +
                        "relationship as throughEntity.",
                )
            }

            // Rule 6: a non-partial unique composite index on the unordered
            // FK pair — either column order satisfies it (symmetric link-table writes). Pair
            // uniqueness is order-independent, and a pair-swapped second
            // declaration's "source-first" order is the reverse.
            val indexes = try { junction.indexes() } catch (e: Exception) {
                error("$ctx: junction indexes could not be resolved: ${e.message}")
            }
            val qualifying = indexes.firstOrNull {
                it.unique &&
                    it.where == null &&
                    it.fields.size == 2 &&
                    it.fields.toSet() == setOf(sourceFkCol, targetFkCol)
            }
            if (qualifying == null) {
                error(
                    "$ctx: junction is missing a non-partial unique composite index on the " +
                        "pair ($sourceFkCol, $targetFkCol) (either column order). Declare one with " +
                        "`index(\"<name>\", <source_fk>, <target_fk>).unique()` (no `.where(...)`), " +
                        "or model this relationship as throughEntity.",
                )
            }

            // Rule 6b (symmetric link-table writes): when the relationship is declared from both
            // endpoints, THIS side does source-keyed reads (set exact-set,
            // add/remove deltas, read traversal, eager-load) by its own
            // source FK — require a non-partial index whose LEADING column is
            // that FK. The unique pair index satisfies only the side it leads
            // with; the opposite endpoint needs its own leading-column index.
            // (Not relaxed for `.readOnly()` — a read-only side still reads by
            // source.) A LONE declaration is exempt — NOT because it always has
            // a source-leading index (Rule 6 accepts the unique pair index in
            // either column order, so a lone side's only index may lead with the
            // target FK), but for backward compatibility: lone declarations are
            // the pre-existing case, and tightening this would reject schemas the
            // old source-first rule's relaxation now accepts. The cost is a
            // possible sequential-scan source read on such a lone junction — a
            // performance gap, not a correctness issue. See the spec's Backward
            // Compatibility note.
            val isTwoSided = m2mCanonicalKey(junctionName, through.sourceEdge, through.targetEdge) in twoSidedKeys
            if (isTwoSided) {
                val hasLeading = indexes.any { it.where == null && it.fields.firstOrNull() == sourceFkCol }
                if (!hasLeading) {
                    error(
                        "$ctx is one side of a two-sided link table, so it reads by its source FK " +
                            "'$sourceFkCol', but the junction has no non-partial index leading with " +
                            "'$sourceFkCol'. Declare one leading with the source FK, e.g. " +
                            "`index(\"<name>\", <$sourceFkCol field>, <$targetFkCol field>)` " +
                            "(unique or not). The unique pair index only covers the side it leads with.",
                    )
                }
            }

            // Rule 6a: no extra unique index that constrains a single FK
            // column (partial or not). A `unique()` index on just
            // `source_fk` or `target_fk` would force at most one junction
            // row per endpoint — same broken-M2M effect as Rule 4a's
            // `.unique()` on the belongsTo edge. The contract explicitly
            // allows additional non-unique single-column indexes (for
            // join planning), so we only reject the unique single-FK
            // case here.
            val badFkUniqueIndex = indexes.firstOrNull { idx ->
                idx.unique && idx.fields.size == 1 &&
                    (idx.fields.single() == sourceFkCol || idx.fields.single() == targetFkCol)
            }
            if (badFkUniqueIndex != null) {
                error(
                    "$ctx: junction declares unique single-column index '${badFkUniqueIndex.name}' " +
                        "on '${badFkUniqueIndex.fields.single()}'; this constrains each endpoint " +
                        "to at most one junction row, turning the M2M into 1:1 and breaking helper " +
                        "semantics. Drop the `.unique()` (a non-unique single-column index for " +
                        "join planning is fine) or model this relationship as throughEntity.",
                )
            }
        }
    }
}

class EntGenerator(
    private val packageName: String,
) {
    private val entityGenerator = EntityGenerator(packageName)
    private val mutationGenerator = MutationGenerator(packageName)
    private val createGenerator = CreateGenerator(packageName)
    private val updateGenerator = UpdateGenerator(packageName)
    private val queryGenerator = QueryGenerator(packageName)
    private val repoGenerator = RepoGenerator(packageName)
    private val privacyGenerator = PrivacyGenerator(packageName)
    private val validationGenerator = ValidationGenerator(packageName)
    private val clientGenerator = ClientGenerator(packageName)

    fun generate(schemas: List<SchemaInput>): List<FileSpec> {
        ensureFinalized(schemas)
        val schemaNames: Map<EntSchema, String> = schemas.associate { it.schema to it.name }

        // generated-member collision checks: reject schemas that would emit duplicate Kotlin
        // member names on the same generated artifact. Runs here
        // (not only via SchemaInspector.validate) so direct callers
        // of EntGenerator.generate(...) can't bypass the check.
        // `strict = true` lets manifest-build failures propagate —
        // the hard validation path must not silently skip the
        // collision check for a schema with a broken manifest.
        // Throws with every detected collision joined, so callers
        // see all problems in one error message rather than
        // resolving them one at a time.
        val collisions = runMemberCollisionCheck(schemas, schemaNames, strict = true)
        if (collisions.isNotEmpty()) {
            error(
                "Generated member-name collisions detected:\n" +
                    collisions.joinToString("\n") { "  - " + formatMemberCollisionDiagnostic(it) },
            )
        }

        // declaration-name capture: reject schemas whose belongsTo(...).field(handle)
        // backing has no captured Kotlin val name. SchemaInspector
        // runs the same check, but direct callers of
        // EntGenerator.generate(...) (no inspector pass) must not
        // silently fall through to computeEdgeFks's
        // toCamelCase(column) fallback.
        val rfc06Errors = schemas.flatMap { findFieldBackedFkDeclarationErrors(it) } +
            schemas.flatMap { findDuplicateDeclarationAliases(it) }
        if (rfc06Errors.isNotEmpty()) {
            error(
                "Field-backed FK declaration capture failed:\n" +
                    rfc06Errors.joinToString("\n") { "  - $it" },
            )
        }
        val perSchema = schemas.flatMap { (name, schema) ->
            listOf(
                entityGenerator.generate(name, schema, schemaNames),
                mutationGenerator.generate(name, schema, schemaNames),
                createGenerator.generate(name, schema, schemaNames),
                updateGenerator.generate(name, schema, schemaNames),
                queryGenerator.generate(name, schema, schemaNames),
                repoGenerator.generate(name, schema, schemaNames),
                privacyGenerator.generate(name, schema, schemaNames),
                validationGenerator.generate(name, schema, schemaNames),
            )
        }
        return perSchema + clientGenerator.generate(schemas)
    }

    fun writeTo(outputDir: Path, schemas: List<SchemaInput>) {
        generate(schemas).forEach { fileSpec ->
            fileSpec.writeTo(outputDir)
        }
    }
}
