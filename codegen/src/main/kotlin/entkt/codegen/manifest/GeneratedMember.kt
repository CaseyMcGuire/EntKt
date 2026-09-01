package entkt.codegen.manifest

import entkt.codegen.SchemaInput
import entkt.codegen.metadata.helperEligibleM2MEdges
/**
 * One generated Kotlin member that codegen will emit on a specific
 * generated artifact (an entity data class, a builder, a hook state,
 * a patch type, etc.). The [GeneratedMemberManifest] collects
 * these and the validator rejects any artifact whose `(name, kind)`
 * grouping has more than one source — see contract
 * `docs/possible-features/edge-mutation/07-generated-member-name-collisions.md`.
 *
 * Identity is `(artifact, name)`, **not** `(artifact, name, kind)`:
 * Kotlin won't compile a property and a function with the same
 * name on the same class, so a property→function collision is a
 * real diagnostic even though it isn't a JVM signature clash.
 *
 * Storage names (DB column names, table names) are intentionally
 * not part of this model — those are validated elsewhere and don't
 * share a Kotlin member namespace with generated source members.
 */
internal data class GeneratedMember(
    /**
     * The generated artifact this member lives on. Concrete strings
     * (e.g. `"Post"`, `"PostBeforeUpdateState"`,
     * `"Post.Companion"`) rather than an enum — V1 keeps the set
     * open so future codegen passes can register new artifacts
     * without a runtime enum change. The validator groups by this
     * field, so consistency across emitters matters.
     */
    val artifact: String,
    /** The generated source name, e.g. `unsetWriter` or `pendingEdges`. */
    val name: String,
    /** Kind of generated member; used only for diagnostic phrasing. */
    val kind: GeneratedMemberKind,
    /**
     * Short human-readable description of where this member came
     * from, used in the diagnostic. Examples: `"field 'name'"`,
     * `"FK for edge 'author'"`, `"unset method for field-backed FK
     * edge 'author'"`, `"fixed builder member save"`,
     * `"data-class synthesized copy"`. Keep these short and
     * declaration-rooted — the validator splices them into a
     * sentence verbatim.
     */
    val source: String,
)

/**
 * Coarse classification of a generated member. Distinguishes
 * shapes Kotlin treats differently at the call site (a property
 * vs. a function vs. a nested type). The validator uses this to
 * phrase its diagnostic ("property 'x' collides with method 'x'");
 * it does NOT use it to allow duplicates of different kinds — V1
 * rejects every `(artifact, name)` clash regardless of kind.
 */
internal enum class GeneratedMemberKind {
    PROPERTY,

    /**
     * A `var` on a generated mutation surface. Tracked apart from
     * [PROPERTY] because a settable property also occupies a JVM
     * *setter* signature, and two source-distinct names can map onto one
     * — see [jvmSetterName].
     */
    MUTABLE_PROPERTY,
    FUNCTION,
    NESTED_TYPE,
    CONSTRUCTOR_PARAMETER,
}

/**
 * One detected collision: two or more [GeneratedMember]s on the
 * same artifact share a `name`. The validator emits one diagnostic
 * per collision, listing every source so the schema author sees
 * all contributing declarations rather than only the first two.
 *
 * V1 doesn't model "intentional" duplicates. Every
 * `(artifact, name)` group with size > 1 is an error.
 */
internal data class MemberCollision(
    /** Schema this collision was detected on (used in the diagnostic). */
    val schema: String,
    /** Artifact the colliding members all live on. */
    val artifact: String,
    /** The shared generated name. */
    val name: String,
    /**
     * Every contributing member, in declaration / registration
     * order. The validator does not deduplicate `source` strings
     * across the list — two sources are allowed to phrase
     * themselves identically (e.g. two fields with the same
     * generated name) and the diagnostic includes both.
     */
    val sources: List<GeneratedMember>,
)

/**
 * Per-schema accumulator for [GeneratedMember]s across all the
 * artifacts the codegen will emit for that schema. of the
 * generated-member collision checks implementation walks each schema's resolved metadata
 * (fields, edges, FKs, helper-eligible M2M info, fixed framework
 * members) and feeds members in; calls [findCollisions]
 * to surface duplicates.
 *
 * The manifest is per-schema rather than global because the contract
 * scopes collisions per artifact and artifacts are named per
 * schema (`PostUpdateDraft` is a different namespace from
 * `ArticleUpdateDraft`). A cross-schema collision is not a real
 * collision — Kotlin distinguishes the two classes.
 */
internal class GeneratedMemberManifest(val schema: String) {
    private val entries: MutableList<GeneratedMember> = mutableListOf()

    /** Register one generated member on an artifact for this schema. */
    fun add(member: GeneratedMember) {
        entries.add(member)
    }

    /** Convenience overload for direct call-site phrasing. */
    fun add(artifact: String, name: String, kind: GeneratedMemberKind, source: String) {
        entries.add(GeneratedMember(artifact, name, kind, source))
    }

