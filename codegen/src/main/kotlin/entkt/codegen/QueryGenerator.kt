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
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val EDGE_QUERY = ClassName("entkt.query", "EdgeQuery")
private val DRIVER = ClassName("entkt.runtime", "Driver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP = ClassName("entkt.query", "Op")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val PRIVACY_DENIED = ClassName("entkt.runtime", "PrivacyDeniedException")

class QueryGenerator(
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
                if (edge.through != null) {
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
            .apply {
                if (hasEdges) {
                    addFunction(buildLoadEdges(entityClass, schema, schemaNames))
                }
            }
            .addFunction(buildRequireClient(schemaName))
            .addFunction(buildAll(schemaName, entityClass, hasEdges))
            .addFunction(buildFirstOrNull(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleCount(schemaName, entityClass))
            .addFunction(buildRawCount(schemaName, entityClass))
            .addFunction(buildExists(schemaName, entityClass))
            .addFunctions(traversalMethods)
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    /**
     * Terminal op: execute the query and return every matching entity,
     * throwing if any entity is denied by LOAD privacy rules.
     */
    private fun buildAll(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val builder = FunSpec.builder("all")
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
        if (edge.through == null) {
            resolveEdgeJoin(edge, schema) ?: return null
        } else {
            resolveM2MEdgeJoin(edge, schema, schemaNames) ?: return null
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

        return EagerEdgeSpec(property, withMethod)
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

            if (edge.through != null) {
                val join = resolveM2MEdgeJoin(edge, schema, schemaNames) ?: continue
                emitM2MEagerBlock(body, eagerPropName, edgePropName, join, targetClass, targetName)
            } else {
                val join = resolveEdgeJoin(edge, schema) ?: continue
                if (edge.unique) {
                    emitToOneEagerBlock(body, eagerPropName, edgePropName, join, targetClass, targetName, schema, schemaNames)
                } else {
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
        body.addStatement(
            "val targetById = targetRows.map { %T.fromRow(it) }.associateBy { it.id }",
            targetClass,
        )
        // Group targets by source via junction rows, then paginate per group.
        body.addStatement("val grouped = mutableMapOf<Any?, MutableList<%T>>()", targetClass)
        body.beginControlFlow("for (jr in junctionRows)")
        body.addStatement(
            "val target = targetById[jr[%S]] ?: continue",
            join.junctionTargetColumn,
        )
        body.addStatement(
            "grouped.getOrPut(jr[%S]) { mutableListOf() }.add(target)",
            join.junctionSourceColumn,
        )
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
     * [edge] (one with `.through(...)`). Unlike direct traversal, there's
     * no inverse edge on the target schema. Instead, the target's
     * generated `EntitySchema` carries a reverse M2M edge entry (injected
     * by [reverseM2MEdgeEntries]) whose name combines the source table
     * name and the forward edge name. The generated method references
     * that reverse name so the runtime can walk the junction table in
     * the right direction.
     */
    private fun buildM2MTraversal(
        edge: Edge,
        source: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec? {
        val targetName = schemaNames[edge.target] ?: return null
        val sourceName = schemaNames[source] ?: return null
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"
        val reverseEdgeName = reverseM2MEdgeName(sourceName, edge.name)

        return FunSpec.builder(methodName)
            .returns(targetQueryClass)
            .addStatement("val parent = combinedPredicate()")
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            .beginControlFlow("if (parent != null)")
            .addStatement(
                "target.where(%T.HasEdgeWith(%S, parent))",
                predicateClass,
                reverseEdgeName,
            )
            .nextControlFlow("else")
            .addStatement(
                "target.where(%T.HasEdge(%S))",
                predicateClass,
                reverseEdgeName,
            )
            .endControlFlow()
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