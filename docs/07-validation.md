# Entity Validation

Entity validation rules enforce data model invariants that go beyond
per-field constraints. They run after privacy checks (so unauthorized
users never see validation errors) and before the database write.

## Quick Example

```kotlin
object PostPolicy : EntityPolicy<Post, PostPolicyScope> {
    override fun configure(scope: PostPolicyScope) = scope.run {
        privacy {
            create(RequireAuthToCreate())
            update(AllowAuthorUpdate())
            delete(AllowAuthorDelete())
        }
        validation {
            create(RequireBodyForPublished())
            updateDerivesFromCreate()
            delete(CannotDeletePublishedPost())
        }
    }
}

class RequireBodyForPublished : PostCreateValidationRule {
    override fun validate(ctx: PostCreateValidationContext): ValidationDecision =
        if (ctx.candidate.published && ctx.candidate.body.isNullOrBlank()) {
            ValidationDecision.Invalid("published posts must have a body", field = "body")
        } else {
            ValidationDecision.Valid
        }
}

class CannotDeletePublishedPost : PostDeleteValidationRule {
    override fun validate(ctx: PostDeleteValidationContext): ValidationDecision =
        if (ctx.entity.published) {
            ValidationDecision.Invalid("cannot delete a published post")
        } else {
            ValidationDecision.Valid
        }
}
```

## Concepts

### ValidationDecision

Each rule returns one of two decisions:

| Decision | Meaning |
|----------|---------|
| `Valid` | This rule passes |
| `Invalid(message, field?, code?)` | This rule fails with a reason |

Unlike privacy rules, there is no `Continue` — every rule runs
regardless of prior results. All violations are collected and reported
together.

```kotlin
sealed interface ValidationDecision {
    data object Valid : ValidationDecision
    data class Invalid(
        val message: String,
        val field: String? = null,
        val code: String? = null,
    ) : ValidationDecision
}
```

`field` identifies which field caused the violation (useful for
mapping errors to form inputs). `code` is a machine-readable
identifier for i18n or programmatic error handling. Both are optional.

### ValidationRule

A rule is a `fun interface` that takes a typed context and returns a
decision:

```kotlin
fun interface ValidationRule<in C> {
    fun validate(ctx: C): ValidationDecision
}
```

Each operation gets its own context type, so rules are type-safe for
the operation they guard.

### ValidationException

When one or more rules return `Invalid`, a `ValidationException` is
thrown containing all violations:

```kotlin
class ValidationException(
    val entity: String,
    val violations: List<ValidationDecision.Invalid>,
) : RuntimeException(
    "Validation failed on $entity: ${violations.joinToString("; ") { it.message }}"
)
```

All violations are collected before throwing, so API consumers can
display every problem at once rather than fixing them one at a time.
Each `Invalid` carries its `message`, optional `field`, and optional
`code` for programmatic access.

## Setting Up Validation

Validation rules are registered through the same `EntityPolicy` used
for privacy. The `validation { }` block sits alongside `privacy { }`:

```kotlin
object UserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            // authorization rules
        }
        validation {
            create(UniqueEmail(), ValidEmailFormat())
            update(UniqueEmail(), ValidEmailFormat())
            delete(CannotDeleteWithOpenInvoices())
        }
    }
}

val client = EntClient(driver) {
    policies {
        users(UserPolicy)
        posts(PostPolicy)
    }
}
```

### Operations

The `validation { }` block exposes three methods:

- `create(vararg rules)` — run before insert
- `update(vararg rules)` — run before update
- `delete(vararg rules)` — run before delete

There is no `load` validation — validation guards writes, not reads.

## Operation Contexts

Each operation's rules receive a typed context. Contexts include a
read-only `EntReadClient` so validators can query the
database (e.g. uniqueness checks, referential integrity) — and only
query it. The write surface does not exist on that type, so a
validator that tries to create, update, or delete does not compile.

### CreateValidationContext

```kotlin
data class PostCreateValidationContext(
    val client: EntReadClient,
    val candidate: PostWriteCandidate,
)
```

### UpdateValidationContext

```kotlin
data class PostUpdateValidationContext(
    val client: EntReadClient,
    val before: Post,                    // current state of the entity (loaded by save())
    val requestedPatch: PostUpdatePatch, // caller/hook intent — FieldPatch entries
    val effectivePatch: PostUpdatePatch, // after framework update defaults
    val candidate: PostWriteCandidate,   // full after-state = before + effectivePatch
    val edgeChanges: PostEdgeChangesView, // per-edge intent + computed delta
)
```

`requestedPatch` and `effectivePatch` carry per-field `FieldPatch<T>` entries
(`Unset` or `Set(value)`). Validators that want to know *what changed* should
read the patches; validators that check invariants on the *full after-state*
should read `candidate`.

