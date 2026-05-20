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
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime", "EntQueryRejectedException")
private val ABORT_QUERY_REJECTED = ClassName("entkt.runtime", "AbortQueryRejected")
private val FROZEN_QUERY_SPEC = ClassName("entkt.runtime", "FrozenQuerySpec")
private val QUERY_SPEC_BUILDER = ClassName("entkt.runtime", "QuerySpecBuilder")
private val QUERY_CONTEXT = ClassName("entkt.runtime", "QueryContext")
private val READ_OPERATION = ClassName("entkt.runtime", "ReadOperation")
private val INTERCEPTOR_ENGINE = ClassName("entkt.runtime", "InterceptorEngine")
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
            // Traversal context, populated by generated queryX()
            // methods when this query is the *target* of a
            // traversal step. Read by runReadInterceptors to set
            // the QueryContext's sourceEntity / edgeName / path
            // and to inject the HasEdgeWith / HasM2MEdgeFrom /
            // HasEdge structural predicate that ties this target
            // query back to its source. Defaults are the "root
            // read" shape (no source, empty path, no structural
            // predicate).
            .addProperty(
                PropertySpec.builder(
                    "traversalSourceEntity",
                    ClassName("kotlin.reflect", "KClass")
                        .parameterizedBy(com.squareup.kotlinpoet.STAR)
                        .copy(nullable = true),
                )
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("traversalEdgeName", String::class.asClassName().copy(nullable = true))
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "traversalPath",
                    List::class.asClassName().parameterizedBy(ClassName("entkt.runtime", "EdgeStep")),
                )
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("traversalStructural", predicateClass.copy(nullable = true))
                    .addModifiers(KModifier.INTERNAL)
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
            .addFunction(buildRunReadInterceptors(schemaName, entityClass))
            .addFunction(buildRunEdgePredicateInterceptors(schemaName, entityClass, schema, schemaNames))
            .addFunction(buildAllOrThrow(schemaName, entityClass, hasEdges))
            .addFunction(buildAllOrError(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleAll(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleAllOrError(schemaName, entityClass, hasEdges))
            .addFunction(buildFirstOrNull(schemaName, entityClass, hasEdges))
            .addFunction(buildFirstOrThrow(schemaName, entityClass))
            .addFunction(buildFirstOrError(schemaName, entityClass))
            .addFunction(buildFirstVisibleOrNull(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleCount(schemaName, entityClass))
            .addFunction(buildVisibleCountOrError(schemaName, entityClass))
            .addFunction(buildRawCount(schemaName, entityClass))
            .addFunction(buildRawCountOrError(schemaName, entityClass))
            .addFunction(buildRawExists(entityClass))
            .addFunction(buildRawExistsOrError(schemaName, entityClass))
            .addFunction(buildVisibleExists(schemaName, entityClass))
            .addFunction(buildVisibleExistsOrError(schemaName, entityClass))
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
     * privacy the operation fails with [EntPrivacyDeniedException]
     * (structured EntException family). Driver failures throw
     * [EntDriverException] / [EntConstraintViolationException] per
     * the classifier.
     *
     * Implemented as a `allOrError().getOrThrow()` wrapper per the
     * RFC's "throwing APIs should be implemented as wrappers over
     * xOrError()" guideline — keeps the privacy / driver mapping in
     * one place and guarantees the structured-exception contract
     * across the *OrThrow family.
     */
    private fun buildAllOrThrow(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        return FunSpec.builder("allOrThrow")
            .returns(List::class.asClassName().parameterizedBy(entityClass))
            .addStatement(
                "return allOrError().%M()",
                MEMBER_GET_OR_THROW,
            )
            .build()
    }

    /**
     * `allOrError(): EntResult<List<T>>` — structured-result bulk
     * read. The canonical entry point for the throw/result pair:
     * maps every failure surface into the matching [EntError] variant,
     * and `allOrThrow` delegates here.
     *
     * Failure mapping:
     *  - any matched row denied by LOAD privacy →
     *    `Err(PrivacyDenied)` (the first denial wins via the
     *    underlying `evaluateLoadPrivacy` raise)
     *  - other uncaught Exception → routed through
     *    [classifyDriverError] so SQLSTATE 23xxx surfaces as
     *    `Err(ConstraintViolation)` and the fallback is
     *    `Err(DriverFailure)`
     */
    private fun buildAllOrError(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val resultType = ENT_RESULT.parameterizedBy(listType)
        return FunSpec.builder("allOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  val c = requireClient()\n")
                    .add("  val privacy = c.currentPrivacyContext()\n")
                    .add(
                        "  val spec = runReadInterceptors(%T.ALL, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    .add(
                        "  val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)\n",
                        entityClass,
                    )
                    .add("  val results = rows.map { %T.fromRow(it) }\n", entityClass)
                    .add("  if (c.%L.hasLoadPrivacy()) {\n", repoPropName)
                    .add("    for (entity in results) c.%L.evaluateLoadPrivacy(privacy, entity)\n", repoPropName)
                    .add("  }\n")
                    .add(
                        "  %T.Ok(%L)\n",
                        ENT_RESULT,
                        if (hasEdges) "loadEdges(results, privacy)" else "results",
                    )
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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
     * subset of matching **root** rows the current viewer can LOAD.
     * Root rows that fail LOAD privacy are dropped from the result
     * instead of triggering an exception. Driver failures still
     * propagate as raw exceptions; use [visibleAllOrError] for the
     * structured form.
     *
     * **Visible filtering is root-only.** Eager-loaded edge targets
     * via `with...()` still enforce target LOAD privacy strictly —
     * a denied target throws `PrivacyDeniedException` from this
     * call, the same way it does for `allOrThrow`. The rationale
     * (and the workaround — chain visible queries on the target
     * side) is documented in the RFC's "Visible-only API contract"
     * section.
     *
     * **When the repo has LOAD privacy rules**, the storage scan is
     * bounded by `EntClientConfig.visibleOverfetchLimit` (default
     * 100): the driver fetches at most `min(queryLimit ?: cap, cap)`
     * rows so a few visible rows hidden behind many denied ones
     * don't pull an unbounded result set into memory. Cap exhaustion
     * is silent on this path — the caller gets whatever was visible
     * within the budget; `visibleAllOrError` is the variant that
     * surfaces cap-exhausted as an explicit Err.
     *
     * **When the repo has no LOAD privacy rules**, the cap is
     * skipped entirely — there's nothing to filter in-process, so
     * "visible all" is just "all," and the caller's `queryLimit`
     * (if any) is the only bound. Otherwise a no-privacy entity
     * would silently truncate at the cap which is surprising.
     */
    private fun buildVisibleAll(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val builder = FunSpec.builder("visibleAll")
            .returns(listType)
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val spec = runReadInterceptors(%T.ALL, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
        builder.addCode(
            CodeBlock.builder()
                // No-privacy fast path: skip the overfetch cap entirely
                // and pass the spec's (post-interceptor) limit through.
                // "visible all" == "all" when there's nothing to filter.
                .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
                .addStatement(
                    "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)",
                    entityClass,
                )
                .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
                .also {
                    if (hasEdges) {
                        it.addStatement("return loadEdges(results, privacy)")
                    } else {
                        it.addStatement("return results")
                    }
                }
                .endControlFlow()
                // With LOAD privacy: apply the overfetch cap. The
                // post-interceptor effective limit (spec.limit) is the
                // input to the cap math, so an interceptor's
                // requireLimitAtMost can tighten the scan further.
                .addStatement("val scanLimit = minOf(spec.limit ?: c.visibleOverfetchLimit, c.visibleOverfetchLimit)")
                .addStatement(
                    "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)",
                    entityClass,
                )
                .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
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
     * filter-only bulk read.
     *
     * **Visible filtering is root-only.** Eager-loaded edge targets
     * via `with...()` still enforce target LOAD privacy strictly —
     * a denied target surfaces as `Err(PrivacyDenied)` from this
     * call, not as a silent root-row drop. See the RFC's
     * "Visible-only API contract" section for the rationale and the
     * chain-visible-queries workaround.
     *
     * **When the repo has LOAD privacy rules**, returns
     * `Err(OverfetchCapExceeded)` when the storage scan hit
     * `EntClientConfig.visibleOverfetchLimit` (meaning more matching
     * rows may exist beyond the cap that we didn't filter). The
     * detection is conservative: "rows returned >= cap" triggers
     * the Err even if the actual row count is exactly `cap`, and
     * `limit(cap)` is not "strictly smaller than cap" so it still
     * triggers — callers either re-query with a larger cap, paginate
     * via `queryOffset`, or accept the partial result.
     *
     * **When the repo has no LOAD privacy rules**, the cap is
     * skipped entirely — there's nothing to filter in-process, so
     * cap-exhaustion is meaningless. The user's `queryLimit` (if
     * any) is the only bound, and `Err(OverfetchCapExceeded)` is
     * never returned. This avoids the surprise of a no-privacy
     * entity being told its 100-row read "exceeded the cap".
     */
    private fun buildVisibleAllOrError(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val resultType = ENT_RESULT.parameterizedBy(listType)
        val builder = FunSpec.builder("visibleAllOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  val c = requireClient()\n")
                    .add("  val privacy = c.currentPrivacyContext()\n")
                    .add(
                        "  val spec = runReadInterceptors(%T.ALL, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    // No-privacy fast path: skip the overfetch cap
                    // entirely. With no in-process filter, "visible
                    // all" reduces to "all" and the spec's (post-
                    // interceptor) limit is the only bound. Cap-
                    // exhaustion has no semantic meaning here.
                    .add("  if (!c.%L.hasLoadPrivacy()) {\n", repoPropName)
                    .add(
                        "    val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)\n",
                        entityClass,
                    )
                    .add("    val results = rows.map { %T.fromRow(it) }\n", entityClass)
                    .add(
                        "    %T.Ok(%L)\n",
                        ENT_RESULT,
                        if (hasEdges) "loadEdges(results, privacy)" else "results",
                    )
                    .add("  } else {\n")
                    .add("    val cap = c.visibleOverfetchLimit\n")
                    .add("    val scanLimit = minOf(spec.limit ?: cap, cap)\n")
                    .add(
                        "    val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)\n",
                        entityClass,
                    )
                    .add(
                        "    val results = rows.map { %T.fromRow(it) }\n",
                        entityClass,
                    )
                    .add(
                        "    val visible = results.filter { e -> try { c.%L.evaluateLoadPrivacy(privacy, e); true } catch (_: %T) { false } }\n",
                        repoPropName, PRIVACY_DENIED,
                    )
                    // Eager-load BEFORE checking the cap, so eager
                    // target privacy denial (which raises
                    // PrivacyDeniedException) wins over cap
                    // exhaustion. Privacy denial is a hard signal
                    // (caller should know about the mismatch); cap
                    // exhaustion is a heuristic ("you should
                    // paginate"). If both apply, the hard signal
                    // surfaces through the dedicated
                    // catch (PrivacyDeniedException) arm below.
                    .add(
                        "    val finalRows = %L\n",
                        if (hasEdges) "loadEdges(visible, privacy)" else "visible",
                    )
                    // Cap exhaustion (privacy path only): we asked
                    // the driver for `cap` rows and got at least that
                    // many. Suppress the Err only when the caller
                    // bounded the scan strictly smaller than the cap.
                    // Uses spec.limit (post-interceptor) so a
                    // requireLimitAtMost(N<cap) clamp still
                    // suppresses the Err.
                    .add("    val capturedLimit = spec.limit\n")
                    .add("    if (rows.size >= cap && (capturedLimit == null || capturedLimit >= cap)) {\n")
                    .add(
                        "      %T.Err(%T.OverfetchCapExceeded(%S, %T.QUERY, cap))\n",
                        ENT_RESULT, ENT_ERROR, schemaName, ENT_OPERATION,
                    )
                    .add("    } else {\n")
                    .add("      %T.Ok(finalRows)\n", ENT_RESULT)
                    .add("    }\n")
                    .add("  }\n")
                    // Eager-edge LOAD denial via loadEdges(...) can
                    // re-raise PrivacyDeniedException. Without this
                    // explicit catch arm it would fall through to the
                    // generic Exception arm and be misclassified as
                    // Err(DriverFailure). Same shape as allOrError /
                    // firstOrError.
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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
        return builder.build()
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
                "val spec = runReadInterceptors(%T.FIRST, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            // `first` semantics: cap at 1 unconditionally — an
            // interceptor clamp can only further restrict, never
            // raise above 1.
            .addStatement(
                "val row = driver.query(%T.TABLE, spec.predicates, spec.orderBy, 1, spec.offset).firstOrNull()",
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
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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
     * `firstVisibleOrNull(): T?` — scans matched **root** rows in
     * storage order and returns the first row LOAD privacy allows.
     * Returns `null` if no matched root row is visible OR if the cap
     * was exhausted before finding one. Driver failures still
     * propagate as raw exceptions.
     *
     * **Visible filtering is root-only.** Eager-loaded edge targets
     * via `with...()` still enforce target LOAD privacy strictly —
     * a denied target throws `PrivacyDeniedException` from this
     * call. The "visible" name guarantees the *root entity*
     * survives privacy filtering; it does not recursively apply to
     * the eager subgraph. See the RFC's "Visible-only API contract"
     * for the rationale and the chain-visible-queries workaround.
     *
     * **When the repo has LOAD privacy rules**, scanning is bounded
     * by `EntClientConfig.visibleOverfetchLimit` (default 100): at
     * most `min(queryLimit ?: cap, cap)` rows are pulled from
     * storage. Per the RFC, cap-exhaustion is silent — the "no
     * visible row found within the work budget" outcome is
     * indistinguishable from genuine absence, which is fine for the
     * optimistic-read shape this method advertises.
     *
     * **When the repo has no LOAD privacy rules**, only one row is
     * fetched (limit 1) — there's no in-process filter that might
     * skip rows, so the first row from storage is the answer.
     * Skipping the cap avoids pulling 100 rows just to return one.
     */
    private fun buildFirstVisibleOrNull(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val builder = FunSpec.builder("firstVisibleOrNull")
            .returns(entityClass.copy(nullable = true))
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val spec = runReadInterceptors(%T.FIRST, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
        builder.addCode(
            CodeBlock.builder()
                // No-privacy fast path: only fetch 1 row. Nothing to
                // skip on the filter side, so the first storage row is
                // the answer. Skipping the cap-sized scan avoids
                // pulling up to 100 rows just to return one.
                .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
                .addStatement(
                    "val row = driver.query(%T.TABLE, spec.predicates, spec.orderBy, 1, spec.offset).firstOrNull() ?: return null",
                    entityClass,
                )
                .addStatement("val entity = %T.fromRow(row)", entityClass)
                .also {
                    if (hasEdges) it.addStatement("return loadEdges(listOf(entity), privacy).first()")
                    else it.addStatement("return entity")
                }
                .endControlFlow()
                // With LOAD privacy: cap the scan so the in-process
                // filter has bounded work even when many storage rows
                // are denied. Uses spec.limit so a
                // requireLimitAtMost(N<cap) clamp tightens further.
                .addStatement("val scanLimit = minOf(spec.limit ?: c.visibleOverfetchLimit, c.visibleOverfetchLimit)")
                .addStatement(
                    "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)",
                    entityClass,
                )
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
                "val spec = runReadInterceptors(%T.VISIBLE_COUNT, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement(
                "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)",
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
            .addStatement(
                "val spec = runReadInterceptors(%T.RAW_COUNT, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement("return driver.count(%T.TABLE, spec.predicates)", entityClass)
            .build()
    }

    /**
     * `rawCountOrError(): EntResult<Long>` — structured-result form of
     * [rawCount]. Maps each failure mode to its [EntError] variant:
     *  - interceptor rejection → `Err(QueryRejected)`
     *  - any other uncaught Exception → routed through
     *    [classifyDriverError]
     *
     * There is no PrivacyDenied surface here — rawCount intentionally
     * bypasses LOAD privacy, so PrivacyDeniedException can't occur on
     * this path.
     */
    private fun buildRawCountOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val resultType = ENT_RESULT.parameterizedBy(LONG)
        return FunSpec.builder("rawCountOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add(
                        "  val spec = runReadInterceptors(%T.RAW_COUNT, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    .add(
                        "  %T.Ok(driver.count(%T.TABLE, spec.predicates))\n",
                        ENT_RESULT, entityClass,
                    )
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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
     * `visibleCountOrError(): EntResult<Long>` — structured-result form
     * of [visibleCount]. Maps each failure mode to its [EntError] variant.
     *
     * Visible-count materializes rows to evaluate LOAD privacy, so the
     * same PrivacyDeniedException catch arm used by [allOrError] applies
     * (e.g. an eager edge's denied target re-raises). Denied root rows
     * are silently filtered (per visibleCount semantics) — those don't
     * surface as PrivacyDenied here.
     */
    private fun buildVisibleCountOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val resultType = ENT_RESULT.parameterizedBy(LONG)
        return FunSpec.builder("visibleCountOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  val c = requireClient()\n")
                    .add("  val privacy = c.currentPrivacyContext()\n")
                    .add(
                        "  val spec = runReadInterceptors(%T.VISIBLE_COUNT, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    .add(
                        "  val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)\n",
                        entityClass,
                    )
                    .add("  val results = rows.map { %T.fromRow(it) }\n", entityClass)
                    .add("  if (!c.%L.hasLoadPrivacy()) {\n", repoPropName)
                    .add("    %T.Ok(results.size.toLong())\n", ENT_RESULT)
                    .add("  } else {\n")
                    .add("    var count = 0L\n")
                    .add("    for (entity in results) {\n")
                    .add("      try { c.%L.evaluateLoadPrivacy(privacy, entity); count++ } catch (_: %T) {}\n", repoPropName, PRIVACY_DENIED)
                    .add("    }\n")
                    .add("    %T.Ok(count)\n", ENT_RESULT)
                    .add("  }\n")
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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
     * `rawExists(): Boolean` — fast existence check that skips
     * privacy entirely. Returns true iff at least one storage row
     * matches the predicate. Use this for "does this row exist at
     * all?" semantics (uniqueness checks before insert, idempotency
     * keys, etc.) where privacy of the caller is not the relevant
     * question.
     *
     * Replaces the legacy `exists()` that fetched one row and threw
     * `PrivacyDeniedException` if it was denied — neither "do any
     * rows exist?" nor "is there a row I can see?" was the answer
     * you got, which surprised callers. Use [visibleExists] for the
     * privacy-aware variant.
     */
    private fun buildRawExists(entityClass: ClassName): FunSpec {
        return FunSpec.builder("rawExists")
            .returns(BOOLEAN)
            .addKdoc(
                "Fast existence check; skips LOAD privacy. Returns true iff at least one\n" +
                "storage row matches the predicate. Pair with [visibleExists] for the\n" +
                "privacy-aware variant.",
            )
            .addStatement(
                "val spec = runReadInterceptors(%T.RAW_EXISTS, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            // exists is fixed at limit-1 — interceptor clamps can
            // only further restrict (to 0) so honor spec.limit if
            // it's been set lower than 1.
            .addStatement("val limit = minOf(1, spec.limit ?: 1)")
            .addStatement(
                "return driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty()",
                entityClass,
            )
            .build()
    }

    /**
     * `visibleExists(): Boolean` — privacy-aware existence check.
     * Returns true iff at least one storage row matches the
     * predicate AND the current viewer can LOAD it. Scans storage
     * order, bounded by `EntClientConfig.visibleOverfetchLimit`
     * (same cap as `firstVisibleOrNull`), and returns true on the
     * first visible row. Cap-exhausted-with-no-visible is silent
     * (returns false), matching the optimistic-read shape used by
     * `firstVisibleOrNull`.
     *
     * No-privacy fast path: when the repo has no LOAD rules, falls
     * through to [rawExists] semantics (single-row probe, no cap).
     */
    /**
     * `rawExistsOrError(): EntResult<Boolean>` — structured-result form
     * of [rawExists]. Same failure-mode mapping as [rawCountOrError]:
     * interceptor rejection → `Err(QueryRejected)`, driver failure →
     * `Err(DriverFailure)`. No PrivacyDenied surface (rawExists bypasses
     * LOAD privacy).
     */
    private fun buildRawExistsOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val resultType = ENT_RESULT.parameterizedBy(BOOLEAN)
        return FunSpec.builder("rawExistsOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add(
                        "  val spec = runReadInterceptors(%T.RAW_EXISTS, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    .add("  val limit = minOf(1, spec.limit ?: 1)\n")
                    .add(
                        "  %T.Ok(driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty())\n",
                        ENT_RESULT, entityClass,
                    )
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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

    private fun buildVisibleExists(schemaName: String, entityClass: ClassName): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val builder = FunSpec.builder("visibleExists")
            .returns(BOOLEAN)
            .addKdoc(
                "Privacy-aware existence check. Returns true iff at least one storage row\n" +
                "matches AND the viewer can LOAD it. Bounded by `visibleOverfetchLimit` on\n" +
                "the privacy path; cap-exhausted-with-no-visible returns false silently.",
            )
            .addStatement("val c = requireClient()")
            .addStatement("val privacy = c.currentPrivacyContext()")
            .addStatement(
                "val spec = runReadInterceptors(%T.VISIBLE_EXISTS, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
        builder.addCode(
            CodeBlock.builder()
                // No-privacy fast path: single-row probe, no cap.
                .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
                .addStatement("val limit = minOf(1, spec.limit ?: 1)")
                .addStatement(
                    "return driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty()",
                    entityClass,
                )
                .endControlFlow()
                // Privacy path: cap the scan, return true on first
                // visible row, false if cap exhausted or no rows.
                .addStatement("val scanLimit = minOf(spec.limit ?: c.visibleOverfetchLimit, c.visibleOverfetchLimit)")
                .addStatement(
                    "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)",
                    entityClass,
                )
                .beginControlFlow("for (row in rows)")
                .addStatement("val entity = %T.fromRow(row)", entityClass)
                .beginControlFlow("try")
                .addStatement("c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
                .addStatement("return true")
                .nextControlFlow("catch (_: %T)", PRIVACY_DENIED)
                .endControlFlow()
                .endControlFlow()
                .addStatement("return false")
                .build()
        )
        return builder.build()
    }

    /**
     * `visibleExistsOrError(): EntResult<Boolean>` — structured-result
     * form of [visibleExists]. Same failure-mode mapping as the *OrError
     * counterparts: interceptor rejection → `Err(QueryRejected)`, driver
     * failure → `Err(DriverFailure)`. Denied rows are silently scanned
     * past per visibleExists semantics — only an eager-edge denial path
     * (which visibleExists doesn't have) could surface PrivacyDenied.
     */
    private fun buildVisibleExistsOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val resultType = ENT_RESULT.parameterizedBy(BOOLEAN)
        return FunSpec.builder("visibleExistsOrError")
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  val c = requireClient()\n")
                    .add("  val privacy = c.currentPrivacyContext()\n")
                    .add(
                        "  val spec = runReadInterceptors(%T.VISIBLE_EXISTS, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    .add("  if (!c.%L.hasLoadPrivacy()) {\n", repoPropName)
                    .add("    val limit = minOf(1, spec.limit ?: 1)\n")
                    .add(
                        "    %T.Ok(driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty())\n",
                        ENT_RESULT, entityClass,
                    )
                    .add("  } else {\n")
                    .add("    val scanLimit = minOf(spec.limit ?: c.visibleOverfetchLimit, c.visibleOverfetchLimit)\n")
                    .add(
                        "    val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)\n",
                        entityClass,
                    )
                    .add("    var found = false\n")
                    .add("    for (row in rows) {\n")
                    .add("      val entity = %T.fromRow(row)\n", entityClass)
                    .add("      try { c.%L.evaluateLoadPrivacy(privacy, entity); found = true; break } catch (_: %T) {}\n", repoPropName, PRIVACY_DENIED)
                    .add("    }\n")
                    .add("    %T.Ok(found)\n", ENT_RESULT)
                    .add("  }\n")
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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
            .addKdoc("Return a [QueryPlan] describing the query shapes [allOrThrow] would execute.\n" +
                "Eager edge subplans show structure, not multiplicity — nested\n" +
                "eager loads may execute once per parent group at runtime.\n" +
                "Interceptors run with operation = ALL; their predicate and\n" +
                "limit contributions show up in the plan.")
            .returns(queryPlan)
            .addStatement(
                "val spec = runReadInterceptors(%T.ALL, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement("return buildQueryPlan(spec, true)")
            .build()

        // explainFirst() → models firstOrNull()
        methods += FunSpec.builder("explainFirst")
            .addKdoc("Return a [QueryPlan] describing the query shapes [firstOrNull] would execute.\n" +
                "Interceptors run with operation = FIRST; limit operations are\n" +
                "silent no-ops per the RFC's limit-shape rules.")
            .returns(queryPlan)
            .addStatement(
                "val spec = runReadInterceptors(%T.FIRST, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            // first() is hardwired to limit 1; honor interceptor
            // requireLimitAtMost(0) by min'ing with the spec.
            .addStatement("val limit = minOf(1, spec.limit ?: 1)")
            .addStatement(
                "return buildQueryPlan(spec.copy(limit = limit), true)",
            )
            .build()

        // explainExists() → models rawExists()
        methods += FunSpec.builder("explainExists")
            .addKdoc("Return a [QueryPlan] describing the query shape [rawExists] would execute.\n" +
                "Interceptors run with operation = RAW_EXISTS; limit operations\n" +
                "are silent no-ops.")
            .returns(queryPlan)
            .addStatement(
                "val spec = runReadInterceptors(%T.RAW_EXISTS, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement("val limit = minOf(1, spec.limit ?: 1)")
            .addStatement(
                "return buildQueryPlan(spec.copy(limit = limit), false)",
            )
            .build()

        // explainVisibleCount() → models visibleCount()
        methods += FunSpec.builder("explainVisibleCount")
            .addKdoc("Return a [QueryPlan] describing the query shape [visibleCount] would execute.\n" +
                "Interceptors run with operation = VISIBLE_COUNT; limit operations\n" +
                "are silent no-ops per the RFC.")
            .returns(queryPlan)
            .addStatement(
                "val spec = runReadInterceptors(%T.VISIBLE_COUNT, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement("return buildQueryPlan(spec, false)")
            .build()

        // explainRawCount() → models rawCount()
        methods += FunSpec.builder("explainRawCount")
            .addKdoc("Return a [QueryPlan] describing the query [rawCount] would execute.\n" +
                "Interceptors run with operation = RAW_COUNT; predicate\n" +
                "contributions show up in the plan, limit operations are silent\n" +
                "no-ops per the RFC.")
            .returns(queryPlan)
            .addStatement(
                "val spec = runReadInterceptors(%T.RAW_COUNT, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement("return %T(driver.explainCount(%T.TABLE, spec.predicates))", queryPlan, entityClass)
            .build()

        // Private buildQueryPlan helper. Takes the post-interceptor
        // FrozenQuerySpec rather than raw limit/offset so the explain
        // output reflects every predicate, limit, and offset
        // contribution from the chain (including
        // requireLimitAtMost / addPredicate / setDefaultLimitIfAbsent).
        val helper = FunSpec.builder("buildQueryPlan")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("spec", FROZEN_QUERY_SPEC)
            .addParameter("includeEager", BOOLEAN)
            .returns(queryPlan)
            .addStatement(
                "val root = driver.explainQuery(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)",
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

    /**
     * Helper: builds a [QuerySpecBuilder] seeded with the caller's
     * authored state on this query (predicates / orderFields /
     * queryLimit / queryOffset) plus any structural predicates the
     * terminal contributes, then runs the per-entity + global
     * interceptor chain via [InterceptorEngine.apply].
     *
     * Returns the [FrozenQuerySpec] terminals should hand to the
     * driver. Translates the internal [AbortQueryRejected] marker into
     * the user-facing [EntQueryRejectedException] at the boundary so
     * downstream terminal code only ever sees the public type.
     */
    private fun buildRunReadInterceptors(schemaName: String, entityClass: ClassName): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val readOp = READ_OPERATION
        val structuralListType = List::class.asClassName().parameterizedBy(predicateClass)
        return FunSpec.builder("runReadInterceptors")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("operation", readOp)
            .addParameter("entOperation", ENT_OPERATION)
            .addParameter(
                ParameterSpec.builder("extraStructural", structuralListType)
                    .defaultValue("emptyList()")
                    .build()
            )
            .returns(FROZEN_QUERY_SPEC)
            .addCode(
                CodeBlock.builder()
                    .addStatement("val c = requireClient()")
                    .addStatement("val privacy = c.currentPrivacyContext()")
                    // Merge any in-place structural predicate from
                    // a prior traversal step (HasEdgeWith /
                    // HasM2MEdgeFrom / HasEdge) with extras the
                    // caller passed in (used by byId-style
                    // structural ids — query terminals pass none).
                    .addStatement(
                        "val structural = listOfNotNull(traversalStructural) + extraStructural",
                    )
                    // rootEntity walks back along the traversal
                    // path; if no traversal context, this query IS
                    // the root. Otherwise the first EdgeStep's
                    // source is the chain's origin.
                    .addStatement(
                        "val root = traversalPath.firstOrNull()?.source ?: %T::class",
                        entityClass,
                    )
                    .add("val builder = %T(\n", QUERY_SPEC_BUILDER)
                    .add("  table = %T.TABLE,\n", entityClass)
                    .add("  entity = %T::class,\n", entityClass)
                    .add("  callerPredicates = predicates,\n")
                    .add("  structuralPredicates = structural,\n")
                    .add("  orderBy = orderFields,\n")
                    .add("  callerLimit = queryLimit,\n")
                    .add("  offset = queryOffset,\n")
                    .add("  flags = emptySet(),\n")
                    .add(")\n")
                    .add("val context = %T(\n", QUERY_CONTEXT)
                    .add("  privacy = privacy,\n")
                    .add("  operation = operation,\n")
                    .add("  rootEntity = root,\n")
                    .add("  currentEntity = %T::class,\n", entityClass)
                    .add("  sourceEntity = traversalSourceEntity,\n")
                    .add("  edgeName = traversalEdgeName,\n")
                    .add("  path = traversalPath,\n")
                    .add("  flags = emptySet(),\n")
                    .add(")\n")
                    .add("val frozen = try {\n")
                    .add("  %T.apply(\n", INTERCEPTOR_ENGINE)
                    .add("    builder = builder,\n")
                    .add("    context = context,\n")
                    .add("    entity = %S,\n", schemaName)
                    .add("    entOperation = entOperation,\n")
                    .add("    entityInterceptors = c.entityInterceptors.entityInterceptorsFor(%S),\n", repoPropName)
                    .add("    globalInterceptors = c.entityInterceptors.globals(),\n")
                    .add("  )\n")
                    .add("} catch (e: %T) {\n", ABORT_QUERY_REJECTED)
                    .add("  throw %T(e.rejected)\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("}\n")
                    // Walk the post-interceptor predicate tree and
                    // run EDGE_PREDICATE interceptors on any
                    // HasEdgeWith / HasEdge subpredicates. The
                    // target entity's interceptors get a chance to
                    // narrow the EXISTS subquery (so e.g. soft-
                    // deleted target rows don't contribute to the
                    // existence check). Structural predicates we
                    // injected (traversalStructural from a queryX
                    // step, or extraStructural for eager-load's IN
                    // predicate / byId's id = X) are NOT re-walked
                    // — they were either already processed by the
                    // prior step's interceptors (traversal source
                    // step) or are framework-synthetic plumbing.
                    .addStatement("val skipWalk: Set<%T> = (listOfNotNull(traversalStructural) + extraStructural).toSet()", predicateClass)
                    .addStatement(
                        "val walked = frozen.predicates.map { p -> if (p in skipWalk) p else runEdgePredicateInterceptors(p, traversalPath) }",
                    )
                    .addStatement("return frozen.copy(predicates = walked)")
                    .build()
            )
            .build()
    }

    /**
     * Walk a [Predicate] tree and rewrite [Predicate.HasEdgeWith] /
     * [Predicate.HasEdge] sub-nodes by firing the target entity's
     * interceptors against the inner predicate with
     * [ReadOperation.EDGE_PREDICATE]. Boolean combinators (And / Or)
     * recurse; leaves return unchanged.
     *
     * HasEdge with no inner upgrades to HasEdgeWith when target
     * interceptors add predicates; stays HasEdge otherwise.
     * Important for the soft-delete shape — `User.articles.has()`
     * must filter out soft-deleted articles to give correct
     * existence semantics.
     *
     * [HasM2MEdgeFrom] is NOT rewritten in V1 — the dispatch keyed
     * by source-table (rather than this query's outgoing edge name)
     * needs a separate global registry. Workaround: use the
     * traversal form (`queryX()`) for M2M, which fires source
     * interceptors via Phase 5a.
     *
     * The dispatch `when` on the edge name is generated per-source-
     * entity from the schema's outgoing edges. Edges whose targets
     * aren't visible to codegen fall through unchanged.
     */
    private fun buildRunEdgePredicateInterceptors(
        schemaName: String,
        entityClass: ClassName,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val edgeStepClass = ClassName("entkt.runtime", "EdgeStep")
        val body = CodeBlock.builder()
        body.addStatement("val c = requireClient()")
        body.add("return when (predicate) {\n")
        body.add(
            "  is %T.And -> %T.And(runEdgePredicateInterceptors(predicate.left, parentPath), runEdgePredicateInterceptors(predicate.right, parentPath))\n",
            predicateClass, predicateClass,
        )
        body.add(
            "  is %T.Or -> %T.Or(runEdgePredicateInterceptors(predicate.left, parentPath), runEdgePredicateInterceptors(predicate.right, parentPath))\n",
            predicateClass, predicateClass,
        )
        body.add("  is %T.HasEdgeWith -> {\n", predicateClass)
        body.add("    val processedInner = when (predicate.edge) {\n")
        for (edge in schema.edges()) {
            val targetName = schemaNames[edge.target] ?: continue
            val targetClass = ClassName(packageName, targetName)
            val targetQueryClass = ClassName(packageName, "${targetName}Query")
            // The edge name in HasEdgeWith corresponds to an edge
            // on THIS query's entity (the source). Dispatch by
            // this entity's outgoing edge names.
            body.add(
                "      %S -> {\n",
                edge.name,
            )
            // Reuse this source query's driver — the target
            // query never touches it on this code path (we only
            // call runReadInterceptors, which is pure transform),
            // but its primary constructor requires one.
            body.add(
                "        val targetQ = %T(driver, c)\n",
                targetQueryClass,
            )
            body.add("        targetQ.predicates = listOf(predicate.inner)\n")
            body.add("        targetQ.traversalSourceEntity = %T::class\n", entityClass)
            body.add("        targetQ.traversalEdgeName = predicate.edge\n")
            body.add(
                "        targetQ.traversalPath = parentPath + %T(%T::class, predicate.edge, %T::class)\n",
                edgeStepClass, entityClass, targetClass,
            )
            body.add(
                "        val spec = targetQ.runReadInterceptors(%T.EDGE_PREDICATE, %T.QUERY)\n",
                READ_OPERATION, ENT_OPERATION,
            )
            body.add(
                "        spec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) } ?: predicate.inner\n",
                predicateClass,
            )
            body.add("      }\n")
        }
        body.add("      else -> predicate.inner\n")
        body.add("    }\n")
        body.add("    %T.HasEdgeWith(predicate.edge, processedInner)\n", predicateClass)
        body.add("  }\n")
        // HasEdge (no inner): if any target interceptor adds
        // predicates, upgrade to HasEdgeWith with the interceptor-
        // contributed predicates as the inner. If interceptors add
        // nothing, keep as HasEdge. Important for soft-delete on the
        // target — `User.articles.has()` must still filter out
        // soft-deleted articles, otherwise the existence check is
        // wrong.
        body.add("  is %T.HasEdge -> {\n", predicateClass)
        body.add("    when (predicate.edge) {\n")
        for (edge in schema.edges()) {
            val targetName = schemaNames[edge.target] ?: continue
            val targetClass = ClassName(packageName, targetName)
            val targetQueryClass = ClassName(packageName, "${targetName}Query")
            body.add(
                "      %S -> {\n",
                edge.name,
            )
            body.add(
                "        val targetQ = %T(driver, c)\n",
                targetQueryClass,
            )
            body.add("        targetQ.traversalSourceEntity = %T::class\n", entityClass)
            body.add("        targetQ.traversalEdgeName = predicate.edge\n")
            body.add(
                "        targetQ.traversalPath = parentPath + %T(%T::class, predicate.edge, %T::class)\n",
                edgeStepClass, entityClass, targetClass,
            )
            body.add(
                "        val spec = targetQ.runReadInterceptors(%T.EDGE_PREDICATE, %T.QUERY)\n",
                READ_OPERATION, ENT_OPERATION,
            )
            body.add(
                "        val combined = spec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }\n",
                predicateClass,
            )
            body.add(
                "        if (combined != null) %T.HasEdgeWith(predicate.edge, combined) else predicate\n",
                predicateClass,
            )
            body.add("      }\n")
        }
        body.add("      else -> predicate\n")
        body.add("    }\n")
        body.add("  }\n")
        body.add("  else -> predicate\n")
        body.add("}\n")

        return FunSpec.builder("runEdgePredicateInterceptors")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("predicate", predicateClass)
            .addParameter(
                "parentPath",
                List::class.asClassName().parameterizedBy(edgeStepClass),
            )
            .returns(predicateClass)
            .addCode(body.build())
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
            // Reject negatives at the boundary so the bad input never
            // reaches the driver. Postgres rejects LIMIT -1 with a
            // syntax error; the InMemoryDriver's `take(-1)` throws
            // IllegalArgumentException from inside the query — both
            // are confusing failures one layer removed from the
            // caller. Loud-fail here instead.
            .addStatement("require(n >= 0) { %S + n }", "limit must be non-negative; was ")
            .addStatement("this.queryLimit = n")
            .addStatement("return this")
            .build()
    }

    private fun buildOffset(queryClass: ClassName): FunSpec {
        return FunSpec.builder("offset")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("require(n >= 0) { %S + n }", "offset must be non-negative; was ")
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
                    emitM2MEagerBlock(body, eagerPropName, edgePropName, edge.name, join, entityClass, targetClass, targetName)
                }
                is EdgeKind.BelongsTo -> {
                    val join = resolveEdgeJoin(edge, schema) ?: continue
                    emitToOneEagerBlock(body, eagerPropName, edgePropName, edge.name, join, entityClass, targetClass, targetName, schema, schemaNames)
                }
                is EdgeKind.HasOne -> {
                    val join = resolveEdgeJoin(edge, schema) ?: continue
                    emitHasOneEagerBlock(body, eagerPropName, edgePropName, edge.name, join, entityClass, targetClass, targetName)
                }
                is EdgeKind.HasMany -> {
                    val join = resolveEdgeJoin(edge, schema) ?: continue
                    emitToManyEagerBlock(body, eagerPropName, edgePropName, edge.name, join, entityClass, targetClass, targetName)
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
        edgeName: String,
        join: EdgeJoin,
        sourceClass: ClassName,
        targetClass: ClassName,
        targetName: String,
    ) {
        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val sourceIds = entities.map { it.id }")
        emitEagerSubquerySetup(body, edgeName, sourceClass, targetClass)
        // Run target interceptors with EAGER_LOAD. The IN
        // predicate that ties target rows back to the source ids
        // goes in via extraStructural so it's tagged STRUCTURAL,
        // not CALLER. Fetch all matching rows — limit/offset are
        // applied per group below.
        body.addStatement(
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf(%S, %T.IN, sourceIds)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, join.targetColumn, OP,
        )
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null)",
            targetClass,
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
        edgeName: String,
        join: EdgeJoin,
        sourceClass: ClassName,
        targetClass: ClassName,
        targetName: String,
    ) {
        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val sourceIds = entities.map { it.id }")
        emitEagerSubquerySetup(body, edgeName, sourceClass, targetClass)
        body.addStatement(
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf(%S, %T.IN, sourceIds)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, join.targetColumn, OP,
        )
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null)",
            targetClass,
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
        edgeName: String,
        join: EdgeJoin,
        sourceClass: ClassName,
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
        emitEagerSubquerySetup(body, edgeName, sourceClass, targetClass)
        // Fetch all matching targets — limit/offset is meaningless for to-one.
        body.addStatement(
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf(%S, %T.IN, fkValues)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, join.targetColumn, OP,
        )
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null)",
            targetClass,
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
        edgeName: String,
        join: EdgeJoin,
        sourceClass: ClassName,
        targetClass: ClassName,
        targetName: String,
    ) {
        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val sourceIds = entities.map { it.id }")
        // Query junction table (no interceptors — the junction
        // is internal storage, not an entity with interceptors).
        body.addStatement(
            "val junctionRows = driver.query(%S, listOf(%T.Leaf(%S, %T.IN, sourceIds)), emptyList(), null, null)",
            join.junctionTable, PREDICATE, join.junctionSourceColumn, OP,
        )
        body.beginControlFlow("if (junctionRows.isNotEmpty())")
        body.addStatement(
            "val targetIds = junctionRows.map { it[%S] }.distinct()",
            join.junctionTargetColumn,
        )
        emitEagerSubquerySetup(body, edgeName, sourceClass, targetClass)
        // Fetch all matching targets — limit/offset are applied per group below.
        body.addStatement(
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf(%S, %T.IN, targetIds)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, "id", OP,
        )
        body.addStatement(
            "val targetRows = driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null)",
            targetClass,
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
     * Set the eager subquery's traversal context (sourceEntity /
     * edgeName / path) so its [runReadInterceptors] call exposes
     * the right [QueryContext] for [ReadOperation.EAGER_LOAD]. Must
     * be called inside the `subQuery ->` lambda — uses `subQuery`
     * as the receiver. `entities.map { it.id }` and similar setup
     * happens before this call; this just patches in the context.
     */
    private fun emitEagerSubquerySetup(
        body: CodeBlock.Builder,
        edgeName: String,
        sourceClass: ClassName,
        targetClass: ClassName,
    ) {
        val edgeStepClass = ClassName("entkt.runtime", "EdgeStep")
        body.addStatement("subQuery.traversalSourceEntity = %T::class", sourceClass)
        body.addStatement("subQuery.traversalEdgeName = %S", edgeName)
        body.addStatement(
            "subQuery.traversalPath = this.traversalPath + %T(%T::class, %S, %T::class)",
            edgeStepClass, sourceClass, edgeName, targetClass,
        )
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
        val sourceName = schemaNames[source] ?: return null
        val targetName = schemaNames[edge.target] ?: return null
        val sourceEntityClass = ClassName(packageName, sourceName)
        val targetEntityClass = ClassName(packageName, targetName)
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"
        val sourceTable = source.tableName
        val edgeStepClass = ClassName("entkt.runtime", "EdgeStep")

        return FunSpec.builder(methodName)
            .returns(targetQueryClass)
            // Fire SOURCE interceptors with EDGE_TRAVERSAL before
            // materializing the bridging predicate. The post-
            // interceptor predicates fold into the HasM2MEdgeFrom's
            // sourceFilter so source-side tenant-scope / soft-delete
            // narrowing applies to the bridging EXISTS subquery.
            .addStatement(
                "val sourceSpec = runReadInterceptors(%T.EDGE_TRAVERSAL, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement(
                "val parent = sourceSpec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }",
                predicateClass,
            )
            .addStatement(
                "val structural = %T.HasM2MEdgeFrom(%S, %S, parent)",
                predicateClass,
                sourceTable,
                edge.name,
            )
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            .addStatement("target.traversalSourceEntity = %T::class", sourceEntityClass)
            .addStatement("target.traversalEdgeName = %S", edge.name)
            .addStatement(
                "target.traversalPath = this.traversalPath + %T(%T::class, %S, %T::class)",
                edgeStepClass, sourceEntityClass, edge.name, targetEntityClass,
            )
            .addStatement("target.traversalStructural = structural")
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
        val sourceName = schemaNames[source] ?: return null
        val targetName = schemaNames[edge.target] ?: return null
        val inverse = findInverseEdge(edge, source) ?: return null
        val sourceEntityClass = ClassName(packageName, sourceName)
        val targetEntityClass = ClassName(packageName, targetName)
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"
        val edgeStepClass = ClassName("entkt.runtime", "EdgeStep")

        return FunSpec.builder(methodName)
            .returns(targetQueryClass)
            // Fire SOURCE interceptors with EDGE_TRAVERSAL before
            // materializing the bridging predicate. The post-
            // interceptor predicates fold into the HasEdgeWith
            // inner so source-side narrowing applies to the
            // EXISTS subquery used to bridge to the target.
            .addStatement(
                "val sourceSpec = runReadInterceptors(%T.EDGE_TRAVERSAL, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement(
                "val parent = sourceSpec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }",
                predicateClass,
            )
            .addStatement(
                "val structural: %T = if (parent != null) %T.HasEdgeWith(%S, parent) else %T.HasEdge(%S)",
                predicateClass,
                predicateClass,
                inverse.name,
                predicateClass,
                inverse.name,
            )
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            .addStatement("target.traversalSourceEntity = %T::class", sourceEntityClass)
            .addStatement("target.traversalEdgeName = %S", edge.name)
            .addStatement(
                "target.traversalPath = this.traversalPath + %T(%T::class, %S, %T::class)",
                edgeStepClass, sourceEntityClass, edge.name, targetEntityClass,
            )
            .addStatement("target.traversalStructural = structural")
            .addStatement("return target")
            .build()
    }
}

internal fun toPascalCase(snakeCase: String): String =
    toCamelCase(snakeCase).replaceFirstChar { it.uppercase() }