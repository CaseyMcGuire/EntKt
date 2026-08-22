@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.TraversalSourceShape
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeTraversal
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.StorageQuerySpec
import entkt.runtime.query.AbortQueryRejected
import entkt.runtime.query.GlobalInterceptScopeImpl
import entkt.runtime.query.InterceptScopeImpl
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QuerySource
import entkt.runtime.query.QuerySpecBuilder
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.RegisteredGlobalInterceptor
import entkt.runtime.query.RegisteredInterceptor
import java.util.Collections
import kotlin.reflect.KClass

/** Maximum nested edge-predicate depth before a likely interceptor cycle is rejected. */
private const val EDGE_PREDICATE_MAX_DEPTH: Int = 32

/**
 * Resolves one entity-query node into the immutable query executed by storage.
 *
 * The compilation pipeline is:
 *
 * 1. Recursively compile any traversal source and turn it into a bridge predicate.
 * 2. Construct the interceptor context for the current entity and traversal path.
 * 3. Combine caller predicates with framework-owned structural predicates.
 * 4. Run entity interceptors followed by global interceptors.
 * 5. Recursively compile interceptor behavior inside edge predicates.
 * 6. Return the completed [StorageQuerySpec].
 *
 * Selected relationships and junction discovery enter the same interceptor and
 * edge-predicate stages with their relationship context supplied explicitly.
 */
