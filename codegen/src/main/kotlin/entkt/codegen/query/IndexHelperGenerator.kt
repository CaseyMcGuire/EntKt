package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.columnName
import entkt.codegen.metadata.columnMetadataFor
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.codegen.toCamelCase
import entkt.schema.ColumnStorage
import entkt.schema.EntSchema
import entkt.schema.FieldType

private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val ENT_RESULT = ClassName("entkt.runtime.result", "EntResult")
private val INDEX_RANGE_BUILDER = ClassName("entkt.query", "IndexRangeBuilder")
private val LIST = ClassName("kotlin.collections", "List")
private const val ENT_CLIENT_NAME = "EntClient"

/**
 * Property names that, if used verbatim as the equality value parameter,
 * would shadow a stage member or force a backtick-escaped soft keyword.
 * Such columns get a `${propertyName}Value` parameter instead.
 */
private val RESERVED_PARAM_NAMES = setOf("driver", "client", "predicates", "value")

/**
 * The non-null value-parameter name for an equality helper. Echoes the
 * column-ref property name (matching the RFC's `authorId(authorId)` shape)
 * unless that would shadow a stage member or be a soft keyword, in which
 * case it is suffixed. Shared by the generator and the schema-printer path
 * rendering so they never diverge.
 */
internal fun indexHelperValueParamName(col: ResolvedIndexColumn): String =
    if (col.propertyName in RESERVED_PARAM_NAMES) "${col.propertyName}Value" else col.propertyName

/**
 * One indexed column resolved to everything index-helper codegen needs:
 * its physical [columnName], the generated column-ref [propertyName]
 * (which is also the helper method name), the non-null parameter
 * [paramType], whether it admits range blocks ([comparable]), whether it
 * is generally helper-eligible ([equalityEligible]), and [nullable] (used
 * to gate unique terminals).
 *
 * Names mirror the entity companion column refs exactly: `id` → `id`,
 * scalar fields → `toCamelCase(field.name)`, FK columns →
 * `EdgeFk.propertyName` (implicit `${camel(edge)}Id`, field-backed = the
 * captured declaration name). See [resolveIndexColumns].
 */
internal data class ResolvedIndexColumn(
    val columnName: String,
    val propertyName: String,
    val paramType: TypeName,
    val comparable: Boolean,
    val equalityEligible: Boolean,
    val nullable: Boolean,
)

/** An index resolved to its eligible columns (declared order) plus its unique flag. */
internal data class ResolvedIndex(
    val name: String,
    val columns: List<ResolvedIndexColumn>,
    val unique: Boolean,
)

/**
 * A node in the left-prefix tree of eligible indexes. The root carries an
 * empty [prefix] (the `indexes` namespace itself); every other node is a
 * bound equality prefix reached by chaining stage methods.
 *
 * - [children]: the next equality columns valid after this prefix.
 * - [rangeColumns]: the next comparable columns that also get a `{ }`
 *   range overload (a range overload terminates the chain).
 * - [isUniqueTerminal]: true when this prefix exactly equals the full
 *   column list of an eligible unique index whose columns are all
 *   non-nullable — only then are `orNull`/`orError`/`orThrow`/
 *   `visibleOrNull` exposed.
 */
internal class IndexPrefixNode(
    val prefix: List<ResolvedIndexColumn>,
    val children: List<IndexChild>,
    val rangeColumns: List<ResolvedIndexColumn>,
    val isUniqueTerminal: Boolean,
)

internal class IndexChild(val column: ResolvedIndexColumn, val node: IndexPrefixNode)

/** A column type is range-eligible when it maps to a comparable query column (gt/gte/lt/lte). */
private fun isComparable(type: FieldType): Boolean = when (type) {
    FieldType.STRING, FieldType.TEXT,
    FieldType.INT, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE,
    FieldType.TIME -> true
    else -> false
}

