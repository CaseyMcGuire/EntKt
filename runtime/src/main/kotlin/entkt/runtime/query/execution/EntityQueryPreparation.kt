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
import entkt.runtime.query.FrozenQuerySpec
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

/** Turns a recursive entity query into the immutable shape sent to storage. */
@EntktInternal
class EntityQueryPreparation(
    private val driver: DatabaseDriver,
    private val registeredInterceptors: () -> EntInterceptorsConfig,
) {
    /** Apply traversal and interceptor stages at every node in [query]. */
    fun <Entity : EntEntity<*>> prepare(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        privacyContext: PrivacyContext,
    ): FrozenQuerySpec<Entity> = prepareWithContext(
        query,
        operation,
        privacyContext,
    ).spec

    /** Prepare one selected edge target with its relationship and traversal context. */
    fun <Entity : EntEntity<*>> prepareSelectedEdge(
        query: EntityQuery<Entity>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        path: List<EdgeStep>,
        structuralPredicates: List<Predicate<Entity>>,
        structuralSingleBindTransport: Boolean = false,
    ): FrozenQuerySpec<Entity> = prepareRelatedNode(
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

    /** Prepare the junction-discovery read for a selected many-to-many edge. */
    fun <Entity : EntEntity<*>> prepareJunction(
        entity: EntityMapping<Entity>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        path: List<EdgeStep>,
        structuralPredicates: List<Predicate<Entity>>,
    ): FrozenQuerySpec<Entity> = prepareRelatedNode(
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

    private fun <Entity : EntEntity<*>> prepareRelatedNode(
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
    ): FrozenQuerySpec<Entity> {
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
        return prepareNode(
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
        ).spec
    }

    private fun <Entity : EntEntity<*>> prepareWithContext(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        privacyContext: PrivacyContext,
    ): PreparedEntityQuery<Entity> {
        val traversal = prepareTraversal(query, privacyContext)
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
        return prepareNode(
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
    }

    private fun <Entity : EntEntity<*>> prepareTraversal(
        query: EntityQuery<Entity>,
        privacyContext: PrivacyContext,
    ): PreparedTraversal<Entity>? = when (val source = query.source) {
        is QuerySource.Root -> null
        is QuerySource.Traversal<*, *> -> prepareTraversalSource(source, privacyContext)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Target : EntEntity<*>> prepareTraversalSource(
        source: QuerySource.Traversal<*, *>,
        privacyContext: PrivacyContext,
    ): PreparedTraversal<Target> {
        val typedSource = source as QuerySource.Traversal<EntEntity<*>, Target>
        val sourceQuery = prepareWithContext(
            typedSource.source,
            ReadOperation.EDGE_TRAVERSAL,
            privacyContext,
        )
        val traversal = checkNotNull(typedSource.edge.traversal) {
            "Edge '${typedSource.edge.name}' does not support chained query traversal"
        }
        val sourceShape = TraversalSourceShape(
            table = sourceQuery.spec.table,
            selectedColumn = traversal.selectedColumn,
            predicates = sourceQuery.spec.predicates,
            orderBy = sourceQuery.spec.orderBy,
            limit = sourceQuery.spec.limit,
            offset = sourceQuery.spec.offset,
            flags = sourceQuery.spec.flags,
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

        return PreparedTraversal(
            bridge = bridge,
            annotations = sourceQuery.spec.annotations,
            rootEntity = sourceQuery.context.rootEntity,
            sourceEntity = typedSource.edge.source.entityClass,
            edgeName = typedSource.edge.name,
            path = immutablePath(sourceQuery.context.path + step),
        )
    }

    private fun <Entity : EntEntity<*>> prepareNode(
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
    ): PreparedEntityQuery<Entity> {
        val interceptors = registeredInterceptors()
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

        val queryAfterInterceptors = queryBuilder.freeze()
        val queryReadyForStorage = applyEdgePredicateInterceptors(
            entity,
            queryAfterInterceptors,
            context,
        )
        return PreparedEntityQuery(queryReadyForStorage, context)
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
        query: FrozenQuerySpec<Entity>,
        context: QueryContext,
    ): FrozenQuerySpec<Entity> {
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
        val targetQuery = prepareEdgePredicateTarget(
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
        val targetQuery = prepareEdgePredicateTarget(edge, emptyList(), context)
        edgeAnnotations.putAll(targetQuery.annotations)
        val combined = targetQuery.predicates.reduceOrNull(Predicate<EntEntity<*>>::and)
        return if (combined == null) {
            predicate
        } else {
            Predicate.HasEdgeWith(predicate.edge, combined)
        }
    }

    private fun <Source : EntEntity<*>, Target : EntEntity<*>> prepareEdgePredicateTarget(
        edge: EdgeMapping<Source, Target>,
        predicates: List<Predicate<Target>>,
        parentContext: QueryContext,
    ): FrozenQuerySpec<Target> {
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
        return prepareNode(
            entity = edge.target,
            callerPredicates = predicates,
            orderBy = emptyList(),
            limit = null,
            offset = null,
            structuralPredicates = emptyList(),
            initialAnnotations = emptyMap(),
            context = context,
        ).spec
    }

    private fun edgePredicateDepthMessage(path: List<EdgeStep>): String =
        "edge-predicate interceptor recursion exceeded depth " +
            "$EDGE_PREDICATE_MAX_DEPTH on path " +
            path.joinToString(" → ") { "${it.source.simpleName}.${it.edgeName}" } +
            ". Likely cause: interceptors on two entities add edge predicates that " +
            "reference each other. Fix the interceptor cycle."

    private fun immutablePath(path: List<EdgeStep>): List<EdgeStep> =
        Collections.unmodifiableList(path.toList())

    private data class PreparedTraversal<Entity : EntEntity<*>>(
        val bridge: Predicate<Entity>,
        val annotations: Map<String, String>,
        val rootEntity: KClass<*>,
        val sourceEntity: KClass<*>,
        val edgeName: String,
        val path: List<EdgeStep>,
    )

    private data class PreparedEntityQuery<Entity : EntEntity<*>>(
        val spec: FrozenQuerySpec<Entity>,
        val context: QueryContext,
    )
}