@EntktInternal
class ReadQueryCompiler(
    private val driver: DatabaseDriver,
    private val registeredInterceptorsProvider: () -> EntInterceptorsConfig,
) {
    /** Apply traversal and interceptor stages at every node in [query]. */
    fun <Entity : EntEntity<*>> compile(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        privacyContext: PrivacyContext,
    ): StorageQuerySpec<Entity> {
        val compiledNode = compileQueryNode(query, operation, privacyContext)
        return compiledNode.query
    }

    /** Compile one selected edge target with its relationship and traversal context. */
    fun <Entity : EntEntity<*>> compileSelectedEdge(
        query: EntityQuery<Entity>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        path: List<EdgeStep>,
        structuralPredicates: List<Predicate<Entity>>,
        structuralSingleBindTransport: Boolean = false,
    ): StorageQuerySpec<Entity> = compileRelatedNode(
        entity = query.entity,
        callerPredicates = query.predicates,
        orderBy = query.orderBy,
        limit = query.limit,
        offset = query.offset,
        operation = ReadOperation.EAGER_LOAD,
        privacyContext = privacyContext,
        rootEntity = rootEntity,
        path = path,
        structuralPredicates = structuralPredicates,
        appendPrimaryKeyOrder = true,
        structuralSingleBindTransport = structuralSingleBindTransport,
    )

    /** Compile the junction-discovery read for a selected many-to-many edge. */
    fun <Entity : EntEntity<*>> compileJunction(
        entity: EntityMapping<Entity>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        path: List<EdgeStep>,
        structuralPredicates: List<Predicate<Entity>>,
    ): StorageQuerySpec<Entity> = compileRelatedNode(
        entity = entity,
        callerPredicates = emptyList(),
        orderBy = emptyList(),
        limit = null,
        offset = null,
        operation = ReadOperation.EAGER_JUNCTION,
        privacyContext = privacyContext,
        rootEntity = rootEntity,
        path = path,
        structuralPredicates = structuralPredicates,
        appendPrimaryKeyOrder = false,
        structuralSingleBindTransport = false,
    )

    private fun <Entity : EntEntity<*>> compileRelatedNode(
        entity: EntityMapping<Entity>,
        callerPredicates: List<Predicate<Entity>>,
        orderBy: List<OrderField<Entity>>,
        limit: Int?,
        offset: Int?,
        operation: ReadOperation,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        path: List<EdgeStep>,
        structuralPredicates: List<Predicate<Entity>>,
        appendPrimaryKeyOrder: Boolean,
        structuralSingleBindTransport: Boolean,
    ): StorageQuerySpec<Entity> {
        val step = path.lastOrNull()
        val effectiveOrder = if (appendPrimaryKeyOrder && orderBy.none { it.field == "id" }) {
            orderBy + OrderField("id", OrderDirection.ASC)
        } else {
            orderBy
        }
        val context = QueryContext(
            privacy = privacyContext,
            operation = operation,
            rootEntity = rootEntity,
            currentEntity = entity.entityClass,
            sourceEntity = step?.source,
            edgeName = step?.edgeName,
            path = immutablePath(path),
            flags = emptySet(),
        )
        return compileStorageQuery(
            entity = entity,
            callerPredicates = callerPredicates,
            orderBy = effectiveOrder,
            callerOrderBy = orderBy,
            limit = limit,
            offset = offset,
            structuralPredicates = structuralPredicates,
            initialAnnotations = emptyMap(),
            context = context,
            structuralSingleBindTransport = structuralSingleBindTransport,
        )
    }

    /** Resolve traversal context, then compile the current entity-query node. */
    private fun <Entity : EntEntity<*>> compileQueryNode(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        privacyContext: PrivacyContext,
    ): CompiledQueryNode<Entity> {
        val traversal = resolveTraversal(query, privacyContext)
        val context = QueryContext(
            privacy = privacyContext,
            operation = operation,
            rootEntity = traversal?.rootEntity ?: query.entity.entityClass,
            currentEntity = query.entity.entityClass,
            sourceEntity = traversal?.sourceEntity,
            edgeName = traversal?.edgeName,
            path = traversal?.path ?: emptyList(),
            flags = emptySet(),
        )
        val queryForStorage = compileStorageQuery(
            entity = query.entity,
            callerPredicates = query.predicates,
            orderBy = query.orderBy,
            callerOrderBy = query.orderBy,
            limit = query.limit,
            offset = query.offset,
            structuralPredicates = query.structuralPredicates + listOfNotNull(traversal?.bridge),
            initialAnnotations = traversal?.annotations ?: emptyMap(),
            context = context,
        )
        return CompiledQueryNode(queryForStorage, context)
    }

    /** Resolve a chained query source into its storage bridge and interceptor context. */
    private fun <Entity : EntEntity<*>> resolveTraversal(
        query: EntityQuery<Entity>,
        privacyContext: PrivacyContext,
    ): ResolvedTraversal<Entity>? = when (val source = query.source) {
        is QuerySource.Root -> null
        is QuerySource.Traversal<*, *> -> resolveTraversalSource(source, privacyContext)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Target : EntEntity<*>> resolveTraversalSource(
        source: QuerySource.Traversal<*, *>,
        privacyContext: PrivacyContext,
    ): ResolvedTraversal<Target> {
        val typedSource = source as QuerySource.Traversal<EntEntity<*>, Target>
        val sourceNode = compileQueryNode(
            typedSource.source,
            ReadOperation.EDGE_TRAVERSAL,
            privacyContext,
        )
        val traversal = checkNotNull(typedSource.edge.traversal) {
            "Edge '${typedSource.edge.name}' does not support chained query traversal"
        }
        val sourceShape = TraversalSourceShape(
            table = sourceNode.query.table,
            selectedColumn = traversal.selectedColumn,
            predicates = sourceNode.query.predicates,
            orderBy = sourceNode.query.orderBy,
            limit = sourceNode.query.limit,
            offset = sourceNode.query.offset,
            flags = sourceNode.query.flags,
        )
        val bridge: Predicate<Target> = when (traversal) {
            is EdgeTraversal.Direct -> Predicate.HasEdgeFromShape(
                traversal.inverseStorageName,
                sourceShape,
            )

            is EdgeTraversal.ManyToMany -> Predicate.HasM2MEdgeFromShape(
                traversal.sourceStorageName,
                sourceShape,
            )
        }
        val step = EdgeStep(
            source = typedSource.edge.source.entityClass,
            edgeName = typedSource.edge.name,
            target = typedSource.edge.target.entityClass,
        )

        return ResolvedTraversal(
            bridge = bridge,
            annotations = sourceNode.query.annotations,
            rootEntity = sourceNode.context.rootEntity,
            sourceEntity = typedSource.edge.source.entityClass,
            edgeName = typedSource.edge.name,
            path = immutablePath(sourceNode.context.path + step),
        )
    }

    /** Build one storage query, run its interceptors, and compile nested edge predicates. */
    private fun <Entity : EntEntity<*>> compileStorageQuery(
        entity: EntityMapping<Entity>,
        callerPredicates: List<Predicate<Entity>>,
        orderBy: List<OrderField<Entity>>,
        callerOrderBy: List<OrderField<Entity>> = orderBy,
        limit: Int?,
        offset: Int?,
        structuralPredicates: List<Predicate<Entity>>,
        initialAnnotations: Map<String, String>,
        context: QueryContext,
        structuralSingleBindTransport: Boolean = false,
    ): StorageQuerySpec<Entity> {
        val interceptors = registeredInterceptorsProvider()
        val queryBuilder = QuerySpecBuilder(
            table = entity.table,
            entity = entity.entityClass,
            callerPredicates = callerPredicates,
            structuralPredicates = structuralPredicates,
            orderBy = orderBy,
            callerLimit = limit,
            offset = offset,
            flags = context.flags,
            initialAnnotations = initialAnnotations,
            callerOrderBy = callerOrderBy,
            requireBindCapacity = { driver.requireBindCapacity(it, entity.table) },
            structuralSingleBindTransport = structuralSingleBindTransport,
        )

        runInterceptors(
            builder = queryBuilder,
            context = context,
            entityName = entity.entityName,
            entityInterceptors = interceptors.entityInterceptorsFor(entity.clientName),
            globalInterceptors = interceptors.globals(),
        )

        val queryAfterInterceptors = queryBuilder.build()
        return applyEdgePredicateInterceptors(
            entity,
            queryAfterInterceptors,
            context,
        )
    }

    /** Run entity interceptors first, followed by global interceptors. */
    internal fun <Entity : Any> runInterceptors(
        builder: QuerySpecBuilder<Entity>,
        context: QueryContext,
        entityName: String,
        entityInterceptors: List<RegisteredInterceptor<Entity>>,
        globalInterceptors: List<RegisteredGlobalInterceptor>,
    ) {
        try {
            for (registered in entityInterceptors) {
                val scope = InterceptScopeImpl(
                    builder,
                    registered.name,
                    entityName,
                    context.operation,
                )
                registered.interceptor.intercept(scope, context)
            }
            for (registered in globalInterceptors) {
                val scope = GlobalInterceptScopeImpl(
                    builder,
                    registered.name,
                    entityName,
                    context.operation,
                )
                registered.interceptor.intercept(scope, context)
            }
        } catch (rejection: AbortQueryRejected) {
            throw rejection.rejected
        }
    }

    private fun <Entity : EntEntity<*>> applyEdgePredicateInterceptors(
        entity: EntityMapping<Entity>,
        query: StorageQuerySpec<Entity>,
        context: QueryContext,
    ): StorageQuerySpec<Entity> {
        val edgeAnnotations = linkedMapOf<String, String>()
        val structuralRange = query.callerPredicateCount until
            (query.callerPredicateCount + query.structuralPredicateCount)
        val predicates = query.predicates.mapIndexed { index, predicate ->
            if (index in structuralRange) {
                predicate
            } else {
                applyEdgePredicateInterceptors(
                    entity,
                    predicate,
                    context,
                    edgeAnnotations,
                )
            }
        }
        return query.copy(
            predicates = predicates,
            annotations = edgeAnnotations + query.annotations,
        )
    }

    private fun <Source : EntEntity<*>> applyEdgePredicateInterceptors(
        entity: EntityMapping<Source>,
        predicate: Predicate<Source>,
        context: QueryContext,
        edgeAnnotations: MutableMap<String, String>,
    ): Predicate<Source> = when (predicate) {
        is Predicate.And -> Predicate.And(
            applyEdgePredicateInterceptors(entity, predicate.left, context, edgeAnnotations),
            applyEdgePredicateInterceptors(entity, predicate.right, context, edgeAnnotations),
        )

        is Predicate.Or -> Predicate.Or(
            applyEdgePredicateInterceptors(entity, predicate.left, context, edgeAnnotations),
            applyEdgePredicateInterceptors(entity, predicate.right, context, edgeAnnotations),
        )

        is Predicate.HasEdgeWith<Source, *> -> evaluateEdgePredicate(
            entity,
            predicate,
            context,
            edgeAnnotations,
        )

        is Predicate.HasEdge -> evaluateEdgePredicate(
            entity,
            predicate,
            context,
            edgeAnnotations,
        )

        else -> predicate
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>> evaluateEdgePredicate(
        entity: EntityMapping<Source>,
        predicate: Predicate.HasEdgeWith<Source, *>,
        context: QueryContext,
        edgeAnnotations: MutableMap<String, String>,
    ): Predicate<Source> {
        val edge = entity.edgeByStorageName(predicate.edge)
            as? EdgeMapping<Source, EntEntity<*>>
            ?: return predicate
        val inner = predicate.inner as Predicate<EntEntity<*>>
        val targetQuery = compileEdgePredicateTarget(
            edge,
            listOf(inner),
            context,
        )
        edgeAnnotations.putAll(targetQuery.annotations)
        val combined = targetQuery.predicates.reduceOrNull(Predicate<EntEntity<*>>::and)
            ?: inner
        return Predicate.HasEdgeWith(predicate.edge, combined)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>> evaluateEdgePredicate(
        entity: EntityMapping<Source>,
        predicate: Predicate.HasEdge<Source>,
        context: QueryContext,
        edgeAnnotations: MutableMap<String, String>,
    ): Predicate<Source> {
        val edge = entity.edgeByStorageName(predicate.edge)
            as? EdgeMapping<Source, EntEntity<*>>
            ?: return predicate
        val targetQuery = compileEdgePredicateTarget(edge, emptyList(), context)
        edgeAnnotations.putAll(targetQuery.annotations)
        val combined = targetQuery.predicates.reduceOrNull(Predicate<EntEntity<*>>::and)
        return if (combined == null) {
            predicate
        } else {
            Predicate.HasEdgeWith(predicate.edge, combined)
        }
    }

    private fun <Source : EntEntity<*>, Target : EntEntity<*>> compileEdgePredicateTarget(
        edge: EdgeMapping<Source, Target>,
        predicates: List<Predicate<Target>>,
        parentContext: QueryContext,
    ): StorageQuerySpec<Target> {
        check(parentContext.path.size < EDGE_PREDICATE_MAX_DEPTH) {
            edgePredicateDepthMessage(parentContext.path)
        }
        val step = EdgeStep(
            source = edge.source.entityClass,
            edgeName = edge.name,
            target = edge.target.entityClass,
        )
        val context = QueryContext(
            privacy = parentContext.privacy,
            operation = ReadOperation.EDGE_PREDICATE,
            rootEntity = parentContext.rootEntity,
            currentEntity = edge.target.entityClass,
            sourceEntity = edge.source.entityClass,
            edgeName = edge.name,
            path = immutablePath(parentContext.path + step),
            flags = emptySet(),
        )
        return compileStorageQuery(
            entity = edge.target,
            callerPredicates = predicates,
            orderBy = emptyList(),
            limit = null,
            offset = null,
            structuralPredicates = emptyList(),
            initialAnnotations = emptyMap(),
            context = context,
        )
    }

    private fun edgePredicateDepthMessage(path: List<EdgeStep>): String =
        "edge-predicate interceptor recursion exceeded depth " +
            "$EDGE_PREDICATE_MAX_DEPTH on path " +
            path.joinToString(" → ") { "${it.source.simpleName}.${it.edgeName}" } +
            ". Likely cause: interceptors on two entities add edge predicates that " +
            "reference each other. Fix the interceptor cycle."

    private fun immutablePath(path: List<EdgeStep>): List<EdgeStep> =
        Collections.unmodifiableList(path.toList())

    private data class ResolvedTraversal<Entity : EntEntity<*>>(
        val bridge: Predicate<Entity>,
        val annotations: Map<String, String>,
        val rootEntity: KClass<*>,
        val sourceEntity: KClass<*>,
        val edgeName: String,
        val path: List<EdgeStep>,
    )

    private data class CompiledQueryNode<Entity : EntEntity<*>>(
        val query: StorageQuerySpec<Entity>,
        val context: QueryContext,
    )
}
