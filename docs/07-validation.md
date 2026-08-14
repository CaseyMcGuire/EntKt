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

### EntValidationException

When one or more rules return `Invalid`, the save fails with
`MutationResult.Failed(EntValidationException)` containing all
violations:

```kotlin
class EntValidationException(
    val entityType: String,
    val operation: EntOperation,
    val violations: List<ValidationViolation>,  // non-empty
) : EntMutationException(MutationWriteState.NotPersisted, ...)

data class ValidationViolation(
    val message: String,
    val field: String? = null,
    val code: String? = null,
)
```

All violations are collected before the result is built, so API
consumers can display every problem at once rather than fixing them one
at a time. Each violation carries its `message`, optional `field`, and
optional `code` for programmatic access. The exception hardcodes
`writeState = NotPersisted` — validation always rejects before the
write — and `.getOrThrow()` throws it directly.

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
read-only `EntValidationReadClient` so validators can query the
database (e.g. uniqueness checks, referential integrity) — and only
query it. The write surface does not exist on that type, so a
validator that tries to create, update, or delete does not compile.

### CreateValidationContext

```kotlin
data class PostCreateValidationContext(
    val client: EntValidationReadClient,
    val candidate: PostWriteCandidate,
)
```

### UpdateValidationContext

```kotlin
data class PostUpdateValidationContext(
    val client: EntValidationReadClient,
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
already fired, so a dirty + null required field would have failed the
save with `MutationResult.Failed(EntValidationException)` before
reaching entity validation. Validators can treat
`FieldPatch.Set(value)` for required fields as having a non-null value
and `FieldPatch.Unset` as "not in this update".

### DeleteValidationContext

```kotlin
data class PostDeleteValidationContext(
    val client: EntValidationReadClient,
    val entity: Post,
    val candidate: PostWriteCandidate,
)
```

Contexts do **not** include `PrivacyContext`. Privacy has already been
enforced by the time validators run — validators are viewer-agnostic.
If a rule cares about who is performing the operation, it belongs in
privacy, not validation.

The `EntValidationReadClient` in validation contexts is read-only and its
reads bypass LOAD privacy, allowing invariant checks such as uniqueness and
referential integrity to inspect all relevant rows. Read interceptors still
apply, and validation performed inside `withTransaction` sees earlier writes
from the same transaction.

Privacy-rule contexts expose `EntPrivacyReadClient` instead, whose reads use
the caller's privacy context. Both concrete types implement the shared
`EntReadClient` interface: helpers that work correctly under either posture
can accept `EntReadClient` (and must then avoid raw terminals, which fail with
`ReadResult.Failed(IllegalStateException)` on privacy readers), while helpers
that rely on privacy-bypassing reads should
accept `EntValidationReadClient` so they cannot be handed a viewer-scoped
reader. See [Privacy → Operation Contexts](06-privacy.md#operation-contexts).

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
collected into one `EntValidationException` carried by
`MutationResult.Failed`.

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
7. LOAD privacy on the returned entity (saveAndLoad() only)
```

### Update

```
1. current-entity load (absent target → Failed(EntTargetAbsentException))
2. beforeSave and beforeUpdate hooks
3. required-field checks, update defaults, and field validation
4. UPDATE privacy
5. UPDATE entity validation
6. persistence
7. afterUpdate hooks
8. LOAD privacy on the returned entity (saveAndLoad() only)
```

An assignment-free update — an empty request, or one whose hooks removed
every requested change — is not an error: after the target is confirmed
to exist, UPDATE privacy and entity validation still run against the
unchanged candidate, persistence and `afterUpdate` are skipped, and the
save completes as `Success`.

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
exposes `EntValidationReadClient`, whose per-entity repos carry
`findById`, the full `query { }` DSL with every terminal
(`all` / `firstOrNull`, `rawCount` / `rawExists`, the raw aggregates,
`explain*`), and the generated index helpers — and nothing else.
`create`, `update`, `save`, the `delete*` family, edge mutators, and
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
        }.rawExists().getOrThrow()
        return if (exists) ValidationDecision.Invalid("slug already taken")
        else ValidationDecision.Valid
    }
}

class AuthorExists : PostCreateValidationRule {
    override fun validate(ctx: PostCreateValidationContext): ValidationDecision {
        val author = ctx.client.users.findById(ctx.candidate.authorId).getOrThrow()
        return if (author == null) ValidationDecision.Invalid("author does not exist")
        else ValidationDecision.Valid
    }
}
```

A read failure inside a rule is fine to surface with `.getOrThrow()`:
the calling save's terminal captures the thrown exception as
`Failed(EntUnexpectedMutationException)`, preserving it as the cause.

Index helpers work too — they are query sugar and equally read-only:

```kotlin
class UniqueEmail : UserCreateValidationRule {
    override fun validate(ctx: UserCreateValidationContext): ValidationDecision =
        if (ctx.client.users.indexes.email(ctx.candidate.email).find().getOrThrow() != null) {
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
item runs the full validation pipeline independently, and the whole
operation is atomic: it shares one transaction (the caller's, or an
EntKt-owned one when the caller has none), so the first validation
failure aborts the batch with `Failed(EntValidationException)` and a
confirmed rollback leaves no committed subset.

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
| `{Entity}ReadRepo` | Read-only repo exposed to validators (`findById`, `query { }`, index helpers) |
| `EntValidationReadClient` | Read client in validation contexts — privacy-bypassing reads (schema-set-level) |
| `EntReadClient` | Shared read-only interface both posture clients implement (schema-set-level) |

The `{Entity}PolicyScope` gains a `validation { }` method alongside
the existing `privacy { }` method. The `{Entity}WriteCandidate` is
shared between privacy and validation contexts.

Validation contexts expose the read-only `EntValidationReadClient`.
Validators can use its `findById`, `query { ... }`, and indexed
query helpers, but cannot create, update, or delete entities.

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
        }.rawCount().getOrThrow()
        return if (openCount > 0) {
            ValidationDecision.Invalid("user has $openCount open invoice(s)")
        } else {
            ValidationDecision.Valid
        }
    }
}
```