    /**
     * Walk the accumulated entries and return one [MemberCollision]
     * per `(artifact, name)` group with more than one source.
     * Stable order: collisions sort by `(artifact, name)` so
     * diagnostic ordering is deterministic across runs (golden
     * tests depend on this).
     */
    fun findCollisions(): List<MemberCollision> {
        val byName = entries.groupBy { it.artifact to it.name }
            .filter { (_, members) -> members.size > 1 }
            .map { (key, members) ->
                MemberCollision(
                    schema = schema,
                    artifact = key.first,
                    name = key.second,
                    sources = members,
                )
            }

        // Source-distinct names can still collide on the JVM. Kotlin maps
        // `var isActive` to `setActive(...)` and `var active` to the same
        // signature, so a schema declaring both compiles to a platform
        // declaration clash inside generated source. Only settable
        // members are affected: the getters (`isActive()` / `getActive()`)
        // stay distinct.
        val bySetter = entries
            .filter { it.kind == GeneratedMemberKind.MUTABLE_PROPERTY }
            .groupBy { it.artifact to jvmSetterName(it.name) }
            .filter { (_, members) -> members.map { it.name }.distinct().size > 1 }
            .map { (key, members) ->
                MemberCollision(
                    schema = schema,
                    artifact = key.first,
                    name = "${key.second}(…)",
                    sources = members,
                )
            }

        return (byName + bySetter)
            .sortedWith(compareBy({ it.artifact }, { it.name }))
    }

    /** Read-only view, for tests and diagnostics that want the full list. */
    fun snapshot(): List<GeneratedMember> = entries.toList()
}

/**
 * Run the generated-member collision checks manifest + collision check across [schemas] and
 * return every detected [MemberCollision]. Shared between
 * [SchemaInspector.validate] and [EntGenerator.generate], with
 * the two callers using different strictness modes via [strict].
 *
 * `strict = false` (default — used by [SchemaInspector.validate]):
 * per-schema manifest-build failures are swallowed and the schema
 * is skipped. The rationale is that the inspector's upstream
 * per-schema passes (column / edge / index resolution) typically
 * surface the root cause as its own diagnostic, and we don't want
 * derivative manifest-builder failures drowning that signal in
 * the result's errors list.
 *
 * `strict = true` (used by [EntGenerator.generate]): manifest-
 * build failures propagate. The hard validation path must not
 * silently skip collision checks for a schema — a manifest build
 * that throws indicates either a codegen bug or a malformed schema
 * that earlier validation didn't catch, and either should be a
 * loud failure rather than a silent pass.
 *
 * In strict mode, `helperEligibleM2MEdges` is also allowed to
 * propagate. A thrown call there would leave the `helperEligible`
 * list empty, which would silently omit the per-edge M2M mutator
 * properties from the update-draft manifest — hiding collisions
 * between user-declared fields and the mutator properties (e.g.
 * `val tags = string("tags")` on a schema with a
 * `manyToMany<Tag>("tags")` through-link edge). Lenient mode keeps
 * the fail-soft so the inspector doesn't drown out the upstream
 * error that almost certainly caused the M2M-resolution throw.
 */
internal fun runMemberCollisionCheck(
    schemas: List<SchemaInput>,
    schemaNames: Map<entkt.schema.EntSchema, String>,
    strict: Boolean = false,
): List<MemberCollision> {
    val collisions = mutableListOf<MemberCollision>()
    for (input in schemas) {
        val helperEligible = if (strict) {
            // No try/catch: an M2M-resolution failure means we'd
            // build an incomplete manifest and silently miss
            // mutator-property collisions on the update draft.
            helperEligibleM2MEdges(input.schema, schemaNames)
        } else {
            try {
                helperEligibleM2MEdges(input.schema, schemaNames)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val manifest = if (strict) {
            // No try/catch: a manifest-build failure here is a real
            // bug that the generator path must surface, not skip.
            buildMemberManifest(input.name, input.schema, schemaNames, helperEligible)
        } else {
            try {
                buildMemberManifest(input.name, input.schema, schemaNames, helperEligible)
            } catch (e: Exception) {
                continue
            }
        }
        collisions.addAll(manifest.findCollisions())
    }
    return collisions
}

/**
 * The JVM setter name Kotlin generates for a property.
 *
 * A property whose name begins with `is` followed by a non-lowercase
 * character keeps that name as its getter and drops the prefix for its
 * setter, so `isActive` sets through `setActive`. Every other name gets
 * the usual `set` + capitalized form. The rule is independent of the
 * property's type — `var isActive: String` clashes with
 * `var active: String` exactly as the Boolean pair does.
 */
internal fun jvmSetterName(propertyName: String): String {
    val usesIsPrefix = propertyName.length > 2 &&
        propertyName.startsWith("is") &&
        !propertyName[2].isLowerCase()
    return if (usesIsPrefix) {
        "set" + propertyName.substring(2)
    } else {
        "set" + propertyName.replaceFirstChar { it.uppercaseChar() }
    }
}
