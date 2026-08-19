package entkt.schema

import kotlin.reflect.KProperty
import kotlin.reflect.KVisibility

/**
 * Shared `provideDelegate` binding logic for field and edge builders.
 *
 * Field builders ([FieldBuilder]) and edge builders ([EdgeBuilderBase])
 * are separate hierarchies with no common supertype, but they bind a
 * declaration name identically, so the rules live here once rather than
 * being restated in five places.
 *
 * Kotlin calls `provideDelegate` while *constructing* a delegated
 * property and hands it the property's metadata. That is what lets entkt
 * learn the Kotlin name without a reflective scan after construction,
 * without a repeated API-name string, and without an annotation
 * processor or compiler plugin.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
internal fun bindDeclarationName(
    property: KProperty<*>,
    kind: String,
    storageName: String,
    owner: EntSchema?,
    existing: String?,
    thisRef: Any?,
    mixinAllowed: Boolean,
    onMixinBinding: (kotlin.reflect.KClass<*>) -> Unit,
): String {
    // A mixin contributes its fields to the *host* schema, so the host
    // class has no property of that name. Recording the concrete mixin
    // class here is what lets finalization tell a legitimate mixin
    // contribution apart from a declaration inherited from a superclass,
    // which the V1 scope rejects — and it scopes the mixin's own
    // binding check to the class that actually declared each builder,
    // so nested mixins do not check each other's properties.
    val schema = owner?.let { it::class.simpleName } ?: "<unregistered>"

    if (thisRef is EntMixin) {
        // Mixins contribute fields and indexes, never edges. Nothing in
        // `EntMixin` can create an edge builder, but a host can create
        // one and hand it to a mixin, which could then delegate a
        // property to it — laundering an edge into the mixin exemption
        // that finalization grants. Reject at the binding itself, where
        // the receiver is known.
        if (!mixinAllowed) {
            error(
                "Mixin '${thisRef::class.simpleName}': $kind '$storageName' cannot be declared " +
                    "in a mixin. Mixins contribute fields and indexes only — declare the edge on " +
                    "the schema class itself.",
            )
        }
        // A mixin only ever contributes to the schema that included it.
        // Binding through a mixin whose host is a different schema would
        // let the mixin exemption launder a declaration onto a schema
        // that never declared it.
        val host = thisRef.schema.host
        if (owner != null && host !== owner) {
            error(
                "Schema '${owner::class.simpleName}': $kind '$storageName' is bound through " +
                    "mixin '${thisRef::class.simpleName}', whose including schema is " +
                    "'${host::class.simpleName}'. A mixin may only contribute to the schema " +
                    "that included it.",
            )
        }
        onMixinBinding(thisRef::class)
    } else if (owner != null && thisRef !== owner) {
        // A delegate receiver that is neither the owning schema nor one
        // of its mixins cannot name a generated API. Without this, an
        // unrelated object could bind a name — and finalization would
        // then mistake any same-named property on the schema for the
        // declaration:
        //
        //     class Holder(b: StringFieldBuilder) { val title by b }
        //     class Article : EntSchema(...) {
        //         val title = Holder(string("legacy_title"))   // rejected
        //     }
        error(
            "Schema '$schema': $kind '$storageName' is bound to property " +
                "'${property.name}' on '${thisRef?.let { it::class.simpleName } ?: "null"}', " +
                "which is neither the declaring schema nor a mixin it includes. Declare it " +
                "directly on '$schema' with `by`.",
        )
    }

    // A builder names exactly one generated API. Reusing one as two
    // delegates (`val a by shared; val b by shared`) would make the
    // generated name depend on binding order, so it is rejected at the
    // *second* binding — where both names are known and can be named.
    if (existing != null) {
        error(
            "Schema '$schema': $kind '$storageName' is already bound to declaration " +
                "'$existing', so it cannot also be bound to '${property.name}'. A $kind " +
                "builder names exactly one generated API — keep one canonical " +
                "`val $existing by ...` declaration and remove the other.",
        )
    }

    // A non-public property would silently create a *public* generated
    // member, which is the opposite of what the declaration says. Reject
    // rather than quietly promoting it.
    //
    // `KProperty.visibility` is resolvable here: the reference Kotlin
    // passes to `provideDelegate` carries the declaration's visibility,
    // and the schema module already depends on kotlin-reflect.
    val visibility = property.visibility
    if (visibility != null && visibility != KVisibility.PUBLIC) {
        error(
            "Schema '$schema': $kind '$storageName' is bound to " +
                "${visibility.name.lowercase()} property '${property.name}'. A generated API " +
                "name must come from a public `val` — a non-public declaration would " +
                "silently create a public generated member. Make '${property.name}' public, " +
                "or remove the declaration.",
        )
    }

    // The declaration name is emitted verbatim as a Kotlin identifier, so
    // it faces the generated-API vocabulary, not the storage vocabulary.
    EntSchema.validateApiName(property.name, "$kind declaration name")

    return property.name
}
