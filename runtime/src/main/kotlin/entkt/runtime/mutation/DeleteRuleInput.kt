package entkt.runtime.mutation

import entkt.runtime.entity.EntEntity

/**
 * The entity being deleted and the candidate holding its writable values, as evaluated by DELETE rules.
 *
 * The runtime reads both properties itself (the entity's ID for denials, the candidate for CREATE-derived
 * rules), and the two type arguments fully describe the input. Every entity therefore shares this class
 * through a generated `<Entity>DeleteRuleInput` alias. Compare [UpdateRuleInput], whose per-entity patch
 * and edge-change types require a generated class.
 *
 * Rule inputs share their values without per-rule defensive copies.
 * Treat all properties and nested values as read-only.
 */
data class DeleteRuleInput<Entity : EntEntity<*>, Candidate : WriteCandidate<Entity>>(
    val entity: Entity,
    val candidate: Candidate,
)
