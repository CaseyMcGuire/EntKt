package entkt.schema

/**
 * Marker interfaces for edge declarations. Each edge kind has its own
 * handle interface so that `inverse(...)` and `through(...)` can
 * restrict which property references are valid at compile time.
 *
 * Edge builder classes implement these interfaces, making the builder
 * itself the public handle stored by schema properties.
 */

interface BelongsToHandle<Target : EntSchema>
interface HasManyHandle<Target : EntSchema>
interface HasOneHandle<Target : EntSchema>
interface ManyToManyHandle<Target : EntSchema>
