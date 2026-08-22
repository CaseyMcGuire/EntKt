package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity

/**
 * Builds the target batch that will undergo privacy evaluation and recursive graph loading.
 *
 * Relationship loading first groups targets by source and applies each source's offset and
 * limit. A target may consequently appear in more than one group, but it should be evaluated
 * only once. This function keeps targets that survived at least one source window, removes
 * duplicate entity IDs, and preserves their order in [orderedTargets].
 *
 * @param groups windowed targets keyed by the source entity's relationship key.
 * @param orderedTargets all loaded targets in storage-query order.
 * @return the ordered, ID-distinct targets retained by at least one source group.
 */
internal fun <Key, Target : EntEntity<*>> retainTargets(
    groups: Map<Key, List<Target>>,
    orderedTargets: List<Target>,
): List<Target> {
    val retainedIds = groups.values.flatten().mapTo(linkedSetOf()) { it.id }
    return orderedTargets
        .filter { it.id in retainedIds }
        .distinctBy { it.id }
}

/**
 * Rebuilds each source group with the targets returned by graph evaluation.
 *
 * [groups] contains the originally loaded entity instances used to preserve source correlation
 * and relationship ordering. [evaluatedTargets] contains the corresponding privacy-checked
 * entities after their selected subgraphs have been loaded. Each original target is replaced by
 * the evaluated instance with the same entity ID. Targets absent from [evaluatedTargets], such as
 * entities removed by filtering LOAD privacy, are omitted from every source group.
 *
 * @param groups windowed targets keyed by the source entity's relationship key.
 * @param evaluatedTargets privacy-checked, recursively loaded targets keyed by entity ID.
 * @return the original source groups and ordering populated with evaluated target instances.
 */
internal fun <Key, Target : EntEntity<*>> replaceWithEvaluatedTargets(
    groups: Map<Key, List<Target>>,
    evaluatedTargets: List<Target>,
): Map<Key, List<Target>> {
    val evaluatedById = evaluatedTargets.associateBy { it.id }
    return groups.mapValues { (_, targets) ->
        targets.mapNotNull { evaluatedById[it.id] }
    }
}