/**
 * A column is equality-helper eligible unless it is a btree-incompatible
 * type. Mirrors the RFC's exclusion list: typed JSON, pgvector, BYTES,
 * and any column with native `ColumnStorage.Native`.
 */
private fun isEqualityEligible(type: FieldType, storage: ColumnStorage?): Boolean {
    if (storage is ColumnStorage.Native) return false
    return when (type) {
        FieldType.JSON, FieldType.PGVECTOR, FieldType.BYTES -> false
        else -> true
    }
}

/**
 * Resolve every indexable column on [schema] to a [ResolvedIndexColumn],
 * keyed by physical column name. Built from `id` + [scalarFields] +
 * [computeEdgeFks] so the keys and generated property names line up 1:1
 * with the entity companion column refs (and so field-backed FK columns
 * use their declaration name, not `toCamelCase(column)`).
 */
internal fun resolveIndexColumns(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): Map<String, ResolvedIndexColumn> {
    val map = LinkedHashMap<String, ResolvedIndexColumn>()

    val idType = schema.id().type
    map["id"] = ResolvedIndexColumn(
        columnName = "id",
        propertyName = "id",
        paramType = idType.toTypeName(),
        comparable = isComparable(idType),
        equalityEligible = isEqualityEligible(idType, null),
        nullable = false,
    )

    for (field in scalarFields(schema)) {
        map[field.columnName] = ResolvedIndexColumn(
            columnName = field.columnName,
            propertyName = toCamelCase(field.name),
            paramType = field.resolvedTypeName(),
            comparable = isComparable(field.type),
            equalityEligible = isEqualityEligible(field.type, field.storage),
            nullable = field.nullable,
        )
    }

    for (fk in computeEdgeFks(schema, schemaNames)) {
        map[fk.columnName] = ResolvedIndexColumn(
            columnName = fk.columnName,
            propertyName = fk.propertyName,
            paramType = fk.idType.toTypeName(),
            comparable = isComparable(fk.idType),
            equalityEligible = isEqualityEligible(fk.idType, null),
            nullable = !fk.required,
        )
    }

    return map
}

/**
 * The eligible indexes that generate helpers, in a stable order:
 * explicit non-partial, non-native `index(...)` declarations first, then
 * synthesized single-column unique indexes from unique non-PK columns
 * (mirrors [SchemaInspector] / migration synthesis). An explicit index is
 * dropped whole if any column is not [isEqualityEligible]; duplicate
 * (columns, unique) shapes are de-duplicated.
 */
