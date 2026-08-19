package entkt.codegen

import entkt.schema.Edge
import entkt.schema.Field

// ------------------------------------------------------------------
// The single authority for generated Kotlin names.
//
// entkt performs no inflection and no storage-to-Kotlin normalization.
// A generated public identifier comes from exactly one of:
//
//   - the concrete schema class simple name (entity and entity-prefixed
//     types) — see `SchemaInput.name`;
//   - the schema's declared `clientName` (client/config properties);
//   - a field or edge's Kotlin declaration name, bound through `by`
//     (everything else) — [apiName] below;
//   - a fixed framework name (`id`, and the mechanical affixes applied
//     to a [generatedStem]).
//
// Storage strings — table, column, edge, and index names — stay storage
// metadata: they drive DDL, joins, edge lookup, migration identity, and
// driver row keys, and never name a Kotlin member.
//
// See `docs/implemented-features/schema/schema-declaration-api-names.md`.
// ------------------------------------------------------------------

/** The database column name for this field. */
internal val Field.columnName: String get() = name

/**
 * The generated API name for this field: the Kotlin `val` it was
 * declared as, never its column.
 *
 * Non-null by construction — `EntSchema.finalize()` rejects any
 * registered builder that never bound a declaration name, so a null
 * here means codegen is reading an unfinalized schema.
 */
internal val Field.apiName: String
    get() = declarationName ?: error(
        "Field '$name' has no declaration name. Schemas must be finalized before codegen " +
            "reads them, and finalization rejects unbound declarations.",
    )

/**
 * The generated API name for this edge: the Kotlin `val` it was
 * declared as, never its storage name or its target type.
 */
internal val Edge.apiName: String
    get() = declarationName ?: error(
        "Edge '$name' has no declaration name. Schemas must be finalized before codegen " +
            "reads them, and finalization rejects unbound declarations.",
    )

/**
 * A declaration name with only its first character title-cased, for
 * composing the generated API family around it: `posts` → `queryPosts`,
 * `withPosts`, `eagerPosts`; `primaryAuthor` → `primaryAuthorId`.
 *
 * These affixes are mechanical, not linguistic. Nothing here parses,
 * translates, singularizes, or pluralizes the declaration.
 */
internal fun String.generatedStem(): String = replaceFirstChar { it.uppercase() }
