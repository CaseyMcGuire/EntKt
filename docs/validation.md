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

Each operation's rules receive a typed context. Contexts include the
`EntClient` so validators can query the database (e.g. uniqueness
checks, referential integrity).

### CreateValidationContext

```kotlin
data class PostCreateValidationContext(
    val client: EntClient,
    val candidate: PostWriteCandidate,
)
```

### UpdateValidationContext

```kotlin
data class PostUpdateValidationContext(
    val client: EntClient,
    val before: Post,                    // current state of the entity (loaded by save())
    val requestedPatch: PostUpdatePatch, // caller/hook intent — FieldPatch entries
    val effectivePatch: PostUpdatePatch, // after framework update defaults
    val candidate: PostWriteCandidate,   // full after-state = before + effectivePatch
)
```

`requestedPatch` and `effectivePatch` carry per-field `FieldPatch<T>` entries
(`Unset` or `Set(value)`). Validators that want to know *what changed* should
read the patches; validators that check invariants on the *full after-state*
should read `candidate`.

By the time validation runs, the post-hook required-not-null check has
already fired, so a dirty + null required field would have thrown
`IllegalStateException` before reaching validation. Validators can treat
`FieldPatch.Set(value)` for required fields as having a non-null value
and `FieldPatch.Unset` as "not in this update".

### DeleteValidationContext

```kotlin
data class PostDeleteValidationContext(
    val client: EntClient,
    val entity: Post,
    val candidate: PostWriteCandidate,
)
```

Contexts do **not** include `PrivacyContext`. Privacy has already been
enforced by the time validators run — validators are viewer-agnostic.
If a rule cares about who is performing the operation, it belongs in
privacy, not validation.

The `client` in validation contexts is a **System-scoped client** —
generated evaluators pass a fixed `Viewer.System` client so that
validator reads bypass LOAD privacy:

```kotlin
// Generated evaluator wires a System-scoped client
val validationClient =
    client.withFixedPrivacyContextForInternalUse(PrivacyContext(Viewer.System))
val ctx = PostCreateValidationContext(validationClient, candidate)
```

This is important: if the caller-scoped client were passed instead,
validators that query (e.g. uniqueness checks) would be blocked by
LOAD privacy rules.

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
collected into a list and thrown together:

```kotlin
// Generated evaluator (pseudocode)
fun evaluateCreateValidation(client: EntClient, candidate: WriteCandidate) {
    val rules = validationConfig.createRules
    if (rules.isEmpty()) return
    val systemClient =
        client.withFixedPrivacyContextForInternalUse(PrivacyContext(Viewer.System))
    val ctx = CreateValidationContext(systemClient, candidate)
    val violations = rules.mapNotNull { rule ->
        when (val decision = rule.validate(ctx)) {
            is ValidationDecision.Valid -> null
            is ValidationDecision.Invalid -> decision
        }
    }
    if (violations.isNotEmpty()) {
        throw ValidationException("Post", violations)
    }
}
```

`Viewer.System` does **not** bypass validation. Validation enforces
data model invariants that apply regardless of who is performing the
operation. This differs from privacy, where `Viewer.System` bypasses
all checks.

## Execution Order

### Create

```
1.  beforeSave hooks
2.  beforeCreate hooks
3.  field extraction + defaults
4.  field validation (minLen, maxLen, etc.)
5.  build WriteCandidate
6.  privacy create
7.  validation create          ← NEW
8.  driver.insert()
9.  hydrate entity from row
10. afterCreate hooks
11. load privacy on returned entity
12. return entity
```

### Update

```
 1. syntactically empty patch → throw EntNoChangesException (before
    the owner-row load — request shape, not database state, so we
    don't leak whether the id exists)
 2. internal current-row load via driver.byId(id), bypassing LOAD
    privacy; missing row → save() returns null (saveOrThrow throws
    EntNotFoundException)
 3. beforeSave hooks (receive UserMutation)
 4. beforeUpdate hooks (receive UserUpdateHookContext with a fresh
    `patch` snapshot per hook; hooks may write through ctx.mutation
    or call ctx.mutation.unsetFoo() to remove entries)
 5. required-not-null check on dirty fields (after hooks, so a hook
    can repair an explicit `name = null` via unset or reassignment)
 6. build the canonical requested patch
 7. hook-cleared empty path: if all dirty fields were unset by hooks,
    run UPDATE privacy on the unchanged candidate, then throw
    EntNoChangesException (skip defaults, validation, driver write,
    afterUpdate, returned LOAD privacy)
 8. apply update defaults (e.g. updatedAt = updateDefaultNow()) to
    produce the effective patch
 9. field validation on the effective patch's Set entries (mutable
    fields only)
10. build the database write set from the effective patch — only Set
    entries are sent to driver.update; untouched columns are not
    round-tripped
11. build the full after-state WriteCandidate by folding the effective
    patch over `before`
12. privacy update          ← receives (before, requestedPatch,
    effectivePatch, candidate)
13. validation update       ← NEW; same context shape as privacy
14. driver.update()
15. hydrate entity from row
16. afterUpdate hooks
17. load privacy on returned entity
18. return entity
```

### Delete

```
1.  build WriteCandidate
2.  privacy delete
3.  validation delete          ← NEW
4.  beforeDelete hooks
5.  driver.delete()
6.  afterDelete hooks
```

Field validation runs before privacy because it validates local request
shape and generated schema constraints (minLen, maxLen, etc.) — these
do not read stored data. Entity validation runs after privacy to
prevent domain and data-existence leaks through validation errors
(e.g. "slug already exists" or "recipient not found").

## Validators That Query

Since validation contexts receive a System-scoped client, validators
can query the database without being blocked by LOAD privacy.

**Validators should be read-only.** The context exposes a full
`EntClient`, but validators must not create, update, or delete
entities — they answer "is this state valid?", not "make it valid."
Mutating inside a validator would bypass the calling operation's
hooks, privacy, and validation ordering.

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
        }.exists()
        return if (exists) ValidationDecision.Invalid("slug already taken")
        else ValidationDecision.Valid
    }
}

class AuthorExists : PostCreateValidationRule {
    override fun validate(ctx: PostCreateValidationContext): ValidationDecision {
        val author = ctx.client.users.byId(ctx.candidate.authorId)
        return if (author == null) ValidationDecision.Invalid("author does not exist")
        else ValidationDecision.Valid
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

## What Gets Generated

For each schema, the codegen emits validation infrastructure in a
separate `{Entity}Validation.kt` file alongside the existing
`{Entity}Privacy.kt`:

| Generated type | Purpose |
|----------------|---------|
| `{Entity}CreateValidationRule` | Typealias for create validation rules |
| `{Entity}UpdateValidationRule` | Typealias for update validation rules |
| `{Entity}DeleteValidationRule` | Typealias for delete validation rules |
| `{Entity}CreateValidationContext` | Context for create validators |
| `{Entity}UpdateValidationContext` | Context for update validators |
| `{Entity}DeleteValidationContext` | Context for delete validators |
| `{Entity}ValidationConfig` | Internal storage for validation rule lists |
| `{Entity}ValidationScope` | DSL scope inside `validation { }` |

The `{Entity}PolicyScope` gains a `validation { }` method alongside
the existing `privacy { }` method. The `{Entity}WriteCandidate` is
shared between privacy and validation contexts.

## Relationship to Other Concepts

| Concept | Purpose | Runs | Bypassed by System? |
|---------|---------|------|---------------------|
| Field validation | Per-field constraints (minLen, max, etc.) | Before privacy | No |
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
