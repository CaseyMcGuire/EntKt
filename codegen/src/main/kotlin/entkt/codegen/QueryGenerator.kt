package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val EDGE_QUERY = ClassName("entkt.query", "EdgeQuery")
private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP = ClassName("entkt.query", "Op")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val PRIVACY_DENIED = ClassName("entkt.runtime.privacy", "PrivacyDeniedException")
private val QUERY_EXPLANATION = ClassName("entkt.runtime.query", "QueryExplanation")
private val ENT_ERROR = ClassName("entkt.runtime.result", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_RESULT = ClassName("entkt.runtime.result", "EntResult")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val ABORT_QUERY_REJECTED = ClassName("entkt.runtime.query", "AbortQueryRejected")
private val FROZEN_QUERY_SPEC = ClassName("entkt.runtime.query", "FrozenQuerySpec")
private val QUERY_SPEC_BUILDER = ClassName("entkt.runtime.query", "QuerySpecBuilder")
private val QUERY_CONTEXT = ClassName("entkt.runtime.query", "QueryContext")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val INTERCEPTOR_ENGINE = ClassName("entkt.runtime.query", "InterceptorEngine")
private val MEMBER_GET_OR_THROW = com.squareup.kotlinpoet.MemberName("entkt.runtime.result", "getOrThrow")
private val MEMBER_CLASSIFY = com.squareup.kotlinpoet.MemberName("entkt.runtime.driver", "classifyDriverError")

// Raw aggregate terminals.
private val AGG_FUNCTION = ClassName("entkt.runtime.query", "AggregateFunction")
private val AGG_BUCKET = ClassName("entkt.runtime.query", "AggregateBucket")
private val AGG_RESULT_ROW = ClassName("entkt.runtime.query", "AggregateResultRow")
private val COMPARABLE_COLUMN = ClassName("entkt.query", "ComparableColumn")
private val NUMERIC_COLUMN = ClassName("entkt.query", "NumericColumn")
private val INTEGRAL_COLUMN = ClassName("entkt.query", "IntegralColumn")
private val FLOATING_COLUMN = ClassName("entkt.query", "FloatingColumn")
private val GROUPABLE_COLUMN = ClassName("entkt.query", "GroupableColumn")
private val NULLABLE_GROUPABLE_COLUMN = ClassName("entkt.query", "NullableGroupableColumn")
private val KOTLIN_COMPARABLE = ClassName("kotlin", "Comparable")

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
        // Typed convenience names for this entity's predicate/order-field
        // scopes. Used everywhere the generated query stores or accepts
        // its own predicate / order field.
        val predicateForEntity = predicateClass.parameterizedBy(entityClass)
        val orderFieldForEntity = orderFieldClass.parameterizedBy(entityClass)

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
            // Generated query class implements EdgeQuery<EntityClass>;
            // the scope flows out of combinedPredicate() typed as
            // `Predicate<EntityClass>`.
            .addSuperinterface(EDGE_QUERY.parameterizedBy(entityClass))
            // Also implements `EdgePredicateScope<EntityClass>` so it
            // can be used as the narrow receiver inside
            // `EdgeRef.has { ... }` blocks. The generated `where()`
            // method below is marked `override` to satisfy this
            // interface's `where(Predicate<E>)` member — Kotlin
            // permits the concrete query class to refine the return
            // type covariantly from `EdgePredicateScope<E>` to the
            // concrete query type so chaining outside `has` blocks
            // still returns the wider type.
            .addSuperinterface(
                ClassName("entkt.query", "EdgePredicateScope").parameterizedBy(entityClass),
            )
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
            // Mutable query state is `private`:
            // application code in the same module would have write
            // access if these were `internal`, which would let
            // app code mutate `predicates` / `orderFields` /
            // traversal context / `deferredSourceStep` directly and
            // bypass the public DSL (`where(...)`, `orderBy(...)`,
            // queryX traversal). The only cross-instance access these
            // need is from the generated `snapshotForTraversal`
            // method below, which lives inside the same class and
            // therefore enjoys same-class private access in Kotlin
            // (private is class-scoped, not instance-scoped).
            .addProperty(
                PropertySpec.builder(
                    "predicates",
                    List::class.asClassName().parameterizedBy(predicateForEntity),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "orderFields",
                    List::class.asClassName().parameterizedBy(orderFieldForEntity),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            // queryLimit / queryOffset: public getter, private setter.
            // The eager-load codegen path reads them on a sibling
            // `subQuery` (cross-class read), so the getter must be
            // visible. The DSL methods `.limit(n)` / `.offset(n)` are
            // the only legitimate write path and enforce `require(n >= 0)`
            // — direct app-code mutation bypassing the guard is closed
            // by `private set`.
            .addProperty(
                PropertySpec.builder("queryLimit", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.PRIVATE).build())
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryOffset", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.PRIVATE).build())
                    .build()
            )
            // Traversal context: `private var`. Cross-class writes
            // (walker, eager-load, queryX) go through the generated
            // `@EntktInternal internal fun seedEdgeTraversal(...)`
            // method below — application code in the same module
            // can't spoof traversal-source / edge / path on a sibling
            // query without an explicit `@OptIn(EntktInternal::class)`.
            // Same pattern as `setDeferredSourceStep`.
            .addProperty(
                PropertySpec.builder(
                    "traversalSourceEntity",
                    ClassName("kotlin.reflect", "KClass")
                        .parameterizedBy(com.squareup.kotlinpoet.STAR)
                        .copy(nullable = true),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("traversalEdgeName", String::class.asClassName().copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "traversalPath",
                    List::class.asClassName().parameterizedBy(ClassName("entkt.runtime.query", "EdgeStep")),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            // Deferred source-step lambda, populated by generated
            // queryX() methods when this query is the *target* of a
            // traversal. The lambda is invoked at *terminal time*
            // (inside runReadInterceptors' try/catch) so a source-step
            // rejection from `scope.reject(...)` propagates through
            // the same EntQueryRejectedException path as target-step
            // rejection — making `*OrError` terminals on the target
            // able to catch traversal-source rejection too. Returns
            // the bridging predicate to inject as STRUCTURAL into the
            // target's QuerySpecBuilder, plus the source-step
            // annotations to seed the target's spec with so
            // observability consumers see source annotations on the
            // final QueryPlan.
            .addProperty(
                PropertySpec.builder(
                    "deferredSourceStep",
                    LambdaTypeName.get(
                        receiver = null,
                        returnType = ClassName("entkt.runtime.query", "TraversalSourceResult")
                            .parameterizedBy(entityClass),
                    ).copy(nullable = true),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperties(eagerEdgeSpecs.map { it.property })
            .addFunction(buildWhere(queryClass, predicateForEntity))
            .addFunction(buildOrderBy(queryClass, orderFieldForEntity))
            .addFunction(buildLimit(queryClass))
            .addFunction(buildOffset(queryClass))
            .addFunction(buildCombinedPredicate(predicateForEntity))
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
            .addFunction(buildSnapshotForTraversal(queryClass, clientClass))
            .addFunction(buildSeedEdgeTraversal())
            .addFunction(buildSetDeferredSourceStep(entityClass))
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
            .addFunctions(buildAggregateTerminals(schemaName, entityClass))
            .addFunction(buildRawExists(entityClass))
            .addFunction(buildRawExistsOrError(schemaName, entityClass))
            .addFunction(buildVisibleExists(schemaName, entityClass))
            .addFunction(buildVisibleExistsOrError(schemaName, entityClass))
            .addFunctions(buildExplainMethods(entityClass, schema, schemaNames))
            .addFunctions(traversalMethods)
            .build()

        // Every generated query file constructs `Predicate.HasEdge` /
        // `Predicate.HasEdgeWith` / `Predicate.HasM2MEdgeFrom` in the
        // edge-predicate walker and traversal lambdas. Those types
        // carry `@EntktInternal` constructors; the file-level OptIn
        // lets the construction sites compile without per-call annotation.
        return FileSpec.builder(packageName, className)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
                    .build()
            )
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
     * Implemented as a `allOrError().getOrThrow()` wrapper so the
     * privacy / driver mapping stays in one place and guarantees the
     * structured-exception contract
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
     * side) is to chain visible queries on the target side.
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
     * call, not as a silent root-row drop. Chain visible queries on the
     * target side when target filtering should be non-throwing.
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
     * the eager subgraph.
     *
     * **When the repo has LOAD privacy rules**, scanning is bounded
     * by `EntClientConfig.visibleOverfetchLimit` (default 100): at
     * most `min(queryLimit ?: cap, cap)` rows are pulled from
     * storage. Cap-exhaustion is silent — the "no
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
                // IMPORTANT: scope the PrivacyDeniedException catch
                // around evaluateLoadPrivacy ONLY — not around
                // loadEdges. The "visible" contract is root-only:
                // a denied root row silently skips to the next
                // candidate, but a denied EAGER TARGET must throw
                // PrivacyDeniedException out of this call (same
                // shape visibleAll / visibleAllOrError use). Earlier
                // codegen wrapped both calls in one try, which
                // swallowed eager-target denial as if the root were
                // invisible.
                .beginControlFlow("for (row in rows)")
                .addStatement("val entity = %T.fromRow(row)", entityClass)
                .beginControlFlow("val rootVisible = try")
                .addStatement("c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
                .addStatement("true")
                .nextControlFlow("catch (_: %T)", PRIVACY_DENIED)
                .addStatement("false")
                .endControlFlow()
                .addStatement("if (!rootVisible) continue")
                .also {
                    if (hasEdges) it.addStatement("return loadEdges(listOf(entity), privacy).first()")
                    else it.addStatement("return entity")
                }
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
     * The single-metric raw aggregate terminals:
     * ungrouped `rawMin/rawMax/rawSum/rawAvg` returning a typed scalar, and
     * grouped `raw…By` returning `List<AggregateBucket<K, V>>`, each with an
     * `…OrError` twin. All route through the private `aggregateRows` helper, which
     * runs the read interceptors as RAW_AGGREGATE and calls `driver.aggregate`.
     */
    private fun buildAggregateTerminals(schemaName: String, entityClass: ClassName): List<FunSpec> {
        val specs = mutableListOf<FunSpec>()
        val suppress = AnnotationSpec.builder(Suppress::class)
            .addMember("%S", "UNCHECKED_CAST").build()

        // The private helper every aggregate terminal delegates to.
        specs += FunSpec.builder("aggregateRows")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("function", AGG_FUNCTION)
            .addParameter("column", STRING.copy(nullable = true))
            .addParameter("groupBy", STRING.copy(nullable = true))
            .returns(LIST.parameterizedBy(AGG_RESULT_ROW))
            .addStatement(
                "val spec = runReadInterceptors(%T.RAW_AGGREGATE, %T.QUERY)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement(
                "return driver.aggregate(%T.TABLE, function, column, spec.predicates, groupBy)",
                entityClass,
            )
            .build()

        // A fresh `T : Comparable<T>` type variable.
        fun comparableT(): TypeVariableName {
            val t = TypeVariableName("T")
            return t.copy(bounds = listOf(KOTLIN_COMPARABLE.parameterizedBy(t)))
        }

        // OrError try/catch wrapping a happy-path expression (mirrors rawCountOrError).
        fun orErrorBody(happy: CodeBlock): CodeBlock = CodeBlock.builder()
            .add("return try {\n")
            .add("  %T.Ok(%L)\n", ENT_RESULT, happy)
            .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
            .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
            .add("} catch (e: %T) {\n", Exception::class.asClassName())
            .add(
                "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
                ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
            )
            .add("}\n")
            .build()

        // Ungrouped scalar terminal + its OrError twin. An ungrouped aggregate is
        // exactly one row, so take `.single().value` and cast to the metric type.
        fun scalar(name: String, fn: String, columnParam: ParameterSpec, returnType: TypeName, typeVars: List<TypeVariableName>) {
            val happy = CodeBlock.of(
                "aggregateRows(%T.%L, column.name, null).single().value as %T",
                AGG_FUNCTION, fn, returnType,
            )
            specs += FunSpec.builder(name).addAnnotation(suppress).addTypeVariables(typeVars)
                .addParameter(columnParam).returns(returnType)
                .addStatement("return %L", happy).build()
            specs += FunSpec.builder("${name}OrError").addAnnotation(suppress).addTypeVariables(typeVars)
                .addParameter(columnParam).returns(ENT_RESULT.parameterizedBy(returnType))
                .addCode(orErrorBody(happy)).build()
        }

        run {
            val t = comparableT()
            scalar("rawMin", "MIN", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
        }
        run {
            val t = comparableT()
            scalar("rawMax", "MAX", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
        }
        scalar("rawSum", "SUM", ParameterSpec.builder("column", INTEGRAL_COLUMN.parameterizedBy(entityClass, STAR)).build(), LONG.copy(nullable = true), emptyList())
        scalar("rawSum", "SUM", ParameterSpec.builder("column", FLOATING_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())
        scalar("rawAvg", "AVG", ParameterSpec.builder("column", NUMERIC_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())

        // Grouped terminal: two overloads per metric (GroupableColumn → key K,
        // NullableGroupableColumn → key K?). `valueColumn` is null for countBy.
        fun grouped(name: String, fn: String, valueColumn: ParameterSpec?, bucketValueType: TypeName, valueTypeVars: List<TypeVariableName>) {
            for (nullableKey in listOf(false, true)) {
                val k = TypeVariableName("K")
                val groupColType = (if (nullableKey) NULLABLE_GROUPABLE_COLUMN else GROUPABLE_COLUMN).parameterizedBy(entityClass, k)
                val keyType = if (nullableKey) k.copy(nullable = true) else k
                val listType = LIST.parameterizedBy(AGG_BUCKET.parameterizedBy(keyType, bucketValueType))
                val keyExpr = if (nullableKey) "groupBy.decodeKey(it.key)" else "groupBy.decodeKey(it.key)!!"
                val columnArg = if (valueColumn != null) "column.name" else "null"
                val params = listOfNotNull(ParameterSpec.builder("groupBy", groupColType).build(), valueColumn)
                val happy = CodeBlock.of(
                    "aggregateRows(%T.%L, %L, groupBy.name).map { %T(%L, it.value as %T) }",
                    AGG_FUNCTION, fn, columnArg, AGG_BUCKET, keyExpr, bucketValueType,
                )
                val typeVars = listOf(k) + valueTypeVars
                specs += FunSpec.builder(name).addAnnotation(suppress).addTypeVariables(typeVars)
                    .addParameters(params).returns(listType)
                    .addStatement("return %L", happy).build()
                specs += FunSpec.builder("${name}OrError").addAnnotation(suppress).addTypeVariables(typeVars)
                    .addParameters(params).returns(ENT_RESULT.parameterizedBy(listType))
                    .addCode(orErrorBody(happy)).build()
            }
        }

        grouped("rawCountBy", "COUNT", null, LONG, emptyList())
        run {
            val t = comparableT()
            grouped("rawMinBy", "MIN", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
        }
        run {
            val t = comparableT()
            grouped("rawMaxBy", "MAX", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
        }
        grouped("rawSumBy", "SUM", ParameterSpec.builder("column", INTEGRAL_COLUMN.parameterizedBy(entityClass, STAR)).build(), LONG.copy(nullable = true), emptyList())
        grouped("rawSumBy", "SUM", ParameterSpec.builder("column", FLOATING_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())
        grouped("rawAvgBy", "AVG", ParameterSpec.builder("column", NUMERIC_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())

        return specs
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
    /**
     * Shared explain wrapper: runs the interceptor chain for
     * [operationName], converts an interceptor `scope.reject(...)`
     * into a rejected [QueryPlan] via `QueryPlan.rejected(...)`
     * instead of throwing, and otherwise calls [bodyOnSuccess] with
     * the `spec` local in scope to produce the QueryPlan body. The
     * caller's body is responsible for the final `buildQueryPlan(...)`
     * (or `QueryPlan(driver.explainCount(...))`) expression.
     */
    private fun explainBody(operationName: String, bodyOnSuccess: CodeBlock): CodeBlock {
        val queryPlan = ClassName("entkt.runtime.query", "QueryPlan")
        return CodeBlock.builder()
            .add("return try {\n")
            .add(
                "  val spec = runReadInterceptors(%T.%L, %T.QUERY)\n",
                READ_OPERATION, operationName, ENT_OPERATION,
            )
            .add("  ")
            .add(bodyOnSuccess)
            .add("\n")
            .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
            .add("  %T.rejected(e.queryRejected)\n", queryPlan)
            .add("}\n")
            .build()
    }

    private fun buildRowShapedExplain(
        queryPlan: ClassName,
        name: String,
        terminalName: String,
    ): FunSpec {
        return FunSpec.builder(name)
            .addKdoc(
                "Return a [QueryPlan] describing the query shapes [$terminalName] would execute.\n" +
                "Interceptors run with operation = ALL. Eager edge subplans show structure,\n" +
                "not multiplicity — nested eager loads may execute once per parent group at\n" +
                "runtime. On interceptor rejection, returns a plan with `rejected = true`\n" +
                "carrying the rejection metadata; explain does NOT throw."
            )
            .returns(queryPlan)
            .addCode(explainBody("ALL", CodeBlock.of("buildQueryPlan(spec, true)")))
            .build()
    }

    private fun buildFirstShapedExplain(
        queryPlan: ClassName,
        name: String,
        terminalName: String,
    ): FunSpec {
        return FunSpec.builder(name)
            .addKdoc(
                "Return a [QueryPlan] describing the query shapes [$terminalName] would execute.\n" +
                "Interceptors run with operation = FIRST; limit operations are silent\n" +
                "no-ops for this terminal. Plan limit is hardwired to 1.\n" +
                "On interceptor rejection, returns a plan with `rejected = true`."
            )
            .returns(queryPlan)
            .addCode(explainBody("FIRST", CodeBlock.of("buildQueryPlan(spec.copy(limit = 1), true)")))
            .build()
    }

    private fun buildVisibleCountExplain(queryPlan: ClassName, name: String): FunSpec {
        return FunSpec.builder(name)
            .addKdoc(
                "Return a [QueryPlan] describing the query shape [visibleCount] would execute.\n" +
                "Interceptors run with operation = VISIBLE_COUNT; limit operations are\n" +
                "silent no-ops by contract. On interceptor rejection, returns a plan with\n" +
                "`rejected = true`."
            )
            .returns(queryPlan)
            .addCode(explainBody("VISIBLE_COUNT", CodeBlock.of("buildQueryPlan(spec, false)")))
            .build()
    }

    private fun buildExistsShapedExplain(
        queryPlan: ClassName,
        name: String,
        terminalName: String,
        operationName: String,
    ): FunSpec {
        // Runtime: `driver.query(TABLE, spec.predicates,
        // emptyList(), minOf(1, spec.limit ?: 1), spec.offset)`.
        // The orderBy is dropped (no point ordering for an
        // existence probe) but the caller's offset is preserved
        // (so `query { offset(5) }.rawExists()` skips the first
        // 5 rows and asks "is there a 6th?"). Mirror exactly so
        // the explain plan matches the driver call. Note the
        // limit mirrors the runtime's `minOf(1, spec.limit ?: 1)`
        // so a caller who pre-set `limit(0)` shows up as `limit
        // = 0` in the plan.
        return FunSpec.builder(name)
            .addKdoc(
                "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
                "Interceptors run with operation = $operationName; limit operations are\n" +
                "silent no-ops by contract. Plan mirrors the runtime exactly:\n" +
                "`orderBy = emptyList()` (existence probe doesn't order),\n" +
                "`limit = minOf(1, spec.limit ?: 1)` (usually 1, 0 when the caller\n" +
                "passed `query { limit(0) }`), and `offset = spec.offset` (caller's\n" +
                "offset is preserved). On interceptor rejection, returns a plan with\n" +
                "`rejected = true`."
            )
            .returns(queryPlan)
            .addCode(
                explainBody(
                    operationName,
                    CodeBlock.of("buildQueryPlan(spec.copy(orderBy = emptyList(), limit = minOf(1, spec.limit ?: 1)), false)"),
                ),
            )
            .build()
    }

    /**
     * Visible row-shaped explain — branches on `hasLoadPrivacy()`
     * so the plan matches the runtime driver call. On the privacy
     * path the runtime overfetches up to `visibleOverfetchLimit`,
     * filtering denied rows in Kotlin; on the no-privacy path the
     * caller's `spec.limit` passes through unchanged.
     */
    private fun buildVisibleRowShapedExplain(
        queryPlan: ClassName,
        name: String,
        terminalName: String,
        repoPropName: String,
    ): FunSpec {
        val body = CodeBlock.builder()
            .addStatement("val c = requireClient()")
            .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
            .addStatement("buildQueryPlan(spec, true)")
            .nextControlFlow("else")
            .addStatement("val cap = c.visibleOverfetchLimit")
            .addStatement("val scanLimit = minOf(spec.limit ?: cap, cap)")
            .addStatement("buildQueryPlan(spec.copy(limit = scanLimit), true)")
            .endControlFlow()
            .build()
        return FunSpec.builder(name)
            .addKdoc(
                "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
                "Interceptors run with operation = ALL. On the no-privacy fast path the\n" +
                "plan uses `spec.limit` directly; on the privacy path the plan uses\n" +
                "`minOf(spec.limit ?: cap, cap)` where `cap` is\n" +
                "`EntClientConfig.visibleOverfetchLimit`, matching the runtime overfetch\n" +
                "behavior. On interceptor rejection, returns a plan with `rejected = true`."
            )
            .returns(queryPlan)
            .addCode(explainBody("ALL", body))
            .build()
    }

    /**
     * `explainFirstVisibleOrNull` — on the no-privacy fast path
     * fetches a single row; on the privacy path scans up to
     * `visibleOverfetchLimit` to give the in-process filter
     * something to work with. Mirror that branching in explain.
     */
    private fun buildFirstVisibleExplain(
        queryPlan: ClassName,
        name: String,
        terminalName: String,
        repoPropName: String,
    ): FunSpec {
        val body = CodeBlock.builder()
            .addStatement("val c = requireClient()")
            .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
            .addStatement("buildQueryPlan(spec.copy(limit = 1), true)")
            .nextControlFlow("else")
            .addStatement("val cap = c.visibleOverfetchLimit")
            .addStatement("val scanLimit = minOf(spec.limit ?: cap, cap)")
            .addStatement("buildQueryPlan(spec.copy(limit = scanLimit), true)")
            .endControlFlow()
            .build()
        return FunSpec.builder(name)
            .addKdoc(
                "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
                "Interceptors run with operation = FIRST. On the no-privacy fast path the\n" +
                "plan uses `limit = 1`; on the privacy path the plan uses\n" +
                "`minOf(spec.limit ?: cap, cap)` where `cap` is\n" +
                "`EntClientConfig.visibleOverfetchLimit`, matching the runtime cap-bounded\n" +
                "scan. On interceptor rejection, returns a plan with `rejected = true`."
            )
            .returns(queryPlan)
            .addCode(explainBody("FIRST", body))
            .build()
    }

    /**
     * `explainVisibleExists` — same shape as the runtime: probe
     * with `limit = minOf(1, spec.limit ?: 1)` on the no-privacy
     * path; on the privacy path scan up to `visibleOverfetchLimit`
     * so the in-process filter can find a visible row.
     */
    private fun buildVisibleExistsExplain(
        queryPlan: ClassName,
        name: String,
        terminalName: String,
        repoPropName: String,
    ): FunSpec {
        val body = CodeBlock.builder()
            .addStatement("val c = requireClient()")
            .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
            // No-privacy fast path mirrors rawExists shape:
            // emptyList orderBy + caller offset preserved.
            .addStatement("buildQueryPlan(spec.copy(orderBy = emptyList(), limit = minOf(1, spec.limit ?: 1)), false)")
            .nextControlFlow("else")
            // Privacy path needs the order: the in-process filter
            // iterates rows in storage order, so spec.orderBy
            // matters. spec.offset is also preserved by the runtime.
            .addStatement("val cap = c.visibleOverfetchLimit")
            .addStatement("val scanLimit = minOf(spec.limit ?: cap, cap)")
            .addStatement("buildQueryPlan(spec.copy(limit = scanLimit), false)")
            .endControlFlow()
            .build()
        return FunSpec.builder(name)
            .addKdoc(
                "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
                "Interceptors run with operation = VISIBLE_EXISTS. The plan mirrors the\n" +
                "runtime driver call exactly:\n" +
                " - **No-privacy fast path**: `orderBy = emptyList()`,\n" +
                "   `limit = minOf(1, spec.limit ?: 1)` (so\n" +
                "   `query { limit(0) }.visibleExists()` shows `limit = 0`), and\n" +
                "   `offset = spec.offset` (caller offset preserved).\n" +
                " - **Privacy path**: `orderBy = spec.orderBy` (the in-process filter\n" +
                "   iterates rows in storage order, so order matters), `limit =\n" +
                "   minOf(spec.limit ?: cap, cap)` with\n" +
                "   `EntClientConfig.visibleOverfetchLimit`, and `offset = spec.offset`.\n" +
                "\n" +
                "On interceptor rejection, returns a plan with `rejected = true`."
            )
            .returns(queryPlan)
            .addCode(explainBody("VISIBLE_EXISTS", body))
            .build()
    }

    private fun buildRawCountExplain(queryPlan: ClassName, entityClass: ClassName): FunSpec {
        // explainRawCount uses driver.explainCount (a COUNT(*) plan)
        // rather than the row-fetch buildQueryPlan path.
        val body = CodeBlock.of(
            "%T(driver.explainCount(%T.TABLE, spec.predicates), annotations = spec.annotations)",
            queryPlan, entityClass,
        )
        return FunSpec.builder("explainRawCount")
            .addKdoc(
                "Return a [QueryPlan] describing the COUNT query [rawCount] would execute.\n" +
                "Interceptors run with operation = RAW_COUNT; predicate contributions show\n" +
                "up in the plan, limit operations are silent no-ops by contract.\n" +
                "On interceptor rejection, returns a plan with `rejected = true`."
            )
            .returns(queryPlan)
            .addCode(explainBody("RAW_COUNT", body))
            .build()
    }

    private fun buildExplainMethods(
        entityClass: ClassName,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): List<FunSpec> {
        val queryPlan = ClassName("entkt.runtime.query", "QueryPlan")
        val resolvableEdges = resolveExplainableEdges(schema, schemaNames)
        val hasEager = resolvableEdges.isNotEmpty()

        val methods = mutableListOf<FunSpec>()

        val repoPropName = pluralize(entityClass.simpleName.replaceFirstChar { it.lowercase() })

        // Row-shaped reads (ALL operation, eager edges included).
        // The non-visible variants always fetch spec.limit; the
        // visible variants apply the overfetch cap on the
        // privacy path. *OrThrow / *OrError pairs share the same
        // driver-call shape — the result-wrap differs (throw vs
        // Err) but the explain plan content is identical.
        methods += buildRowShapedExplain(queryPlan, "explainAllOrThrow", "allOrThrow")
        methods += buildRowShapedExplain(queryPlan, "explainAllOrError", "allOrError")
        methods += buildVisibleRowShapedExplain(queryPlan, "explainVisibleAll", "visibleAll", repoPropName)
        methods += buildVisibleRowShapedExplain(queryPlan, "explainVisibleAllOrError", "visibleAllOrError", repoPropName)

        // First-row reads. Non-visible variants fetch with limit 1.
        // firstVisibleOrNull on the privacy path scans up to the
        // overfetch cap rather than 1 — so its explain branches.
        methods += buildFirstShapedExplain(queryPlan, "explainFirstOrThrow", "firstOrThrow")
        methods += buildFirstShapedExplain(queryPlan, "explainFirstOrNull", "firstOrNull")
        methods += buildFirstShapedExplain(queryPlan, "explainFirstOrError", "firstOrError")
        methods += buildFirstVisibleExplain(queryPlan, "explainFirstVisibleOrNull", "firstVisibleOrNull", repoPropName)

        // Aggregate reads.
        methods += buildVisibleCountExplain(queryPlan, "explainVisibleCount")
        methods += buildExistsShapedExplain(queryPlan, "explainRawExists", "rawExists", "RAW_EXISTS")
        methods += buildVisibleExistsExplain(queryPlan, "explainVisibleExists", "visibleExists", repoPropName)
        methods += buildRawCountExplain(queryPlan, entityClass)

        // Internal buildQueryPlan helper. Takes the post-interceptor
        // FrozenQuerySpec rather than raw limit/offset so the explain
        // output reflects every predicate, limit, offset, and
        // annotation contribution from the chain. `internal` (not
        // private) so a parent query's eager-explain block can call
        // it on a sibling *Query — that's how eager-load plans get
        // built with the right EAGER_LOAD-step spec instead of
        // re-running the sub-query through its own root explain().
        // [junctionExplain] is non-null only on the M2M eager path
        // where the parent block has computed the junction table's
        // explain (junction is internal-only, not subject to
        // interceptors).
        val helper = FunSpec.builder("buildQueryPlan")
            .addModifiers(KModifier.INTERNAL)
            // Spec is typed in this query's entity scope; every layer above the driver
            // call carries E through.
            .addParameter("spec", FROZEN_QUERY_SPEC.parameterizedBy(entityClass))
            .addParameter("includeEager", BOOLEAN)
            .addParameter(
                ParameterSpec.builder("junctionExplain", QUERY_EXPLANATION.copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .returns(queryPlan)
            .addStatement(
                "val root = driver.explainQuery(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)",
                entityClass,
            )

        if (!hasEager) {
            helper.addStatement(
                "return %T(root, junctionQuery = junctionExplain, annotations = spec.annotations)",
                queryPlan,
            )
        } else {
            helper.addStatement(
                "if (!includeEager) return %T(root, junctionQuery = junctionExplain, annotations = spec.annotations)",
                queryPlan,
            )
            helper.addStatement("val edges = mutableMapOf<String, %T>()", queryPlan)
            for (info in resolvableEdges) {
                helper.addCode(buildEagerExplainBlock(info, queryPlan, entityClass))
            }
            helper.addStatement(
                "return %T(root, junctionQuery = junctionExplain, eagerQueries = edges, annotations = spec.annotations)",
                queryPlan,
            )
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
     * runtime EAGER_LOAD flow in `emit*EagerBlock` so the plan
     * reflects what actually runs:
     *
     * 1. Set up the sub-query's traversal context (sourceEntity /
     *    edgeName / path) — same as runtime.
     * 2. Run `subQuery.runReadInterceptors(EAGER_LOAD, QUERY,
     *    listOf(IN-predicate))`. The target's interceptors fire with
     *    `context.operation == EAGER_LOAD` (not ALL as the previous
     *    `subQuery.explain()` route would have done), and the
     *    resulting spec contains every target-side interceptor's
     *    predicates + annotations.
     * 3. Hand the spec to the sub-query's own `buildQueryPlan` so
     *    the rendered plan shows the post-interceptor shape, plus
     *    recursively-walked nested eager edges via the same path.
     *
     * For M2M edges the junction-table explain is computed in this
     * block (junction tables aren't entities and don't have
     * interceptors), then handed to the sub-query's `buildQueryPlan`
     * as the optional junction explain.
     *
     * The IN predicate uses [QueryExplanation.EXPLAIN_PLACEHOLDER]
     * as the value so the driver renders the actual column name
     * (e.g. `"author_id" IN (?)`) rather than collapsing an empty
     * IN list to FALSE.
     */
    private fun buildEagerExplainBlock(
        info: ExplainableEdge,
        queryPlan: ClassName,
        sourceClass: ClassName,
    ): CodeBlock {
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        val body = CodeBlock.builder()
        body.beginControlFlow("%L?.let { subQuery ->", info.eagerPropName)
        // Mirror runtime emit*EagerBlock context setup so the
        // sub-query's interceptors see the right QueryContext.
        // Cross-class write goes through the @EntktInternal seeder.
        body.addStatement(
            "subQuery.seedEdgeTraversal(%T::class, %S, this.traversalPath + %T(%T::class, %S, %T::class))",
            sourceClass, info.edge.name, edgeStepClass, sourceClass, info.edge.name, info.targetClass,
        )

        // A rejected eager sub-explain becomes a rejected entry
        // in `edges` rather than failing the whole parent plan —
        // the root + sibling eager subplans still appear, the
        // caller can inspect `plan.eagerQueries["X"]?.rejected` to
        // see which step rejected. Explain rejection lives on the
        // plan, not as an exception.
        val queryPlanLocal = ClassName("entkt.runtime.query", "QueryPlan")
        if (info.edge.kind is EdgeKind.ManyToMany) {
            // M2M: junction table explain stands alone (no
            // interceptors on the junction), then the target-table
            // plan uses the post-EAGER_LOAD spec with an IN on "id".
            // Junction-table query has no entity scope (junctions are
            // internal storage). Predicate.Leaf<Any> renders the same
            // structural fields and erases at the driver call.
            body.addStatement(
                "val junctionExplain = driver.explainQuery(%S, listOf(%T.Leaf<%T>(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER)), emptyList(), null, null)",
                info.join.junctionTable, PREDICATE, Any::class.asClassName(), info.join.junctionSourceColumn, OP, QUERY_EXPLANATION,
            )
            body.add("edges[%S] = try {\n", info.edge.name)
            body.add(
                "  val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf<%T>(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER)))\n",
                READ_OPERATION, ENT_OPERATION, PREDICATE, info.targetClass, "id", OP, QUERY_EXPLANATION,
            )
            // Strip limit/offset before handing to buildQueryPlan:
            // the runtime eager fetch uses null/null limit/offset
            // and paginates per-group in Kotlin (see
            // emit*EagerBlock), so passing spec.limit/spec.offset
            // here would render LIMIT/OFFSET in the explain that
            // doesn't match what the driver actually receives at
            // runtime. Predicates / orderBy / annotations DO flow
            // through accurately.
            body.add(
                "  subQuery.buildQueryPlan(subSpec.copy(limit = null, offset = null), includeEager = true, junctionExplain = junctionExplain)\n",
            )
            body.add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
            body.add("  %T.rejected(e.queryRejected)\n", queryPlanLocal)
            body.add("}\n")
        } else {
            // Direct edge: single query with IN on the join column.
            // hasMany/hasOne: IN on targetColumn (FK on target side).
            // belongsTo: IN on targetColumn ("id" on target side).
            body.add("edges[%S] = try {\n", info.edge.name)
            body.add(
                "  val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf<%T>(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER)))\n",
                READ_OPERATION, ENT_OPERATION, PREDICATE, info.targetClass, info.join.targetColumn, OP, QUERY_EXPLANATION,
            )
            // See M2M branch above for why limit/offset are
            // stripped here.
            body.add(
                "  subQuery.buildQueryPlan(subSpec.copy(limit = null, offset = null), includeEager = true)\n",
            )
            body.add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
            body.add("  %T.rejected(e.queryRejected)\n", queryPlanLocal)
            body.add("}\n")
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
        // Structural predicates pass in typed to this query's entity
        // scope. The deferred-source-step bridge (target-scoped) and
        // the eager-load `id IN (...)` leaf both produce
        // Predicate<EntityClass>, so the combined list is uniformly
        // typed. QuerySpecBuilder's erased-storage signatures accept
        // it via standard List<out T> variance.
        val predicateForEntity = predicateClass.parameterizedBy(entityClass)
        val structuralListType = List::class.asClassName().parameterizedBy(predicateForEntity)
        return FunSpec.builder("runReadInterceptors")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("operation", readOp)
            .addParameter("entOperation", ENT_OPERATION)
            .addParameter(
                ParameterSpec.builder("extraStructural", structuralListType)
                    .defaultValue("emptyList()")
                    .build()
            )
            // Return type is typed in this query's entity scope; the
            // FrozenQuerySpec produced by `runReadInterceptors` carries
            // `List<Predicate<EntityClass>>` so the walker rewrite and
            // explain plan builders don't need unchecked casts.
            .returns(FROZEN_QUERY_SPEC.parameterizedBy(entityClass))
            .addCode(
                CodeBlock.builder()
                    .addStatement("val c = requireClient()")
                    .addStatement("val privacy = c.currentPrivacyContext()")
                    // Resolve the deferred source step (if any) at
                    // terminal time. The lambda runs the source
                    // entity's interceptor chain — a source-step
                    // rejection throws EntQueryRejectedException
                    // here, which the terminal's own try/catch
                    // (allOrError / firstOrError / byIdOrError /
                    // *OrError aggregate variants) converts to
                    // `Err(QueryRejected)`. Eager invocation at
                    // queryX() time would have raised the throw
                    // before the *OrError terminal could catch it.
                    .addStatement("val sourceResult = deferredSourceStep?.invoke()")
                    // Source annotations seed the builder so they
                    // surface on the final terminal's
                    // QueryPlan.annotations — interceptors at this
                    // step can overwrite via scope.addAnnotation
                    // (last-writer-wins).
                    .addStatement(
                        "val initialAnnotations = sourceResult?.annotations ?: emptyMap<%T, %T>()",
                        String::class.asClassName(),
                        String::class.asClassName(),
                    )
                    // Bridging predicate from the source step
                    // (HasEdgeWith / HasM2MEdgeFrom / HasEdge) goes
                    // in as STRUCTURAL alongside caller-passed
                    // extras (byId's id leaf, eager-load's IN clause).
                    .addStatement(
                        "val structural = listOfNotNull(sourceResult?.bridge) + extraStructural",
                    )
                    // rootEntity walks back along the traversal
                    // path; if no traversal context, this query IS
                    // the root. Otherwise the first EdgeStep's
                    // source is the chain's origin.
                    .addStatement(
                        "val root = traversalPath.firstOrNull()?.source ?: %T::class",
                        entityClass,
                    )
                    // QuerySpecBuilder<EntityClass> typed at construction
                    // — the typed `predicates` / `orderFields` from this
                    // query class feed in without casts.
                    .add("val builder = %T<%T>(\n", QUERY_SPEC_BUILDER, entityClass)
                    .add("  table = %T.TABLE,\n", entityClass)
                    .add("  entity = %T::class,\n", entityClass)
                    .add("  callerPredicates = predicates,\n")
                    .add("  structuralPredicates = structural,\n")
                    .add("  orderBy = orderFields,\n")
                    .add("  callerLimit = queryLimit,\n")
                    .add("  offset = queryOffset,\n")
                    .add("  flags = emptySet(),\n")
                    .add("  initialAnnotations = initialAnnotations,\n")
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
                    // Skip-list uses identity (`===`), not equality
                    // (`==`). A caller-authored predicate that happens
                    // to be structurally equal to a framework-injected
                    // structural (e.g. an application HasEdgeWith that
                    // matches a traversal-bridging HasEdgeWith by
                    // value) must still be walked through the
                    // edge-predicate processor.
                    // skipWalk is typed in this query's entity scope so
                    // the runEdgePredicateInterceptors call below can
                    // forward the typed `frozen.predicates.map { ... }`
                    // entries through the typed walker without casting.
                    .addStatement(
                        "val skipWalk: List<%T<%T>> = listOfNotNull(sourceResult?.bridge) + extraStructural",
                        predicateClass, entityClass,
                    )
                    // Walker accumulator: each edge-predicate target
                    // step's annotations bubble up into this map and
                    // merge into the outer FrozenQuerySpec below.
                    // Outer-step annotations (already on
                    // `frozen.annotations`) win on key conflicts —
                    // closer-to-caller wins, same direction as
                    // traversal-source-vs-terminal merge.
                    .addStatement("val edgeAnnotations: %T<%T, %T> = mutableMapOf()",
                        ClassName("kotlin.collections", "MutableMap"),
                        String::class.asClassName(),
                        String::class.asClassName(),
                    )
                    // frozen.predicates is typed `List<Predicate<EntityClass>>`
                    // because FrozenQuerySpec<E> carries E through.
                    // No cast needed at the walker
                    // call.
                    .addStatement(
                        "val walked = frozen.predicates.map { p -> if (skipWalk.any { it === p }) p else runEdgePredicateInterceptors(p, traversalPath, edgeAnnotations) }",
                    )
                    .addStatement(
                        "return frozen.copy(predicates = walked, annotations = edgeAnnotations + frozen.annotations)",
                    )
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
     * interceptors via.
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
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        val mutableMap = ClassName("kotlin.collections", "MutableMap")
        val body = CodeBlock.builder()
        body.addStatement("val c = requireClient()")
        // Recursion guard: cap the edge-predicate walker at
        // EDGE_PREDICATE_MAX_DEPTH steps so an interceptor cycle
        // (e.g. Post adds Post.author.has() AND User adds
        // User.posts.has(), or any longer cycle) surfaces as a
        // clear error rather than a StackOverflowError. Generous
        // limit so legitimate deep traversal trees (rare in
        // practice) still work; pathological cycles trip it
        // immediately because each level appends an EdgeStep to
        // parentPath.
        body.add(
            "check(parentPath.size <= %T.EDGE_PREDICATE_MAX_DEPTH) {\n",
            INTERCEPTOR_ENGINE,
        )
        body.add("  %P\n",
            "edge-predicate interceptor recursion exceeded depth " +
                "\${entkt.runtime.query.InterceptorEngine.EDGE_PREDICATE_MAX_DEPTH} on path " +
                "\${parentPath.joinToString(\" → \") { \"\${it.source.simpleName}.\${it.edgeName}\" }}. " +
                "Likely cause: interceptors on two entities each add a HasEdge[With] predicate that " +
                "references back to the other (e.g. Post adds Post.author.has(), User adds " +
                "User.posts.has()). Fix the interceptor cycle or bump InterceptorEngine.EDGE_PREDICATE_MAX_DEPTH.",
        )
        body.add("}\n")
        body.add("return when (predicate) {\n")
        body.add(
            "  is %T.And<%T> -> %T.And(runEdgePredicateInterceptors(predicate.left, parentPath, edgeAnnotations), runEdgePredicateInterceptors(predicate.right, parentPath, edgeAnnotations))\n",
            predicateClass, entityClass, predicateClass,
        )
        body.add(
            "  is %T.Or<%T> -> %T.Or(runEdgePredicateInterceptors(predicate.left, parentPath, edgeAnnotations), runEdgePredicateInterceptors(predicate.right, parentPath, edgeAnnotations))\n",
            predicateClass, entityClass, predicateClass,
        )
        // HasEdgeWith dispatch. Predicate.HasEdgeWith<E, Target>.inner
        // is typed Predicate<Target>, but the smart-cast lands at
        // HasEdgeWith<E, *> so .inner is Predicate<*>. The edge-name
        // serves as the runtime witness for recovering Target. Each
        // branch knows its target statically (from the schema), does
        // an unchecked cast inside
        // the branch, and rebuilds a typed HasEdgeWith for the
        // candidate before returning.
        body.add("  is %T.HasEdgeWith<%T, *> -> when (predicate.edge) {\n", predicateClass, entityClass)
        for (edge in schema.edges()) {
            val targetName = schemaNames[edge.target] ?: continue
            val targetClass = ClassName(packageName, targetName)
            val targetQueryClass = ClassName(packageName, "${targetName}Query")
            // The edge name in HasEdgeWith corresponds to an edge
            // on THIS query's entity (the source). Dispatch by
            // this entity's outgoing edge names.
            body.add("    %S -> {\n", edge.name)
            // Reuse this source query's driver — the target
            // query never touches it on this code path (we only
            // call runReadInterceptors, which is pure transform),
            // but its primary constructor requires one.
            body.add("      val targetQ = %T(driver, c)\n", targetQueryClass)
            // Edge-name-validated unchecked cast: the schema declares
            // this edge with this exact target, so predicate.inner
            // (typed Predicate<*>) is in fact Predicate<TargetEntity>
            // — the runtime witness is the edge-name match.
            body.add("      @Suppress(\"UNCHECKED_CAST\")\n")
            body.add(
                "      val typedInner = predicate.inner as %T<%T>\n",
                predicateClass, targetClass,
            )
            // Use the public DSL (target.where) rather than writing the
            // backing list directly, preserving encapsulation.
            body.add("      targetQ.where(typedInner)\n")
            // Cross-class write through the @EntktInternal seeder.
            body.add(
                "      targetQ.seedEdgeTraversal(%T::class, predicate.edge, parentPath + %T(%T::class, predicate.edge, %T::class))\n",
                entityClass, edgeStepClass, entityClass, targetClass,
            )
            body.add(
                "      val spec = targetQ.runReadInterceptors(%T.EDGE_PREDICATE, %T.QUERY)\n",
                READ_OPERATION, ENT_OPERATION,
            )
            // Bubble up target-step annotations into the outer
            // accumulator so observability sees them on the outer
            // QueryPlan. Source-of-truth merge rule (outer wins on
            // collision) is applied at the outer's
            // runReadInterceptors via `edgeAnnotations + frozen.annotations`.
            body.add("      edgeAnnotations.putAll(spec.annotations)\n")
            // spec.predicates is typed `List<Predicate<Target>>` (the
            // target's runReadInterceptors returns FrozenQuerySpec<Target>),
            // so the reduce produces a typed Predicate<Target> directly.
            body.add(
                "      val combinedInner = spec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) } ?: typedInner\n",
                predicateClass,
            )
            body.add(
                "      %T.HasEdgeWith<%T, %T>(predicate.edge, combinedInner)\n",
                predicateClass, entityClass, targetClass,
            )
            body.add("    }\n")
        }
        body.add("    else -> predicate\n")
        body.add("  }\n")
        // HasEdge (no inner): if any target interceptor adds
        // predicates, upgrade to HasEdgeWith with the interceptor-
        // contributed predicates as the inner. If interceptors add
        // nothing, keep as HasEdge. Important for soft-delete on the
        // target — `User.articles.has()` must still filter out
        // soft-deleted articles, otherwise the existence check is
        // wrong.
        body.add("  is %T.HasEdge<%T> -> when (predicate.edge) {\n", predicateClass, entityClass)
        for (edge in schema.edges()) {
            val targetName = schemaNames[edge.target] ?: continue
            val targetClass = ClassName(packageName, targetName)
            val targetQueryClass = ClassName(packageName, "${targetName}Query")
            body.add("    %S -> {\n", edge.name)
            body.add("      val targetQ = %T(driver, c)\n", targetQueryClass)
            // Cross-class write through the @EntktInternal seeder.
            body.add(
                "      targetQ.seedEdgeTraversal(%T::class, predicate.edge, parentPath + %T(%T::class, predicate.edge, %T::class))\n",
                entityClass, edgeStepClass, entityClass, targetClass,
            )
            body.add(
                "      val spec = targetQ.runReadInterceptors(%T.EDGE_PREDICATE, %T.QUERY)\n",
                READ_OPERATION, ENT_OPERATION,
            )
            // Bubble up target-step annotations even when the
            // walker upgrades HasEdge → HasEdgeWith (or keeps as
            // HasEdge if interceptors added nothing).
            body.add("      edgeAnnotations.putAll(spec.annotations)\n")
            // spec.predicates is already typed in Target.
            body.add(
                "      val combined = spec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }\n",
                predicateClass,
            )
            body.add(
                "      if (combined != null) %T.HasEdgeWith<%T, %T>(predicate.edge, combined) else predicate\n",
                predicateClass, entityClass, targetClass,
            )
            body.add("    }\n")
        }
        body.add("    else -> predicate\n")
        body.add("  }\n")
        body.add("  else -> predicate\n")
        body.add("}\n")

        // Walker input/output are typed in this query's entity scope.
        // The walker recurses through a Predicate<EntityClass> tree,
        // rewriting edge-predicate sub-nodes (which carry their own
        // Target scope) via per-edge branches that use the
        // edge-name-validated unchecked-cast pattern above.
        val predicateForThis = predicateClass.parameterizedBy(entityClass)
        return FunSpec.builder("runEdgePredicateInterceptors")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("predicate", predicateForThis)
            .addParameter(
                "parentPath",
                List::class.asClassName().parameterizedBy(edgeStepClass),
            )
            .addParameter(
                "edgeAnnotations",
                mutableMap.parameterizedBy(
                    String::class.asClassName(),
                    String::class.asClassName(),
                ),
            )
            .returns(predicateForThis)
            .addCode(body.build())
            .build()
    }

    private fun buildWhere(queryClass: ClassName, predicateForEntity: TypeName): FunSpec {
        // Marked `override` because `EdgePredicateScope<E>.where(Predicate<E>)`
        // declares this signature. Covariant return type — the
        // interface declares `EdgePredicateScope<E>` and the
        // concrete query class returns its own concrete type for
        // fluent chaining outside `has { }` blocks.
        return FunSpec.builder("where")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("predicate", predicateForEntity)
            .returns(queryClass)
            .addStatement("this.predicates = this.predicates + predicate")
            .addStatement("return this")
            .build()
    }

    private fun buildOrderBy(queryClass: ClassName, orderFieldForEntity: TypeName): FunSpec {
        return FunSpec.builder("orderBy")
            .addParameter("field", orderFieldForEntity)
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
            // syntax error one layer removed from the caller — loud-fail
            // here instead.
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
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
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
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
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
        val fk = edgeFks.find { it.columnName == join.sourceColumn }
        val fkPropName = fk?.propertyName ?: toCamelCase(join.sourceColumn)
        // A required FK is a non-null property, so the safe-call below would be
        // redundant (and Kotlin warns). Unknown → treat as nullable (safe).
        val fkRequired = fk?.required ?: false

        body.beginControlFlow("%L?.let { subQuery ->", eagerPropName)
        body.addStatement("val fkValues = entities.mapNotNull { it.%L }.distinct()", fkPropName)
        body.beginControlFlow("if (fkValues.isNotEmpty())")
        emitEagerSubquerySetup(body, edgeName, sourceClass, targetClass)
        // Fetch all matching targets — limit/offset is meaningless for to-one.
        body.addStatement(
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf<%T>(%S, %T.IN, fkValues)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
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
        if (fkRequired) {
            // Non-null FK: map lookup directly (still nullable — the target may
            // have been filtered by eager LOAD privacy).
            body.addStatement(
                "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = targetMap[entity.%L])) }",
                edgePropName, fkPropName,
            )
        } else {
            body.addStatement(
                "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = entity.%L?.let { targetMap[it] })) }",
                edgePropName, fkPropName,
            )
        }
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
        // Junction-table query has no entity scope.
        body.addStatement(
            "val junctionRows = driver.query(%S, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)), emptyList(), null, null)",
            join.junctionTable, PREDICATE, Any::class.asClassName(), join.junctionSourceColumn, OP,
        )
        body.beginControlFlow("if (junctionRows.isNotEmpty())")
        body.addStatement(
            "val targetIds = junctionRows.map { it[%S] }.distinct()",
            join.junctionTargetColumn,
        )
        emitEagerSubquerySetup(body, edgeName, sourceClass, targetClass)
        // Fetch all matching targets — limit/offset are applied per group below.
        body.addStatement(
            "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, %T.QUERY, listOf(%T.Leaf<%T>(%S, %T.IN, targetIds)))",
            READ_OPERATION, ENT_OPERATION, PREDICATE, targetClass, "id", OP,
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
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        // Cross-class write through the @EntktInternal seeder
        // (traversal context fields are `private` on the target
        // query class).
        body.addStatement(
            "subQuery.seedEdgeTraversal(%T::class, %S, this.traversalPath + %T(%T::class, %S, %T::class))",
            sourceClass, edgeName, edgeStepClass, sourceClass, edgeName, targetClass,
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
    private fun buildCombinedPredicate(predicateForEntity: TypeName): FunSpec {
        return FunSpec.builder("combinedPredicate")
            .addModifiers(KModifier.OVERRIDE)
            .returns(predicateForEntity.copy(nullable = true))
            .addStatement(
                "return predicates.reduceOrNull { acc, p -> %T.And(acc, p) }",
                predicateClass,
            )
            .build()
    }

    /**
     * Generate `snapshotForTraversal(driver, client): ThisQuery` on the
     * query class.
     *
     * Generated traversal methods (`queryX()` / `queryX()` M2M variants)
     * need to seed a fresh source-query instance with the current
     * query's state so subsequent mutations on `this` don't leak into
     * the bridge. Since `predicates` / `orderFields` / traversal
     * context / `deferredSourceStep` are all `private`, the only place
     * they can be cross-instance-copied is from inside the class
     * itself (same-class private access is unaffected by instance
     * boundaries in Kotlin).
     *
     * `@EntktInternal` on the method blocks application callers from
     * invoking it without explicit opt-in — generated traversal code
     * lives in `@file:OptIn(EntktInternal::class)` files, so the call
     * compiles there.
     */
    private fun buildSnapshotForTraversal(queryClass: ClassName, clientClass: ClassName): FunSpec {
        return FunSpec.builder("snapshotForTraversal")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter("driver", DRIVER)
            .addParameter(
                ParameterSpec.builder("client", clientClass.copy(nullable = true))
                    .build(),
            )
            .returns(queryClass)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(driver, client).also {\n", queryClass)
                    .add("  it.predicates = this.predicates\n")
                    .add("  it.orderFields = this.orderFields\n")
                    .add("  it.queryLimit = this.queryLimit\n")
                    .add("  it.queryOffset = this.queryOffset\n")
                    .add("  it.traversalSourceEntity = this.traversalSourceEntity\n")
                    .add("  it.traversalEdgeName = this.traversalEdgeName\n")
                    .add("  it.traversalPath = this.traversalPath\n")
                    .add("  it.deferredSourceStep = this.deferredSourceStep\n")
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * Generate `seedEdgeTraversal(sourceEntity, edgeName, path)` on
     * the query class. The three traversal-context fields are
     * `private` — spoofing source / edge / path from app code
     * would confuse the interceptor's QueryContext view. Cross-
     * class write needs a method; the method is `@EntktInternal
     * internal` so application code can't call it without explicit
     * `@OptIn`. Used by the eager-load setup, the edge-predicate
     * walker, and queryX traversal methods.
     */
    private fun buildSeedEdgeTraversal(): FunSpec {
        return FunSpec.builder("seedEdgeTraversal")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter(
                "sourceEntity",
                ClassName("kotlin.reflect", "KClass")
                    .parameterizedBy(com.squareup.kotlinpoet.STAR),
            )
            .addParameter("edgeName", String::class.asClassName())
            .addParameter(
                "path",
                List::class.asClassName().parameterizedBy(ClassName("entkt.runtime.query", "EdgeStep")),
            )
            .addStatement("this.traversalSourceEntity = sourceEntity")
            .addStatement("this.traversalEdgeName = edgeName")
            .addStatement("this.traversalPath = path")
            .build()
    }

    /**
     * Generate `setDeferredSourceStep(step)` seeder on the query
     * class. The `deferredSourceStep` field itself is `private` —
     * clearing it would remove the structural traversal-bridge
     * constraint and let queries leak across the boundary. Cross-
     * class write needs a method; the method is
     * `@EntktInternal internal` so application code can't call it
     * without explicit `@OptIn`. Generated traversal code (in
     * `@file:OptIn(EntktInternal::class)` files) calls it freely.
     */
    private fun buildSetDeferredSourceStep(entityClass: ClassName): FunSpec {
        val lambdaType = LambdaTypeName.get(
            receiver = null,
            returnType = ClassName("entkt.runtime.query", "TraversalSourceResult")
                .parameterizedBy(entityClass),
        ).copy(nullable = true)
        return FunSpec.builder("setDeferredSourceStep")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter("step", lambdaType)
            .addStatement("this.deferredSourceStep = step")
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
        val sourceQueryClass = ClassName(packageName, "${sourceName}Query")
        val targetEntityClass = ClassName(packageName, targetName)
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"
        val sourceTable = source.tableName
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        val traversalSourceResult = ClassName("entkt.runtime.query", "TraversalSourceResult")

        return FunSpec.builder(methodName)
            // Defaulted receiver block matching the repository / index
            // `query { ... }` helpers: it configures the *target* query
            // and runs after all traversal seeding, so it is exactly
            // equivalent to chaining `.where(...)` etc. on the returned
            // query. The source snapshot below is taken before the
            // block runs, so the block cannot leak state into the
            // bridge predicate.
            .addParameter(
                ParameterSpec.builder(
                    "block",
                    LambdaTypeName.get(receiver = targetQueryClass, returnType = UNIT),
                ).defaultValue("{}").build(),
            )
            .returns(targetQueryClass)
            // Construct the target query and stash a deferred
            // source-step lambda — the source's interceptor chain
            // does NOT fire here; it fires at the terminal's call
            // site inside the terminal's try/catch (see KDoc on
            // `deferredSourceStep`). This is what lets
            // `.queryX().allOrError()` catch source-step rejections
            // as `Err(QueryRejected)` instead of having queryX()
            // throw before allOrError() can run.
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            // Cross-class write through the @EntktInternal seeder.
            .addStatement(
                "target.seedEdgeTraversal(%T::class, %S, this.traversalPath + %T(%T::class, %S, %T::class))",
                sourceEntityClass, edge.name, edgeStepClass, sourceEntityClass, edge.name, targetEntityClass,
            )
            // Snapshot source state at queryX() time into a fresh
            // source-Query instance so the deferred lambda is
            // immune to later mutations on `this`. Without the
            // snapshot, `users.queryPosts(); users.where(...);
            // posts.allOrThrow()` would let the post-queryX where
            // leak into posts' bridge predicate, which contradicts
            // the pre-deferral snapshot-at-construction semantics.
            // List / nullable fields are immutable values, so copying
            // the references is sufficient: source mutators reassign
            // the reference on `this`, not on the snapshot. The copy
            // is delegated to the generated `snapshotForTraversal`
            // method, which lives inside the source query class and
            // can access the private backing fields via same-class
            // private access.
            .addStatement("val sourceQ = this.snapshotForTraversal(driver, client)")
            .addCode(
                CodeBlock.builder()
                    // Cross-class write goes through the @EntktInternal
                    // seeder so application code can't clear / overwrite
                    // deferredSourceStep without an explicit opt-in (it
                    // is `private` on the target class).
                    .add("target.setDeferredSourceStep {\n")
                    .add(
                        "  val sourceSpec = sourceQ.runReadInterceptors(%T.EDGE_TRAVERSAL, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    // sourceSpec is FrozenQuerySpec<SourceEntity>; its
                    // predicates are typed `List<Predicate<SourceEntity>>`
                    // — no cast needed.
                    .add(
                        "  val parent: %T<%T>? = sourceSpec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }\n",
                        predicateClass, sourceEntityClass, predicateClass,
                    )
                    // M2M bridge: HasM2MEdgeFrom<Target, Source>(...).
                    // The candidate is the M2M target; the sourceFilter
                    // constrains rows in the source table.
                    .add("  %T<%T>(\n", traversalSourceResult, targetEntityClass)
                    .add(
                        "    bridge = %T.HasM2MEdgeFrom<%T, %T>(%S, %S, parent),\n",
                        predicateClass,
                        targetEntityClass, sourceEntityClass,
                        sourceTable,
                        edge.name,
                    )
                    .add("    annotations = sourceSpec.annotations,\n")
                    .add("  )\n")
                    .add("}\n")
                    .build()
            )
            .addStatement("return target.apply(block)")
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
        val sourceQueryClass = ClassName(packageName, "${sourceName}Query")
        val targetEntityClass = ClassName(packageName, targetName)
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        val traversalSourceResult = ClassName("entkt.runtime.query", "TraversalSourceResult")

        return FunSpec.builder(methodName)
            // Defaulted receiver block matching the repository / index
            // `query { ... }` helpers: it configures the *target* query
            // and runs after all traversal seeding, so it is exactly
            // equivalent to chaining `.where(...)` etc. on the returned
            // query. The source snapshot below is taken before the
            // block runs, so the block cannot leak state into the
            // bridge predicate.
            .addParameter(
                ParameterSpec.builder(
                    "block",
                    LambdaTypeName.get(receiver = targetQueryClass, returnType = UNIT),
                ).defaultValue("{}").build(),
            )
            .returns(targetQueryClass)
            // Construct the target query and stash a deferred
            // source-step lambda — the source's interceptor chain
            // does NOT fire here; it fires at the terminal's call
            // site inside the terminal's try/catch (see KDoc on
            // `deferredSourceStep`). This lets
            // `.queryX().allOrError()` catch source-step rejections
            // as `Err(QueryRejected)` instead of having queryX()
            // throw before allOrError() can run.
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            // Cross-class write through the @EntktInternal seeder.
            .addStatement(
                "target.seedEdgeTraversal(%T::class, %S, this.traversalPath + %T(%T::class, %S, %T::class))",
                sourceEntityClass, edge.name, edgeStepClass, sourceEntityClass, edge.name, targetEntityClass,
            )
            // Snapshot source state at queryX() time into a fresh
            // source-Query instance so the deferred lambda is
            // immune to later mutations on `this`. Without the
            // snapshot, `users.queryPosts(); users.where(...);
            // posts.allOrThrow()` would let the post-queryX where
            // leak into posts' bridge predicate, which contradicts
            // the pre-deferral snapshot-at-construction semantics.
            // List / nullable fields are immutable values, so copying
            // the references is sufficient: source mutators reassign
            // the reference on `this`, not on the snapshot. The copy
            // is delegated to the generated `snapshotForTraversal`
            // method, which lives inside the source query class and
            // can access the private backing fields via same-class
            // private access.
            .addStatement("val sourceQ = this.snapshotForTraversal(driver, client)")
            .addCode(
                CodeBlock.builder()
                    // Cross-class write goes through the @EntktInternal
                    // seeder so application code can't clear / overwrite
                    // deferredSourceStep without an explicit opt-in (it
                    // is `private` on the target class).
                    .add("target.setDeferredSourceStep {\n")
                    .add(
                        "  val sourceSpec = sourceQ.runReadInterceptors(%T.EDGE_TRAVERSAL, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    // sourceSpec is FrozenQuerySpec<SourceEntity>; its
                    // predicates are typed `List<Predicate<SourceEntity>>`
                    // — no cast needed.
                    .add(
                        "  val parent: %T<%T>? = sourceSpec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }\n",
                        predicateClass, sourceEntityClass, predicateClass,
                    )
                    // Bridge is target-scoped: HasEdgeWith<Target, Source>
                    // (the inner predicate is on Source). The candidate
                    // entity is the target. The walker's edge-name witness +
                    // unchecked cast soundness applies symmetrically
                    // to the construction site here — codegen knows
                    // both Source and Target by schema.
                    .add(
                        "  val bridge: %T<%T> = if (parent != null) %T.HasEdgeWith<%T, %T>(%S, parent) else %T.HasEdge<%T>(%S)\n",
                        predicateClass, targetEntityClass,
                        predicateClass,
                        targetEntityClass, sourceEntityClass,
                        inverse.name,
                        predicateClass,
                        targetEntityClass,
                        inverse.name,
                    )
                    .add(
                        "  %T<%T>(bridge = bridge, annotations = sourceSpec.annotations)\n",
                        traversalSourceResult, targetEntityClass,
                    )
                    .add("}\n")
                    .build()
            )
            .addStatement("return target.apply(block)")
            .build()
    }
}

internal fun toPascalCase(snakeCase: String): String =
    toCamelCase(snakeCase).replaceFirstChar { it.uppercase() }
