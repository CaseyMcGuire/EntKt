package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val EDGE_QUERY = ClassName("entkt.query", "EdgeQuery")
private val DRIVER = ClassName("entkt.runtime", "Driver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP = ClassName("entkt.query", "Op")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val PRIVACY_DENIED = ClassName("entkt.runtime", "PrivacyDeniedException")
private val QUERY_EXPLANATION = ClassName("entkt.runtime", "QueryExplanation")
private val ENT_ERROR = ClassName("entkt.runtime", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime", "EntOperation")
private val ENT_RESULT = ClassName("entkt.runtime", "EntResult")
private val MEMBER_GET_OR_THROW = com.squareup.kotlinpoet.MemberName("entkt.runtime", "getOrThrow")
private val MEMBER_CLASSIFY = com.squareup.kotlinpoet.MemberName("entkt.runtime", "classifyDriverError")

internal class QueryGenerator(
    private val packageName: String,
) {
    private val predicateClass = ClassName("entkt.query", "Predicate")
    private val orderFieldClass = ClassName("entkt.query", "OrderField")

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Query"
        val queryClass = ClassName(packageName, className)
        val entityClass = ClassName(packageName, schemaName)

        val traversalMethods = schema.edges()
            .mapNotNull { edge ->
                if (edge.kind is EdgeKind.ManyToMany) {
                    buildM2MTraversal(edge, schema, schemaNames)
                } else {
                    buildTraversal(edge, schema, schemaNames)
                }
            }

        // Eager loading: with{Edge}() methods and properties
        val eagerEdgeSpecs = schema.edges().mapNotNull { edge ->
            buildEagerEdgeSpec(edge, schema, schemaNames)
        }

        val hasEdges = eagerEdgeSpecs.isNotEmpty()

        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)

        val typeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addSuperinterface(EDGE_QUERY)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter(
                        ParameterSpec.builder("client", clientClass.copy(nullable = true))
                            .defaultValue("null")
                            .build()
                    )
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("client", clientClass.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("client")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "predicates",
                    List::class.asClassName().parameterizedBy(predicateClass),
                )
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "orderFields",
                    List::class.asClassName().parameterizedBy(orderFieldClass),
                )
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryLimit", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryOffset", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperties(eagerEdgeSpecs.map { it.property })
            .addFunction(buildWhere(queryClass))
            .addFunction(buildOrderBy(queryClass))
            .addFunction(buildLimit(queryClass))
            .addFunction(buildOffset(queryClass))
            .addFunction(buildCombinedPredicate())
            .addFunctions(eagerEdgeSpecs.map { it.withMethod })
            // Always emit `loadEdges` — the M2M eager-load codegen
            // emitted on a *source* query calls `subQuery.loadEdges(...)`
            // unconditionally on the target's query, so a target schema
            // with zero outgoing edges of its own (like a simple `Tag`)
            // still needs the no-op method to satisfy that call site.
            // The body's loop over `schema.edges()` produces zero
            // iterations for edge-less schemas, so the method just
            // returns its `results` parameter unchanged.
            .addFunction(buildLoadEdges(entityClass, schema, schemaNames))
            .addFunction(buildRequireClient(schemaName))
            .addFunction(buildAllOrThrow(schemaName, entityClass, hasEdges))
            .addFunction(buildAllOrError(schemaName, entityClass))
            .addFunction(buildVisibleAll(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleAllOrError(schemaName, entityClass))
            .addFunction(buildFirstOrNull(schemaName, entityClass, hasEdges))
            .addFunction(buildFirstOrThrow(schemaName, entityClass))
            .addFunction(buildFirstOrError(schemaName, entityClass))
            .addFunction(buildFirstVisibleOrNull(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleCount(schemaName, entityClass))
            .addFunction(buildRawCount(schemaName, entityClass))
            .addFunction(buildExists(schemaName, entityClass))
            .addFunctions(buildExplainMethods(entityClass, schema, schemaNames))
            .addFunctions(traversalMethods)
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    /**
     * `allOrThrow(): List<T>` — strict bulk read. Returns every
     * matching entity; if **any** matched row is denied by LOAD
     * privacy the operation fails with [PrivacyDeniedException].
     * Replaces the legacy `all()` name to make the throw-on-denial
     * contract explicit at the call site.
     */
    private fun buildAllOrThrow(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val builder = FunSpec.builder("allOrThrow")
            .returns(List::class.asClassName().parameterizedBy(entityClass))
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val rows = driver.query(%T.TABLE, predicates, orderFields, queryLimit, queryOffset)",
                entityClass,
            )
            .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
        builder.addCode(CodeBlock.builder()
            .beginControlFlow("if (c.%L.hasLoadPrivacy())", repoPropName)
            .addStatement("for (entity in results) c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
            .endControlFlow()
            .build()
        )
        if (hasEdges) {
            builder.addStatement("return loadEdges(results, privacy)")
        } else {
            builder.addStatement("return results")
        }
        return builder.build()
    }

    /**
     * `allOrError(): EntResult<List<T>>` — structured-result bulk
     * read. Wraps [allOrThrow]'s privacy / driver exceptions into the
     * matching [EntError] variant. The `catch (Exception)` arm routes
     * through [classifyDriverError] for the constraint/driver split.
     */
    private fun buildAllOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val resultType = ENT_RESULT.parameterizedBy(listType)
        return FunSpec.builder("allOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  %T.Ok(allOrThrow())\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                    .add(
                        "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
                        ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * `visibleAll(): List<T>` — filter-only bulk read. Returns the
     * subset of matching rows the current viewer can LOAD. Rows that
     * fail LOAD privacy are dropped from the result instead of
     * triggering an exception. Driver failures still propagate as
     * raw exceptions; use [visibleAllOrError] for the structured form.
     *
     * V1 has no overfetch cap wired here yet — every matching row is
     * pulled and filtered in-process. The cap (`EntClientConfig
     * .visibleOverfetchLimit`) will bound the storage scan in a
     * future sub-phase.
     */
    private fun buildVisibleAll(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val builder = FunSpec.builder("visibleAll")
            .returns(listType)
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val rows = driver.query(%T.TABLE, predicates, orderFields, queryLimit, queryOffset)",
                entityClass,
            )
            .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
        builder.addCode(
            CodeBlock.builder()
                .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
                .also {
                    if (hasEdges) {
                        it.addStatement("return loadEdges(results, privacy)")
                    } else {
                        it.addStatement("return results")
                    }
                }
                .endControlFlow()
                .addStatement(
                    "val visible = results.filter { e -> try { c.%L.evaluateLoadPrivacy(privacy, e); true } catch (_: %T) { false } }",
                    repoPropName, PRIVACY_DENIED,
                )
                .build()
        )
        if (hasEdges) {
            builder.addStatement("return loadEdges(visible, privacy)")
        } else {
            builder.addStatement("return visible")
        }
        return builder.build()
    }

    /**
     * `visibleAllOrError(): EntResult<List<T>>` — structured-result
     * filter-only bulk read. Wraps [visibleAll]; the only failure
     * surface is driver exceptions (privacy denial never escapes
     * `visibleAll` because it's filtered to a `false` decision).
     */
    private fun buildVisibleAllOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val resultType = ENT_RESULT.parameterizedBy(listType)
        return FunSpec.builder("visibleAllOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  %T.Ok(visibleAll())\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
                        ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * Terminal op: ask the driver for one row and stop, enforcing LOAD
     * privacy on the result.
     */
    private fun buildFirstOrNull(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val builder = FunSpec.builder("firstOrNull")
            .returns(entityClass.copy(nullable = true))
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val row = driver.query(%T.TABLE, predicates, orderFields, 1, queryOffset).firstOrNull()",
                entityClass,
            )
            .addStatement("val entity = row?.let { %T.fromRow(it) } ?: return null", entityClass)
            .addStatement("if (c.%L.hasLoadPrivacy()) c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName, repoPropName)
        if (hasEdges) {
            builder.addStatement("return loadEdges(listOf(entity), privacy).first()")
        } else {
            builder.addStatement("return entity")
        }
        return builder.build()
    }

    /**
     * `firstOrThrow(): T` — strict first-row read. Throws structured
     * EntException subclasses for every failure surface (NotFound on
     * empty match, PrivacyDenied on denial, DriverFailure on
     * uncategorized exceptions). Wraps [firstOrError].
     */
    private fun buildFirstOrThrow(schemaName: String, entityClass: ClassName): FunSpec {
        return FunSpec.builder("firstOrThrow")
            .returns(entityClass)
            .addStatement("return firstOrError().%M()", MEMBER_GET_OR_THROW)
            .build()
    }

    /**
     * `firstOrError(): EntResult<T>` — structured-result first-row
     * read. Empty match → Err(NotFound); privacy denial →
     * Err(PrivacyDenied); other uncaught Exception routed through
     * [classifyDriverError].
     *
     * NotFound carries `id = null` because a query-level "first row"
     * has no identifying id to attribute the miss to.
     */
    private fun buildFirstOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val resultType = ENT_RESULT.parameterizedBy(entityClass)
        return FunSpec.builder("firstOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add(
                        "  firstOrNull()?.let { %T.Ok(it) } ?: %T.Err(%T.NotFound(%S, %T.QUERY))\n",
                        ENT_RESULT, ENT_RESULT, ENT_ERROR, schemaName, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                    .add(
                        "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
                        ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * `firstVisibleOrNull(): T?` — scans matched rows in storage
     * order and returns the first row LOAD privacy allows. Returns
     * `null` if no matched row is visible. Driver failures still
     * propagate as raw exceptions.
     *
     * V1 has no overfetch cap wired here yet — scanning is bounded
     * only by the query's `queryLimit` (if set) or the natural end
     * of the storage match. The cap (`EntClientConfig
     * .visibleOverfetchLimit`) will bound the scan in a future
     * sub-phase.
     */
    private fun buildFirstVisibleOrNull(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val builder = FunSpec.builder("firstVisibleOrNull")
            .returns(entityClass.copy(nullable = true))
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val rows = driver.query(%T.TABLE, predicates, orderFields, queryLimit, queryOffset)",
                entityClass,
            )
        builder.addCode(
            CodeBlock.builder()
                .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
                .addStatement("val row = rows.firstOrNull() ?: return null")
                .addStatement("val entity = %T.fromRow(row)", entityClass)
                .also {
                    if (hasEdges) it.addStatement("return loadEdges(listOf(entity), privacy).first()")
                    else it.addStatement("return entity")
                }
                .endControlFlow()
                .beginControlFlow("for (row in rows)")
                .addStatement("val entity = %T.fromRow(row)", entityClass)
                .beginControlFlow("try")
                .addStatement("c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
                .also {
                    if (hasEdges) it.addStatement("return loadEdges(listOf(entity), privacy).first()")
                    else it.addStatement("return entity")
                }
                .nextControlFlow("catch (_: %T)", PRIVACY_DENIED)
                .endControlFlow()
                .endControlFlow()
                .addStatement("return null")
                .build()
        )
        return builder.build()
    }

    /**
     * Terminal op: count entities visible to the current viewer.
     * Materializes all matching rows, evaluates LOAD privacy on each,
     * and returns the count of allowed entities.
     */
    private fun buildVisibleCount(schemaName: String, entityClass: ClassName): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val builder = FunSpec.builder("visibleCount")
            .addKdoc("Count entities visible to the current viewer. Materializes matching rows and\n" +
                "evaluates LOAD privacy on each, returning only the count of allowed entities.\n" +
                "Use [rawCount] for a fast aggregate that skips privacy.")
            .returns(LONG)
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val rows = driver.query(%T.TABLE, predicates, orderFields, queryLimit, queryOffset)",
                entityClass,
            )
            .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
        builder.addCode(CodeBlock.builder()
            .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
            .addStatement("return results.size.toLong()")
            .endControlFlow()
            .build()
        )
        builder.addCode(CodeBlock.builder()
            .addStatement("var count = 0L")
            .beginControlFlow("for (entity in results)")
            .beginControlFlow("try")
            .addStatement("c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
            .addStatement("count++")
            .nextControlFlow("catch (_: %T)", PRIVACY_DENIED)
            .endControlFlow()
            .endControlFlow()
            .addStatement("return count")
            .build()
        )
        return builder.build()
    }

    /**
     * Terminal op: count matching rows without materializing them.
     * This is a raw aggregate -- LOAD privacy is not evaluated.
     */
    private fun buildRawCount(schemaName: String, entityClass: ClassName): FunSpec {
        return FunSpec.builder("rawCount")
            .addKdoc("Count matching rows. This is a raw aggregate that does not evaluate LOAD privacy.\n" +
                "Use [visibleCount] for a privacy-aware count.")
            .returns(LONG)
            .addStatement("requireClient()")
            .addStatement("return driver.count(%T.TABLE, predicates)", entityClass)
            .build()
    }

    /**
     * Terminal op: check whether at least one matching row exists.
     * Fetches one row and evaluates LOAD privacy on it. Throws
     * [PrivacyDeniedException] if the row is denied.
     */
    private fun buildExists(schemaName: String, entityClass: ClassName): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        return FunSpec.builder("exists")
            .returns(BOOLEAN)
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val row = driver.query(%T.TABLE, predicates, orderFields, 1, queryOffset).firstOrNull() ?: return false",
                entityClass,
            )
            .addStatement("val entity = %T.fromRow(row)", entityClass)
            .addStatement("if (c.%L.hasLoadPrivacy()) c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName, repoPropName)
            .addStatement("return true")
            .build()
    }

    /**
     * Generate per-terminal explain methods plus a private helper
     * that builds the [QueryPlan] tree. Each public method mirrors
     * the execution shape of its corresponding terminal:
     *
     * - `explain()` → models `all()`: configured limit/offset + eager edges
     * - `explainFirst()` → models `firstOrNull()`: limit 1 + eager edges
     * - `explainExists()` → models `exists()`: limit 1, no eager edges
     * - `explainVisibleCount()` → models `visibleCount()`: configured limit/offset, no eager edges
     * - `explainRawCount()` → models `rawCount()`: COUNT query, no eager edges
     */
    private fun buildExplainMethods(
        entityClass: ClassName,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): List<FunSpec> {
        val queryPlan = ClassName("entkt.runtime", "QueryPlan")
        val resolvableEdges = resolveExplainableEdges(schema, schemaNames)
        val hasEager = resolvableEdges.isNotEmpty()

        val methods = mutableListOf<FunSpec>()

        // explain() → models all()
        methods += FunSpec.builder("explain")
            .addKdoc("Return a [QueryPlan] describing the query shapes [all] would execute.\n" +
                "Eager edge subplans show structure, not multiplicity — nested\n" +
                "eager loads may execute once per parent group at runtime.")
            .returns(queryPlan)
            .addStatement("return buildQueryPlan(queryLimit, queryOffset, true)")
            .build()

        // explainFirst() → models firstOrNull()
        methods += FunSpec.builder("explainFirst")
            .addKdoc("Return a [QueryPlan] describing the query shapes [firstOrNull] would execute.")
            .returns(queryPlan)
            .addStatement("return buildQueryPlan(1, queryOffset, true)")
            .build()

        // explainExists() → models exists()
        methods += FunSpec.builder("explainExists")
            .addKdoc("Return a [QueryPlan] describing the query shape [exists] would execute.")
            .returns(queryPlan)
            .addStatement("return buildQueryPlan(1, queryOffset, false)")
            .build()

        // explainVisibleCount() → models visibleCount()
        methods += FunSpec.builder("explainVisibleCount")
            .addKdoc("Return a [QueryPlan] describing the query shape [visibleCount] would execute.")
            .returns(queryPlan)
            .addStatement("return buildQueryPlan(queryLimit, queryOffset, false)")
            .build()

        // explainRawCount() → models rawCount()
        methods += FunSpec.builder("explainRawCount")
            .addKdoc("Return a [QueryPlan] describing the query [rawCount] would execute.")
            .returns(queryPlan)
            .addStatement("return %T(driver.explainCount(%T.TABLE, predicates))", queryPlan, entityClass)
            .build()

        // Private buildQueryPlan helper
        val helper = FunSpec.builder("buildQueryPlan")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("limit", INT.copy(nullable = true))
            .addParameter("offset", INT.copy(nullable = true))
            .addParameter("includeEager", BOOLEAN)
            .returns(queryPlan)
            .addStatement(
                "val root = driver.explainQuery(%T.TABLE, predicates, orderFields, limit, offset)",
                entityClass,
            )

        if (!hasEager) {
            helper.addStatement("return %T(root)", queryPlan)
        } else {
            helper.addStatement("if (!includeEager) return %T(root)", queryPlan)
            helper.addStatement("val edges = mutableMapOf<String, %T>()", queryPlan)
            for (info in resolvableEdges) {
                helper.addCode(buildEagerExplainBlock(info, queryPlan))
            }
            helper.addStatement("return %T(root, eagerQueries = edges)", queryPlan)
        }
        methods += helper.build()

        return methods
    }

    private data class ExplainableEdge(
        val edge: Edge,
        val targetClass: ClassName,
        val eagerPropName: String,
        val join: EdgeJoin,
    )

    private fun resolveExplainableEdges(
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): List<ExplainableEdge> {
        return schema.edges().mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val targetClass = ClassName(packageName, targetName)
            val eagerPropName = "eager${toPascalCase(edge.name)}"
            when (edge.kind) {
                is EdgeKind.ManyToMany -> {
                    val join = resolveM2MEdgeJoin(edge, schema, schemaNames) ?: return@mapNotNull null
                    ExplainableEdge(edge, targetClass, eagerPropName, join)
                }
                else -> {
                    val join = resolveEdgeJoin(edge, schema) ?: return@mapNotNull null
                    ExplainableEdge(edge, targetClass, eagerPropName, join)
                }
            }
        }
    }

    /**
     * Emit the explain block for a single eager edge. Mirrors the
     * runtime behavior in [buildLoadEdges]:
     * - Adds the IN predicate on the correct column (with a
     *   placeholder value so the driver renders the column name)
     * - Uses null limit/offset (runtime fetches all, paginates in memory)
     * - M2M edges include the junction table query with its own IN predicate
     * - Nested eager loads are pulled from the subquery's own explain()
     */
    private fun buildEagerExplainBlock(info: ExplainableEdge, queryPlan: ClassName): CodeBlock {
        // Use a non-empty placeholder so the driver renders the actual
        // column name in the SQL (e.g. "author_id" IN (?)) instead of
        // collapsing an empty IN to FALSE which hides the join shape.
        val body = CodeBlock.builder()
        body.beginControlFlow("%L?.let { subQuery ->", info.eagerPropName)
        body.addStatement("val nested = subQuery.explain()")

        if (info.edge.kind is EdgeKind.ManyToMany) {
            // M2M: junction table query with IN on source FK column,
            // then target table query with IN on id
            body.addStatement(
                "val junctionExplain = driver.explainQuery(%S, listOf(%T.Leaf(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER)), emptyList(), null, null)",
                info.join.junctionTable, PREDICATE, info.join.junctionSourceColumn, OP, QUERY_EXPLANATION,
            )
            body.addStatement(
                "edges[%S] = %T(driver.explainQuery(%T.TABLE, subQuery.predicates + %T.Leaf(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER), subQuery.orderFields, null, null), junctionExplain, nested.eagerQueries)",
                info.edge.name, queryPlan, info.targetClass, PREDICATE, "id", OP, QUERY_EXPLANATION,
            )
        } else {
            // Direct edge: single query with IN on the join column
            // hasMany/hasOne: IN on targetColumn (FK on target side)
            // belongsTo: IN on targetColumn ("id" on target side)
            body.addStatement(
                "edges[%S] = %T(driver.explainQuery(%T.TABLE, subQuery.predicates + %T.Leaf(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER), subQuery.orderFields, null, null), eagerQueries = nested.eagerQueries)",
                info.edge.name, queryPlan, info.targetClass, PREDICATE, info.join.targetColumn, OP, QUERY_EXPLANATION,
            )
        }

        body.endControlFlow()
        return body.build()
    }

    private fun buildRequireClient(schemaName: String): FunSpec {
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        return FunSpec.builder("requireClient")
            .addModifiers(KModifier.PRIVATE)
            .returns(clientClass)
            .addStatement(
                "return client ?: error(%S)",
                "$schemaName query requires a client for privacy enforcement",
            )
            .build()
    }

    private fun buildWhere(queryClass: ClassName): FunSpec {
        return FunSpec.builder("where")
            .addParameter("predicate", predicateClass)
            .returns(queryClass)
            .addStatement("this.predicates = this.predicates + predicate")
            .addStatement("return this")
            .build()
    }

    private fun buildOrderBy(queryClass: ClassName): FunSpec {
        return FunSpec.builder("orderBy")
            .addParameter("field", orderFieldClass)
            .returns(queryClass)
            .addStatement("this.orderFields = this.orderFields + field")
            .addStatement("return this")
            .build()
    }

    private fun buildLimit(queryClass: ClassName): FunSpec {
        return FunSpec.builder("limit")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("this.queryLimit = n")
            .addStatement("return this")
            .build()
    }

    private fun buildOffset(queryClass: ClassName): FunSpec {
        return FunSpec.builder("offset")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("this.queryOffset = n")
            .addStatement("return this")
            .build()
    }

    // ------------------------------------------------------------------
    // Eager loading
    // ------------------------------------------------------------------

    /**
     * Holds the generated property and `with{Edge}()` method for one
     * eagerly-loadable edge.
     */
    private data class EagerEdgeSpec(
        val edgeName: String,
        val property: PropertySpec,
        val withMethod: FunSpec,
    )

    /**
     * Build the nullable property and `with{Edge}()` method for a single
     * edge. Returns null if the target can't be resolved.
     */
    private fun buildEagerEdgeSpec(
        edge: Edge,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): EagerEdgeSpec? {
        val targetName = schemaNames[edge.target] ?: return null
        // For non-M2M edges, verify we can resolve the join
        if (edge.kind is EdgeKind.ManyToMany) {
            resolveM2MEdgeJoin(edge, schema, schemaNames) ?: return null
        } else {
            resolveEdgeJoin(edge, schema) ?: return null
        }
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val queryClass = ClassName(packageName, "${schema.let { schemaNames[it] }}Query")
        val eagerPropName = "eager${toPascalCase(edge.name)}"
        val withMethodName = "with${toPascalCase(edge.name)}"

        val property = PropertySpec.builder(
            eagerPropName,
            targetQueryClass.copy(nullable = true),
        )
            .addModifiers(KModifier.PRIVATE)
            .mutable(true)
            .initializer("null")
            .build()

        val blockLambda = LambdaTypeName.get(
            receiver = targetQueryClass,
            returnType = UNIT,
        )
        val withMethod = FunSpec.builder(withMethodName)
            .addParameter(
                ParameterSpec.builder("block", blockLambda)
                    .defaultValue("{}")
                    .build()
            )
            .returns(queryClass)
            .addStatement("%L = %T(driver, client).apply(block)", eagerPropName, targetQueryClass)
            .addStatement("return this")
            .build()

        return EagerEdgeSpec(edge.name, property, withMethod)
    }

    /**
     * Build the `loadEdges` method that batch-loads all eager edges.
     * Generated per-query, with edge-type-specific blocks for each
     * declared edge. Called by `all()` and `firstOrNull()`.
     */
    private fun buildLoadEdges(
        entityClass: ClassName,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("if (results.isEmpty()) return results")
        body.addStatement("var entities = results")

        for (edge in schema.edges()) {
            val targetName = schemaNames[edge.target] ?: continue
            val targetClass = ClassName(packageName, targetName)
            val eagerPropName = "eager${toPascalCase(edge.name)}"
            val edgePropName = toCamelCase(edge.name)

            when (edge.kind) {
                is EdgeKind.ManyToMany -> {
                    val join = resolveM2MEdgeJoin(edge, schema, schemaNames) ?: continue
                    emitM2MEagerBlock(body, eagerPropName, edgePropName, join, targetClass, targetName)
                }
                is EdgeKind.BelongsTo -> {
                    val join = resolveEdgeJoin(edge, schema) ?: continue
                    emitToOneEagerBlock(body, eagerPropName, edgePropName, join, targetClass, targetName, schema, schemaNames)
                }
                is EdgeKind.HasOne -> {
                    val join = resolveEdgeJoin(edge, schema) ?: continue
                    emitHasOneEagerBlock(body, eagerPropName, edgePropName, join, targetClass, targetName)
                }
                is EdgeKind.HasMany -> {
                    val join = resolveEdgeJoin(edge, schema) ?: continue
                    emitToManyEagerBlock(body, eagerPropName, edgePropName, join, targetClass, targetName)
                }
            }
        }

        body.addStatement("return entities")

        return FunSpec.builder("loadEdges")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("results", List::class.asClassName().parameterizedBy(entityClass))
            .addParameter(
                ParameterSpec.builder("eagerPrivacyContext", PRIVACY_CONTEXT.copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .returns(List::class.asClassName().parameterizedBy(entityClass))
            .addCode(body.build())
            .build()
    }

    /**
     * Emit the eager loading block for a to-many direct edge.
     * The FK lives on the target side: source.id → target.fk_column.
     */
    private fun emitToManyEagerBlock(
        body: CodeBlock.Builder,
        eagerPropName: String,
        edgePropName: String,
        join: EdgeJoin,
        targetClass: ClassName,
        targetName: String,
    ) {
        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val sourceIds = entities.map { it.id }")
        // Fetch all matching rows — limit/offset are applied per group below.
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subQuery.predicates + %T.Leaf(%S, %T.IN, sourceIds), subQuery.orderFields, null, null)",
            targetClass, PREDICATE, join.targetColumn, OP,
        )
        body.addStatement(
            "val grouped = targetRows.groupBy { it[%S] }",
            join.targetColumn,
        )
        body.addStatement("val perGroupOffset = subQuery.queryOffset ?: 0")
        body.addStatement("val perGroupLimit = subQuery.queryLimit ?: Int.MAX_VALUE")
        body.addStatement(
            "var loadedGroups = grouped.mapValues { (_, rows) -> rows.drop(perGroupOffset).take(perGroupLimit).map { %T.fromRow(it) } }",
            targetClass,
        )
        emitEagerPrivacyCheck(body, targetName, "loadedGroups", grouped = true)
        body.addStatement(
            "loadedGroups = loadedGroups.mapValues { (_, list) -> subQuery.loadEdges(list, eagerPrivacyContext) }",
        )
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = loadedGroups[entity.id] ?: emptyList())) }",
            edgePropName,
        )
        body.endControlFlow()
    }

    /**
     * Emit the eager loading block for a hasOne edge.
     * The FK lives on the target side (like hasMany), but the Edges
     * property is a single nullable entity (like belongsTo).
     */
    private fun emitHasOneEagerBlock(
        body: CodeBlock.Builder,
        eagerPropName: String,
        edgePropName: String,
        join: EdgeJoin,
        targetClass: ClassName,
        targetName: String,
    ) {
        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val sourceIds = entities.map { it.id }")
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subQuery.predicates + %T.Leaf(%S, %T.IN, sourceIds), subQuery.orderFields, null, null)",
            targetClass, PREDICATE, join.targetColumn, OP,
        )
        body.addStatement(
            "val grouped = targetRows.groupBy { it[%S] }",
            join.targetColumn,
        )
        body.addStatement(
            "var loadedGroups = grouped.mapValues { (_, rows) -> rows.map { %T.fromRow(it) } }",
            targetClass,
        )
        emitEagerPrivacyCheck(body, targetName, "loadedGroups", grouped = true)
        body.addStatement(
            "loadedGroups = loadedGroups.mapValues { (_, list) -> subQuery.loadEdges(list, eagerPrivacyContext) }",
        )
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = loadedGroups[entity.id]?.firstOrNull())) }",
            edgePropName,
        )
        body.endControlFlow()
    }

    /**
     * Emit the eager loading block for a to-one direct edge.
     * The FK lives on the source side: source.fk_column → target.id.
     */
    private fun emitToOneEagerBlock(
        body: CodeBlock.Builder,
        eagerPropName: String,
        edgePropName: String,
        join: EdgeJoin,
        targetClass: ClassName,
        targetName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ) {
        // Find the FK property name on the source entity
        val edgeFks = computeEdgeFks(schema, schemaNames)
        val fkPropName = edgeFks.find { it.columnName == join.sourceColumn }?.propertyName
            ?: toCamelCase(join.sourceColumn)

        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val fkValues = entities.mapNotNull { it.%L }.distinct()", fkPropName)
        body.beginControlFlow("if (fkValues.isNotEmpty())")
        // Fetch all matching targets — limit/offset is meaningless for to-one.
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subQuery.predicates + %T.Leaf(%S, %T.IN, fkValues), subQuery.orderFields, null, null)",
            targetClass, PREDICATE, join.targetColumn, OP,
        )
        body.addStatement(
            "var loaded = targetRows.map { %T.fromRow(it) }",
            targetClass,
        )
        emitEagerPrivacyCheck(body, targetName, "loaded", grouped = false)
        body.addStatement("loaded = subQuery.loadEdges(loaded, eagerPrivacyContext)")
        body.addStatement("val targetMap = loaded.associateBy { it.id }")
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = entity.%L?.let { targetMap[it] })) }",
            edgePropName, fkPropName,
        )
        body.nextControlFlow("else")
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = null)) }",
            edgePropName,
        )
        body.endControlFlow()
        body.endControlFlow()
    }

    /**
     * Emit the eager loading block for a many-to-many edge via junction table.
     */
    private fun emitM2MEagerBlock(
        body: CodeBlock.Builder,
        eagerPropName: String,
        edgePropName: String,
        join: EdgeJoin,
        targetClass: ClassName,
        targetName: String,
    ) {
        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val sourceIds = entities.map { it.id }")
        // Query junction table
        body.addStatement(
            "val junctionRows = driver.query(%S, listOf(%T.Leaf(%S, %T.IN, sourceIds)), emptyList(), null, null)",
            join.junctionTable, PREDICATE, join.junctionSourceColumn, OP,
        )
        body.beginControlFlow("if (junctionRows.isNotEmpty())")
        body.addStatement(
            "val targetIds = junctionRows.map { it[%S] }.distinct()",
            join.junctionTargetColumn,
        )
        // Fetch all matching targets — limit/offset are applied per group below.
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subQuery.predicates + %T.Leaf(%S, %T.IN, targetIds), subQuery.orderFields, null, null)",
            targetClass, PREDICATE, "id", OP,
        )
        // Build a target → sources membership lookup from the junction
        // rows. Then iterate `targetRows` in its (already
        // `subQuery.orderFields`-ordered) order, appending each target
        // to the per-source groups it belongs to. Iterating junction
        // rows directly here would drop the ordering — junction rows
        // come back in driver-default order, not target order — and the
        // subsequent `drop(offset).take(limit)` per group would slice
        // the wrong subset for `withTags { orderBy(...); limit(...) }`.
        //
        // The membership lookup uses a `MutableSet` per target id, not
        // a `MutableList`, so a junction with duplicate
        // `(source_id, target_id)` pairs (legal for `throughEntity`
        // junctions with no unique pair index — the row carries
        // distinct payload) collapses to one membership entry. Without
        // the dedup, `withTags()` would return the same target multiple
        // times in one source's group, while the EXISTS-based
        // `queryTags()` traversal correctly returns each target once,
        // and per-group `drop`/`take` would slice from a duplicated
        // list. `mutableSetOf()` returns a `LinkedHashSet` so insertion
        // order is preserved (not that the source iteration order
        // matters for the per-group ordering — that's driven by the
        // target-row iteration).
        body.addStatement(
            "val sourcesByTargetId = mutableMapOf<Any?, MutableSet<Any?>>()",
        )
        body.beginControlFlow("for (jr in junctionRows)")
        body.addStatement(
            "sourcesByTargetId.getOrPut(jr[%S]) { mutableSetOf() }.add(jr[%S])",
            join.junctionTargetColumn,
            join.junctionSourceColumn,
        )
        body.endControlFlow()
        body.addStatement("val grouped = mutableMapOf<Any?, MutableList<%T>>()", targetClass)
        body.beginControlFlow("for (row in targetRows)")
        body.addStatement("val target = %T.fromRow(row)", targetClass)
        body.addStatement("val sources = sourcesByTargetId[target.id] ?: continue")
        body.beginControlFlow("for (src in sources)")
        body.addStatement(
            "grouped.getOrPut(src) { mutableListOf() }.add(target)",
        )
        body.endControlFlow()
        body.endControlFlow()
        body.addStatement("val perGroupOffset = subQuery.queryOffset ?: 0")
        body.addStatement("val perGroupLimit = subQuery.queryLimit ?: Int.MAX_VALUE")
        // Paginate, then privacy, then loadEdges — denied targets never trigger nested reads.
        body.addStatement(
            "var loadedGroups = grouped.mapValues { (_, list) -> list.drop(perGroupOffset).take(perGroupLimit) }",
        )
        emitEagerPrivacyCheck(body, targetName, "loadedGroups", grouped = true)
        body.addStatement(
            "loadedGroups = loadedGroups.mapValues { (_, list) -> subQuery.loadEdges(list, eagerPrivacyContext) }",
        )
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = loadedGroups[entity.id] ?: emptyList())) }",
            edgePropName,
        )
        body.nextControlFlow("else")
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = emptyList())) }",
            edgePropName,
        )
        body.endControlFlow()
        body.endControlFlow()
    }

    /**
     * Emit a privacy check block that applies LOAD privacy to eagerly
     * loaded target entities. [loadedVar] is the name of the mutable
     * local holding the loaded entities (or grouped map). When [grouped]
     * is true, the variable is a `Map<Any?, List<T>>` and each group's
     * list is checked individually. Throws [PrivacyDeniedException] on
     * any denied entity (strict read model).
     */
    private fun emitEagerPrivacyCheck(
        body: CodeBlock.Builder,
        targetName: String,
        loadedVar: String,
        grouped: Boolean,
    ) {
        val targetRepoProp = pluralize(targetName.replaceFirstChar { it.lowercase() })
        body.addStatement("val eagerClient = client")
        body.beginControlFlow("if (eagerClient != null && eagerPrivacyContext != null && eagerClient.%L.hasLoadPrivacy())", targetRepoProp)
        if (grouped) {
            body.beginControlFlow("%L.values.forEach { list ->", loadedVar)
            body.addStatement("for (entity in list) eagerClient.%L.evaluateLoadPrivacy(eagerPrivacyContext, entity)", targetRepoProp)
            body.endControlFlow()
        } else {
            body.addStatement("for (entity in %L) eagerClient.%L.evaluateLoadPrivacy(eagerPrivacyContext, entity)", loadedVar, targetRepoProp)
        }
        body.endControlFlow()
    }

    /**
     * Implements the [EdgeQuery] contract: returns the AND of every
     * accumulated predicate, or null if the query has no wheres. This
     * is what `EdgeRef.has { }` and the generated traversal methods
     * call to fold a query's filters into a single Predicate.
     */
    private fun buildCombinedPredicate(): FunSpec {
        return FunSpec.builder("combinedPredicate")
            .addModifiers(KModifier.OVERRIDE)
            .returns(predicateClass.copy(nullable = true))
            .addStatement(
                "return predicates.reduceOrNull { acc, p -> %T.And(acc, p) }",
                predicateClass,
            )
            .build()
    }

    /**
     * Generate a `queryX(): TargetQuery` traversal for a many-to-many
     * [edge]. Lowered to a `Predicate.HasM2MEdgeFrom` against the
     * candidate target row, naming the *source* schema's table and the
     * forward edge — the runtime walks the junction backwards using the
     * source schema's own edge metadata, with no dependency on a
     * synthesized reverse edge on the target.
     */
    private fun buildM2MTraversal(
        edge: Edge,
        source: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec? {
        val targetName = schemaNames[edge.target] ?: return null
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"
        val sourceTable = source.tableName

        return FunSpec.builder(methodName)
            .returns(targetQueryClass)
            .addStatement("val parent = combinedPredicate()")
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            .addStatement(
                "target.where(%T.HasM2MEdgeFrom(%S, %S, parent))",
                predicateClass,
                sourceTable,
                edge.name,
            )
            .addStatement("return target")
            .build()
    }

    /**
     * Generate a `queryX(): TargetQuery` method for [edge]. This is the
     * traversal entry point — given a query on the source schema, walk
     * across [edge] and return a query on the target.
     *
     * Lowering: the parent's combined predicate becomes a HasEdgeWith
     * predicate on the target query, naming the *inverse* edge (i.e.
     * the edge on the target that points back at the source). When the
     * parent has no wheres we still emit HasEdge so optional inverse
     * edges still filter out unrelated rows.
     *
     * Returns null when the inverse edge can't be resolved — codegen
     * just skips emitting a traversal method in that case.
     */
    private fun buildTraversal(
        edge: Edge,
        source: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec? {
        val targetName = schemaNames[edge.target] ?: return null
        val inverse = findInverseEdge(edge, source) ?: return null
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"

        return FunSpec.builder(methodName)
            .returns(targetQueryClass)
            .addStatement("val parent = combinedPredicate()")
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            .beginControlFlow("if (parent != null)")
            .addStatement(
                "target.where(%T.HasEdgeWith(%S, parent))",
                predicateClass,
                inverse.name,
            )
            .nextControlFlow("else")
            .addStatement(
                "target.where(%T.HasEdge(%S))",
                predicateClass,
                inverse.name,
            )
            .endControlFlow()
            .addStatement("return target")
            .build()
    }
}

internal fun toPascalCase(snakeCase: String): String =
    toCamelCase(snakeCase).replaceFirstChar { it.uppercase() }