`edgeChanges` is the same per-entity aggregator that update privacy rules
receive — one `EdgeChanges<TargetIdType>` per helper-eligible `throughLink`
M2M edge, carrying both caller intent (`requestedSet?` / `requestedAdds` /
`requestedRemoves`) and the computed database delta (`added` / `removed`).
See [Privacy → UpdatePrivacyContext](06-privacy.md#updateprivacycontext)
for the field semantics. A typical validator pattern: reject `remove` calls
that name unknown / forbidden target ids by inspecting
`ctx.edgeChanges.tags.requestedRemoves` — the literal call log surfaces
the id even when the database effect is a no-op.

By the time validation runs, the post-hook required-not-null check has
already fired, so a dirty + null required field would have thrown
`IllegalStateException` before reaching validation. Validators can treat
`FieldPatch.Set(value)` for required fields as having a non-null value
and `FieldPatch.Unset` as "not in this update".

### DeleteValidationContext

```kotlin
data class PostDeleteValidationContext(
    val client: EntReadClient,
    val entity: Post,
    val candidate: PostWriteCandidate,
)
```

Contexts do **not** include `PrivacyContext`. Privacy has already been
enforced by the time validators run — validators are viewer-agnostic.
If a rule cares about who is performing the operation, it belongs in
privacy, not validation.

The `client` in validation contexts is read-only and its reads bypass LOAD
privacy, allowing invariant checks such as uniqueness and referential
integrity to inspect all relevant rows. Read interceptors still apply, and
validation performed inside `withTransaction` sees earlier writes from the
same transaction.

Privacy-rule contexts also expose `EntReadClient`, but their reads use the
caller's privacy context instead. See
[Privacy → Operation Contexts](06-privacy.md#operation-contexts).

### WriteCandidate

Validators reuse the same `WriteCandidate` data class generated for
privacy rules. It contains all non-ID fields and edge FK fields as an
immutable snapshot:

```kotlin
data class PostWriteCandidate(
    val title: String,
    val body: String?,
    val published: Boolean,
    val authorId: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

## Evaluation Semantics

All rules for an operation run unconditionally. Invalid results are
collected into one `ValidationException`.

`Viewer.PrivacyBypass` does **not** bypass validation. Validation enforces
data model invariants that apply regardless of who is performing the
operation. This differs from privacy, where `Viewer.PrivacyBypass` bypasses
all checks.

## Execution Order

### Create

```
1. beforeSave and beforeCreate hooks
2. defaults and field validation
3. CREATE privacy
4. CREATE entity validation
5. persistence
6. afterCreate hooks
7. LOAD privacy on the returned entity
```

### Update

```
1. empty-request check and current-entity load
2. beforeSave and beforeUpdate hooks
3. required-field checks, update defaults, and field validation
4. UPDATE privacy
5. UPDATE entity validation
6. persistence
7. afterUpdate hooks
8. LOAD privacy on the returned entity
```

An empty request throws `EntNoChangesException`. If update hooks remove every
requested change, UPDATE privacy still runs, then the operation throws
`EntNoChangesException` without running entity validation or `afterUpdate`.

### Delete

```
1. DELETE privacy
2. DELETE entity validation
3. beforeDelete hooks
4. deletion
5. afterDelete hooks
```

Field validation runs before privacy because it validates local request
shape and generated schema constraints (minLength, maxLength, etc.) — these
do not read stored data. Entity validation runs after privacy to
prevent domain and data-existence leaks through validation errors
(e.g. "slug already exists" or "recipient not found").

## Validators That Query

Since validation contexts receive a System-scoped client, validators
can query the database without being blocked by LOAD privacy.

**Validators are read-only — by type, not by convention.** The context
exposes `EntReadClient`, whose per-entity repos carry the
byId family, the full `query { }` DSL with every terminal (`all` /
`first` / `visible` families, counts, exists, aggregates, `explain*`),
and the generated index helpers — and nothing else. `create`,
`update`, `save`, the `delete*` family, edge mutators, and
`withTransaction` do not exist on it, so a validator that tries to
mutate fails to compile. Validators answer "is this state valid?", not
"make it valid" — mutating inside a validator would bypass the calling
operation's hooks, privacy, and validation ordering, which is why the
API makes it impossible rather than merely documented.

**Validators do not replace database constraints.** Validation runs
before the database write with no lock held, so queries like "is this
slug taken?" can race concurrent inserts. Use validators to produce
clear domain errors for the common case, but always back uniqueness,
foreign keys, and relationship integrity with database constraints
(`UNIQUE`, `REFERENCES`, etc.). The database constraint is the source
of truth; the validator improves the error message.

```kotlin
class UniqueSlug : PostCreateValidationRule {
    override fun validate(ctx: PostCreateValidationContext): ValidationDecision {
        val exists = ctx.client.posts.query {
            where(Post.slug eq ctx.candidate.slug)
        }.rawExists()
        return if (exists) ValidationDecision.Invalid("slug already taken")
        else ValidationDecision.Valid
    }
}

class AuthorExists : PostCreateValidationRule {
    override fun validate(ctx: PostCreateValidationContext): ValidationDecision {
        val author = ctx.client.users.byIdOrNull(ctx.candidate.authorId)
        return if (author == null) ValidationDecision.Invalid("author does not exist")
        else ValidationDecision.Valid
    }
}
```

Index helpers work too — they are query sugar and equally read-only:

```kotlin
class UniqueEmail : UserCreateValidationRule {
    override fun validate(ctx: UserCreateValidationContext): ValidationDecision =
        if (ctx.client.users.indexes.email(ctx.candidate.email).orNull() != null) {
            ValidationDecision.Invalid("email already taken", field = "email")
        } else {
            ValidationDecision.Valid
        }
}
```

## Rule Derivation

Like privacy, validation supports derivation to reuse create rules for
update:

```kotlin
validation {
    create(RequireBodyForPublished())
    updateDerivesFromCreate()
}
```

When derivation is active, the operation's own rules are evaluated
first, then create rules are also evaluated. Both sets of errors are
collected together.

**Use derivation only for pure candidate invariants** — rules that
inspect `candidate` fields without querying. Rules that check
uniqueness or existence are usually unsafe to derive because the
update context differs from create (e.g. a uniqueness check must
exclude the current entity's own row). Write those as explicit update
rules instead:

```kotlin
validation {
    // Safe to derive — only inspects candidate fields
    create(RequireBodyForPublished())
    updateDerivesFromCreate()

    // NOT safe to derive — create uniqueness check would reject
    // unchanged slugs on update. Write an explicit update rule.
    create(UniqueSlugOnCreate())
    update(UniqueSlugOnUpdate())  // excludes ctx.before.id
}
```

## Bulk Operations

**Bulk methods (`createMany`, `deleteMany`) delegate per item.** Each
item runs the full validation pipeline independently. Execution stops
on the first validation failure. Prior items may already be written
unless the caller wraps the operation in a transaction.

## Generated Validation API

For each schema, entkt provides:

| Public type | Purpose |
|-------------|---------|
| `{Entity}CreateValidationRule` | Typealias for create validation rules |
| `{Entity}UpdateValidationRule` | Typealias for update validation rules |
| `{Entity}DeleteValidationRule` | Typealias for delete validation rules |
| `{Entity}CreateValidationContext` | Context for create validators |
| `{Entity}UpdateValidationContext` | Context for update validators |
| `{Entity}DeleteValidationContext` | Context for delete validators |
| `{Entity}ValidationScope` | DSL scope inside `validation { }` |
| `{Entity}ReadRepo` | Read-only repo exposed to validators (byId family, `query { }`, index helpers) |

The `{Entity}PolicyScope` gains a `validation { }` method alongside
the existing `privacy { }` method. The `{Entity}WriteCandidate` is
shared between privacy and validation contexts.

Validation contexts expose a read-only client. Validators can use its
`byId` methods, `query { ... }`, and indexed query helpers, but cannot
create, update, or delete entities.

## Relationship to Other Concepts

| Concept | Purpose | Runs | Bypassed by System? |
|---------|---------|------|---------------------|
| Field validation | Per-field constraints (minLength, max, etc.) | Before privacy | No |
| Privacy | Authorization — who can perform the operation | Before validation | Yes |
| Entity validation | Cross-field / cross-entity invariants | After privacy | No |
| Hooks | Side effects (timestamps, logging, notifications) | Before validation + privacy (mutate), after write (react) | No |

## Examples

### Cross-field validation

```kotlin
class StartBeforeEnd : EventCreateValidationRule {
    override fun validate(ctx: EventCreateValidationContext): ValidationDecision =
        if (ctx.candidate.startTime >= ctx.candidate.endTime) {
            ValidationDecision.Invalid("start time must be before end time")
        } else {
            ValidationDecision.Valid
        }
}
```

### State transition validation

```kotlin
class ValidStatusTransition : OrderUpdateValidationRule {
    private val allowed = mapOf(
        Status.PENDING to setOf(Status.CONFIRMED, Status.CANCELLED),
        Status.CONFIRMED to setOf(Status.SHIPPED, Status.CANCELLED),
        Status.SHIPPED to setOf(Status.DELIVERED),
    )

    override fun validate(ctx: OrderUpdateValidationContext): ValidationDecision {
        val from = ctx.before.status
        val to = ctx.candidate.status
        if (from == to) return ValidationDecision.Valid
        val valid = allowed[from] ?: emptySet()
        return if (to in valid) ValidationDecision.Valid
        else ValidationDecision.Invalid("cannot transition from $from to $to")
    }
}
```

### Delete guard

```kotlin
class CannotDeleteWithOpenInvoices : UserDeleteValidationRule {
    override fun validate(ctx: UserDeleteValidationContext): ValidationDecision {
        val openCount = ctx.client.invoices.query {
            where(Invoice.userId eq ctx.entity.id and (Invoice.status eq Status.OPEN))
        }.rawCount()
        return if (openCount > 0) {
            ValidationDecision.Invalid("user has $openCount open invoice(s)")
        } else {
            ValidationDecision.Valid
        }
    }
}
```