internal fun eligibleResolvedIndexes(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<ResolvedIndex> {
    val cols = resolveIndexColumns(schema, schemaNames)
    val result = mutableListOf<ResolvedIndex>()
    val seen = mutableSetOf<Pair<List<String>, Boolean>>()

    for (idx in schema.indexes()) {
        // Skip partial (raw-SQL where) and native / non-btree (pgvector
        // hnsw/ivfflat) indexes — equality/range helpers would be
        // misleading there.
        if (idx.where != null || idx.using != null || idx.opclasses != null || idx.with != null) continue
        val resolved = idx.fields.map { cols[it] }
        if (resolved.any { it == null }) continue
        val rc = resolved.filterNotNull()
        // Every column must be btree-helper compatible, else the whole
        // index is ineligible (can't seed an equality predicate for it).
        if (rc.any { !it.equalityEligible }) continue
        val key = rc.map { it.columnName } to idx.unique
        if (seen.add(key)) result.add(ResolvedIndex(idx.name, rc, idx.unique))
    }

    val table = schema.tableName
    for (col in columnMetadataFor(schema, schemaNames)) {
        if (!col.unique || col.primaryKey) continue
        val rc = cols[col.name] ?: continue
        if (!rc.equalityEligible) continue
        val key = listOf(rc.columnName) to true
        if (seen.add(key)) {
            result.add(ResolvedIndex("idx_${table}_${col.name}_unique", listOf(rc), true))
        }
    }

    return result
}

/**
 * Build the merged left-prefix tree of eligible indexes for [schema], or
 * `null` when no helpers are eligible (so callers can omit the `indexes`
 * namespace entirely). Throws a clear error when two distinct indexed
 * columns would resolve to the same generated helper name at the same
 * stage.
 */
internal fun indexHelperTree(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): IndexPrefixNode? {
    val indexes = eligibleResolvedIndexes(schema, schemaNames)
    if (indexes.isEmpty()) return null
    val root = buildIndexNode(emptyList(), indexes)
    checkStageNameCollisions(root)
    return root
}

/** `PascalCase` join of a prefix's property names, e.g. [authorId, createdAt] → "AuthorIdCreatedAt". */
internal fun indexStageSimpleName(prefix: List<ResolvedIndexColumn>): String =
    prefix.joinToString("") { it.propertyName.replaceFirstChar { c -> c.uppercase() } }

/** The nested class name of a range-block terminal, e.g. [authorId] + createdAt → "AuthorIdCreatedAtRange". */
internal fun indexRangeStageSimpleName(prefix: List<ResolvedIndexColumn>, col: ResolvedIndexColumn): String =
    indexStageSimpleName(prefix + col) + "Range"

/**
 * Fail fast when two distinct index stages (equality stages and/or
 * range-block terminals) resolve to the same generated nested-class name.
 *
 * Stage names are a separator-less PascalCase join of property names, so
 * distinct prefixes can collide — e.g. `(a_b, c)` and `(a, b_c)` both
 * yield `ABC`, and an equality stage for a column whose name ends in
 * `range` can collide with a range-block terminal. Left unchecked, the
 * emitter would silently bind both chains to whichever class is emitted
 * first, producing a wrong helper surface. The RFC requires a clear error
 * instead.
 */
private fun checkStageNameCollisions(root: IndexPrefixNode) {
    val seen = HashMap<String, String>()
    fun register(name: String, source: String) {
        val prev = seen.put(name, source)
        if (prev != null && prev != source) {
            error(
                "Index helper stage class name collision: '$name' is generated by both " +
                    "$prev and $source. These distinct index stages produce the same staged " +
                    "builder class name. Rename one of the indexed columns (or its generated " +
                    "column-ref property) so the stage names differ.",
            )
        }
    }
    fun visit(node: IndexPrefixNode) {
        if (node.prefix.isNotEmpty()) {
            register(
                indexStageSimpleName(node.prefix),
                "equality prefix [${node.prefix.joinToString(", ") { it.propertyName }}]",
            )
        }
        for (col in node.rangeColumns) {
            register(
                indexRangeStageSimpleName(node.prefix, col),
                "range block on '${col.propertyName}' after [${node.prefix.joinToString(", ") { it.propertyName }}]",
            )
        }
        for (child in node.children) visit(child.node)
    }
    visit(root)
}

private fun buildIndexNode(
    prefix: List<ResolvedIndexColumn>,
    indexes: List<ResolvedIndex>,
): IndexPrefixNode {
    val depth = prefix.size

    // Unique terminal: a unique index whose full column list is exactly
    // this prefix, with no nullable column in it.
    val isUnique = prefix.isNotEmpty() &&
        prefix.all { !it.nullable } &&
        indexes.any { it.unique && it.columns.size == depth }

    // Group the next valid column (at this depth) across every index that
    // shares this prefix.
    val nextOrder = LinkedHashMap<String, ResolvedIndexColumn>()
    val nextIndexes = LinkedHashMap<String, MutableList<ResolvedIndex>>()
    for (idx in indexes) {
        if (idx.columns.size <= depth) continue
        val col = idx.columns[depth]
        nextOrder[col.columnName] = col
        nextIndexes.getOrPut(col.columnName) { mutableListOf() }.add(idx)
    }

    // Two distinct columns must not collapse to the same helper method
    // name at one stage (ambiguous generated surface).
    for ((prop, group) in nextOrder.values.groupBy { it.propertyName }) {
        val distinct = group.map { it.columnName }.distinct()
        if (distinct.size > 1) {
            error(
                "Index helper name collision on '$prop': indexed columns $distinct would " +
                    "both generate the same stage method at the same index prefix. Rename one " +
                    "of the columns or its generated column-ref property.",
            )
        }
    }

    // A comparable column named `query` on a non-root stage would generate a
    // range-block overload `query(block: IndexRangeBuilder<…>.() -> Unit)` that
    // is an ambiguous single-lambda signature against the stage's own
    // `query(block: …Query.() -> Unit = {})` entrypoint — `stage.query { }`
    // would not resolve. Fail with a clear error instead of emitting it.
    if (prefix.isNotEmpty()) {
        val queryClash = nextOrder.values.firstOrNull { it.comparable && it.propertyName == "query" }
        if (queryClash != null) {
            error(
                "Index helper for comparable column 'query' after prefix " +
                    "[${prefix.joinToString(", ") { it.propertyName }}] collides with the generated " +
                    "`query { ... }` builder entrypoint: its range-block overload is an ambiguous " +
                    "single-lambda signature. Rename the indexed column or its generated column-ref property.",
            )
        }
    }

    val children = nextOrder.map { (colName, col) ->
        IndexChild(col, buildIndexNode(prefix + col, nextIndexes.getValue(colName)))
    }
    val rangeColumns = nextOrder.values.filter { it.comparable }.toList()

    return IndexPrefixNode(prefix, children, rangeColumns, isUnique)
}

/**
 * The chained helper paths a single eligible index generates, one per
 * left prefix, used by the schema printer. Example for
 * `(author_id, created_at)`:
 *
 * ```text
 * indexes.authorId(authorId).query()
 * indexes.authorId(authorId).createdAt(createdAt).query()
 * ```
 */
private fun helperPathsForIndex(index: ResolvedIndex): List<String> =
    (1..index.columns.size).map { k ->
        val chain = index.columns.take(k)
            .joinToString(".") { "${it.propertyName}(${indexHelperValueParamName(it)})" }
        "indexes.$chain.query()"
    }

/**
 * Index name → generated helper paths for every eligible index on
 * [schema]. Consumed by [SchemaInspector] so the explain/printer output
 * can advertise the generated access paths.
 */
internal fun indexHelperPathsByName(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): Map<String, List<String>> =
    eligibleResolvedIndexes(schema, schemaNames).associate { it.name to helperPathsForIndex(it) }

/**
 * Emits the `${schemaName}Indexes` namespace class: the staged,
 * left-prefix index-helper builder reached via `client.<repo>.indexes`.
 *
 * The root class is the empty-prefix node and exposes one method per
 * valid first indexed column. Every deeper stage and every range-block
 * terminal is a class nested inside `${schemaName}Indexes` (so two
 * entities can't collide on a stage symbol in the flat generated
 * package — the same nesting precedent as the link-table edge mutators).
 *
 * Every stage delegates to the generated `${schemaName}Query`: it seeds
 * the bound prefix predicates with `where(...)` before the caller's own
 * `query { }` block (or before a `first*` terminal), so LOAD privacy,
 * eager-load privacy, and read interceptors all run exactly as they do
 * for a hand-written query.
 *
 * Returns `null` when the schema has no eligible index helpers.
 */
internal class IndexHelperGenerator(
    private val packageName: String,
) {
    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec? {
        val root = indexHelperTree(schema, schemaNames) ?: return null

        val entityClass = ClassName(packageName, schemaName)
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val indexesClass = ClassName(packageName, "${schemaName}Indexes")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val emitter = Emitter(entityClass, queryClass, indexesClass, clientClass)

        val indexesType = TypeSpec.classBuilder(indexesClass)
            .addKdoc(
                "Staged index-helper namespace for `%L`, reached via " +
                    "`client.<repo>.indexes`. Each stage records the bound index " +
                    "prefix and delegates to `%LQuery`, preserving privacy and " +
                    "read interceptors.",
                schemaName, schemaName,
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("driver", DRIVER)
                    .addParameter(
                        ParameterSpec.builder("client", clientClass.copy(nullable = true)).build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE).initializer("driver").build(),
            )
            .addProperty(
                PropertySpec.builder("client", clientClass.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE).initializer("client").build(),
            )

        // Root (empty-prefix) first-stage methods live directly on the
        // namespace class; the root has no query()/terminals.
        for (m in emitter.stageMethods(root)) indexesType.addFunction(m)

        // Every deeper stage class + range terminal is nested flat under
        // the namespace class.
        for (child in root.children) emitter.emitStageClass(child.node, indexesType)
        for (rangeCol in root.rangeColumns) emitter.emitRangeTerminal(root.prefix, rangeCol, indexesType)

        return FileSpec.builder(packageName, "${schemaName}Indexes")
            .addType(indexesType.build())
            .build()
    }

    /**
     * Holds the shared class names and the set of already-emitted nested
     * type names (a cheap guard against two distinct prefixes producing
     * the same PascalCase stage name).
     */
    private inner class Emitter(
        val entityClass: ClassName,
        val queryClass: ClassName,
        val indexesClass: ClassName,
        val clientClass: ClassName,
    ) {
        private val emittedNames = mutableSetOf<String>()
        private val predicatesType = LIST.parameterizedBy(PREDICATE.parameterizedBy(entityClass))
        private val nullableEntity = entityClass.copy(nullable = true)

        private fun stageName(prefix: List<ResolvedIndexColumn>): String =
            indexStageSimpleName(prefix)

        private fun rangeStageName(prefix: List<ResolvedIndexColumn>, col: ResolvedIndexColumn): String =
            indexRangeStageSimpleName(prefix, col)

        private fun stageClass(prefix: List<ResolvedIndexColumn>): ClassName =
            indexesClass.nestedClass(stageName(prefix))

        private fun rangeStageClass(prefix: List<ResolvedIndexColumn>, col: ResolvedIndexColumn): ClassName =
            indexesClass.nestedClass(rangeStageName(prefix, col))

        /**
         * Statements that build `q`, a fresh query seeded with the bound
         * prefix predicates. Emitted as discrete statements (rather than a
         * single `.apply { … }` chain) so KotlinPoet line-wrapping can't
         * split a trailing lambda across a newline.
         */
        private fun seedStatements(builder: FunSpec.Builder) {
            builder.addStatement("val q = %T(driver, client)", queryClass)
            builder.addStatement("for (p in predicates) q.where(p)")
        }

        /** The equality + range stage methods for [node] (used on the root class and on every stage class). */
        fun stageMethods(node: IndexPrefixNode): List<FunSpec> {
            val funs = mutableListOf<FunSpec>()
            for (child in node.children) {
                funs.add(equalityMethod(node, child))
            }
            for (rangeCol in node.rangeColumns) {
                funs.add(rangeMethod(node, rangeCol))
            }
            return funs
        }

        private fun equalityMethod(node: IndexPrefixNode, child: IndexChild): FunSpec {
            val col = child.column
            val paramName = indexHelperValueParamName(col)
            val newPred = CodeBlock.of("%T.%N.eq(%N)", entityClass, col.propertyName, paramName)
            val listExpr =
                if (node.prefix.isEmpty()) CodeBlock.of("listOf(%L)", newPred)
                else CodeBlock.of("predicates + %L", newPred)
            val target = stageClass(child.node.prefix)
            return FunSpec.builder(col.propertyName)
                .addParameter(paramName, col.paramType)
                .returns(target)
                .addStatement("return %T(driver, client, %L)", target, listExpr)
                .build()
        }

        private fun rangeMethod(node: IndexPrefixNode, col: ResolvedIndexColumn): FunSpec {
            val rangeBuilderType = INDEX_RANGE_BUILDER.parameterizedBy(entityClass, col.paramType)
            val blockLambda = LambdaTypeName.get(receiver = rangeBuilderType, returnType = UNIT)
            val target = rangeStageClass(node.prefix, col)
            val listExpr =
                if (node.prefix.isEmpty()) CodeBlock.of("range")
                else CodeBlock.of("predicates + range")
            return FunSpec.builder(col.propertyName)
                .addParameter("block", blockLambda)
                .returns(target)
                .addStatement("val range = %T(%T.%N).apply(block).build()", INDEX_RANGE_BUILDER, entityClass, col.propertyName)
                .addStatement("return %T(driver, client, %L)", target, listExpr)
                .build()
        }

        private fun queryMethod(): FunSpec {
            val queryLambda = LambdaTypeName.get(receiver = queryClass, returnType = UNIT)
            return FunSpec.builder("query")
                .addParameter(
                    ParameterSpec.builder("block", queryLambda).defaultValue("{}").build(),
                )
                .returns(queryClass)
                .apply { seedStatements(this) }
                .addStatement("return q.apply(block)")
                .build()
        }

        private fun terminal(name: String, returns: TypeName, call: String): FunSpec =
            FunSpec.builder(name)
                .returns(returns)
                .apply { seedStatements(this) }
                .addStatement("return q.%L", call)
                .build()

        private fun uniqueTerminals(): List<FunSpec> = listOf(
            terminal("orNull", nullableEntity, "firstOrNull()"),
            terminal("visibleOrNull", nullableEntity, "firstVisibleOrNull()"),
            terminal("orError", ENT_RESULT.parameterizedBy(entityClass), "firstOrError()"),
            terminal("orThrow", entityClass, "firstOrThrow()"),
        )

        private fun stageConstructor(): FunSpec =
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addParameter("driver", DRIVER)
                .addParameter(ParameterSpec.builder("client", clientClass.copy(nullable = true)).build())
                .addParameter("predicates", predicatesType)
                .build()

        private fun stageProperties(): List<PropertySpec> = listOf(
            PropertySpec.builder("driver", DRIVER).addModifiers(KModifier.PRIVATE).initializer("driver").build(),
            PropertySpec.builder("client", clientClass.copy(nullable = true)).addModifiers(KModifier.PRIVATE).initializer("client").build(),
            PropertySpec.builder("predicates", predicatesType).addModifiers(KModifier.PRIVATE).initializer("predicates").build(),
        )

        /** Emit the nested stage class for [node] (depth ≥ 1), then recurse into its children + range terminals. */
        fun emitStageClass(node: IndexPrefixNode, container: TypeSpec.Builder) {
            val name = stageName(node.prefix)
            // checkStageNameCollisions ran in indexHelperTree, so a duplicate
            // here is an internal invariant break, not a user-facing collision.
            check(emittedNames.add(name)) { "internal: duplicate index stage class '$name'" }
            val stage = TypeSpec.classBuilder(name)
                .primaryConstructor(stageConstructor())
                .addProperties(stageProperties())
                .addFunction(queryMethod())
            for (m in stageMethods(node)) stage.addFunction(m)
            if (node.isUniqueTerminal) for (t in uniqueTerminals()) stage.addFunction(t)
            container.addType(stage.build())
            for (child in node.children) emitStageClass(child.node, container)
            for (rangeCol in node.rangeColumns) emitRangeTerminal(node.prefix, rangeCol, container)
        }

        /** Emit the nested range-terminal class for a `{ }` block: it only exposes `query()`/`query { }`. */
        fun emitRangeTerminal(
            prefix: List<ResolvedIndexColumn>,
            col: ResolvedIndexColumn,
            container: TypeSpec.Builder,
        ) {
            val name = rangeStageName(prefix, col)
            check(emittedNames.add(name)) { "internal: duplicate index range stage class '$name'" }
            val stage = TypeSpec.classBuilder(name)
                .primaryConstructor(stageConstructor())
                .addProperties(stageProperties())
                .addFunction(queryMethod())
            container.addType(stage.build())
        }
    }
}
