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
    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: PostCreateValidationItem,
    ): ValidationDecision =
        if (item.candidate.published && item.candidate.body.isNullOrBlank()) {
            ValidationDecision.Invalid("published posts must have a body", field = "body")
        } else {
            ValidationDecision.Valid
        }
}

class CannotDeletePublishedPost : PostDeleteValidationRule {
    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: PostDeleteValidationItem,
    ): ValidationDecision =
        if (item.entity.published) {
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

A rule receives phase-shared context separately from one generated item.
It automatically adapts to batch evaluation by visiting items serially in
encounter order:

```kotlin
class ValidationRuleContext<out Client>(
    val client: Client,
)

fun interface ValidationRule<in Client, in Item> :
    BatchValidationRule<Client, Item> {

    fun validate(
        context: ValidationRuleContext<Client>,
        item: Item,
    ): ValidationDecision

    override fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision> =
        batch.decideEach { validate(context, it) }
}
```

Each operation gets its own item type, shared by scalar and batch validators,
so rules are type-safe for the operation they guard.

### BatchValidationRule

Use `batchValidationRule` when one validator should see the complete ordered
phase list, commonly to replace per-item reads with one set-based lookup:

```kotlin
import entkt.runtime.validation.batchValidationRule
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions

interface BatchValidationRule<in Client, in Item> {
    fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision>
}

fun <Client, Item> batchValidationRule(
    block: (
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ) -> RuleDecisions<ValidationDecision>,
): BatchValidationRule<Client, Item>

val uniqueSlugs: PostCreateBatchValidationRule =
    batchValidationRule { context, batch ->
        val existingSlugs = loadExistingSlugs(
            context.client,
            batch.map { it.candidate.slug },
        )
        batch.decideEach { item ->
            if (item.candidate.slug in existingSlugs) {
                ValidationDecision.Invalid("slug already taken", field = "slug")
            } else {
                ValidationDecision.Valid
            }
        }
    }

validation {
    create(uniqueSlugs)
}
```

`RuleBatch` is an immutable, read-only `List`, so a validator can inspect,
group, or sort its items while preparing a set-based read. It must build its
result with `batch.decideEach { ... }` or
`batch.decideEachIndexed { index, item -> ... }`. Those methods return read-only
`RuleDecisions` tied to that exact batch and invoke the decision block in
encounter order. This works for ID-less CREATE candidates and preserves
distinct decisions for duplicate or equal items; `decideEachIndexed` supplies
the index within the current callback batch.

This removes the error-prone API that accepted an arbitrary positional list;
it does not prove that application code computed the semantically correct
decision. Use the item supplied to the `decideEach` block rather than consuming
a separately reordered decision iterator. `RuleDecisions` is readable as a
`List`, and `RuleBatch.from(items)` creates a copied batch for direct decision
tests. A complete generated-rule test must also supply the matching shared
context and read client. A rule decorator must transform delegated decisions with
`result.mapDecisions { ... }`; that operation preserves the delegated result's
batch identity, so a stale cached result remains rejectable. Do not copy a
delegated decision list back through the current batch. Decisions from a test
batch remain bound to it.

Returning decisions created by another batch is an operational
`EntBatchRuleContractException`, not an invalid decision. Java or unchecked
code that returns `null` instead of `RuleDecisions`, or returns a null/invalid
decision, receives the same contract error. Scalar
and batch validators use the same `create`, `update`, and `delete` registration
names in Kotlin and one shared registration order. Generated batch overloads
use JVM names such as `createBatchRule` so Java lambdas remain unambiguous. A
batch validator receives a singleton `RuleBatch` for a scalar mutation and is
not invoked for an empty phase.

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

- `create(vararg scalarRules)` or `create(batchRule)` — run before insert
- `update(vararg scalarRules)` or `update(batchRule)` — run before update
- `delete(vararg scalarRules)` or `delete(batchRule)` — run before delete

Register multiple batch rules with repeated calls. There are no
`createBatch`-style methods. There is no `load` validation — validation guards
writes, not reads.

## Operation Items

Every validator receives a shared `ValidationRuleContext` containing the
read-only `ReadOnlyEntClient`. Generated items contain only values that
differ per candidate. The write surface does not exist on the shared client,
so a validator that tries to create, update, or delete does not compile. One
phase constructs one rule context and passes that exact instance to every
validator; each validator still receives fresh defensive item snapshots.

### CreateValidationItem

```kotlin
data class PostCreateValidationItem(
    val candidate: PostWriteCandidate,
)
```

### UpdateValidationItem

```kotlin
data class PostUpdateValidationItem(
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
See [Privacy → UpdatePrivacyItem](06-privacy.md#updateprivacyitem)
for the field semantics. A typical validator pattern: reject `remove` calls
that name unknown / forbidden target ids by inspecting
`item.edgeChanges.tags.requestedRemoves` — the literal call log surfaces
the id even when the database effect is a no-op.

By the time validation runs, the post-hook required-not-null check has
already fired, so a dirty + null required field would have failed the
save with `MutationResult.Failed(EntValidationException)` before
reaching entity validation. Validators can treat
`FieldPatch.Set(value)` for required fields as having a non-null value
and `FieldPatch.Unset` as "not in this update".

### DeleteValidationItem

```kotlin
data class PostDeleteValidationItem(
    val entity: Post,
    val candidate: PostWriteCandidate,
)
```

Validation rule contexts do **not** include the caller's `ViewerContext`.
Privacy has already been enforced by the time validators run — validators are
viewer-agnostic. If a rule cares about who is performing the operation, it
belongs in privacy, not validation. They instead expose
`readViewerContext`, initialized by the framework as
`ViewerContext.privacyBypass_DANGEROUS("validation read")`, solely for
explicit reads through the shared read-only client.

The `ReadOnlyEntClient` in `ValidationRuleContext` is read-only and its
materializing reads bypass LOAD privacy when passed `context.readViewerContext`,
allowing invariant checks such as uniqueness and referential integrity to
inspect all relevant rows. Read interceptors still apply, and validation
performed inside `withTransaction` sees earlier writes from the same transaction.

`PrivacyRuleContext` exposes `ReadOnlyEntClient` and `viewerContext`
instead; nested terminals normally pass that caller context explicitly. Both
rule contexts expose the same stable client type. The context passed to a
terminal—not the client type—determines LOAD privacy. Raw terminals always skip
LOAD privacy and entity materialization. Helpers that require bypassed
materialization should take an explicit `ViewerContext` and callers should pass
`context.readViewerContext`.
See [Privacy → Operation Items](06-privacy.md#operation-items).

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

Each validator receives its own snapshot. Generated `bytes()` values are copied
directly, and typed JSON values are round-tripped through the driver's
configured JSON mapper, including values inside update patches. Mutating a
`ByteArray` or a mutable collection nested in JSON from one validation item
cannot change the pending database write or another validator's input.

## Evaluation Semantics

All rules for an operation run unconditionally. In a multi-item phase,
evaluation is rule-major: rule one receives every item in encounter order,
then rule two receives every item. `Invalid` decisions are retained by
original item position and appended in rule registration order. A scalar rule's
default adapter is invoked once per item; an explicit batch rule is invoked
once with the list. Rules are not run concurrently.

For a scalar mutation, every invalid result is collected into one
`EntValidationException` carried by `MutationResult.Failed`. For a bulk
mutation with several invalid items, the terminal reports the first invalid
item in encounter order and includes every violation collected for that item;
it does not return a partial-success or multi-item error payload. A thrown rule
exception stops the phase at that registered rule rather than becoming a
validation decision.

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

Validators can query without being blocked by LOAD privacy by explicitly
passing `context.readViewerContext`.

**Validators are read-only — by type, not by convention.** The context
exposes `ReadOnlyEntClient`, whose per-entity repos carry
`findById`, the full `query { }` DSL with every terminal
(`all` / `firstOrNull`, `rawCount` / `rawExists`, and the raw aggregates),
and the generated index helpers — and nothing else.
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
    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: PostCreateValidationItem,
    ): ValidationDecision {
        val exists = context.client.posts.query {
            where(Post.slug eq item.candidate.slug)
        }.rawExists(context.readViewerContext).getOrThrow()
        return if (exists) ValidationDecision.Invalid("slug already taken")
        else ValidationDecision.Valid
    }
}

class AuthorExists : PostCreateValidationRule {
    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: PostCreateValidationItem,
    ): ValidationDecision {
        val author = context.client.users
            .findById(context.readViewerContext, item.candidate.authorId)
            .getOrThrow()
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
    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: UserCreateValidationItem,
    ): ValidationDecision =
        if (context.client.users.indexes.email(item.candidate.email)
                .find(context.readViewerContext).getOrThrow() != null
        ) {
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
    update(UniqueSlugOnUpdate())  // excludes item.before.id
}
```

## Bulk Operations

`createMany` and `deleteMany` evaluate entity validation once per registered
rule over the complete candidate list. Their privacy phase finishes for every
candidate before entity validation begins, so a denial on a later item takes
precedence over a validation failure on an earlier item. Any invalid candidate
fails the operation before its target insert or delete statement. The database
work shares one transaction (the caller's, or an EntKt-owned transaction), and
a confirmed rollback leaves no committed subset.

## Generated Validation API

For each schema, entkt provides:

| Public type | Purpose |
|-------------|---------|
| `{Entity}CreateValidationRule` | Typealias for create validation rules |
| `{Entity}UpdateValidationRule` | Typealias for update validation rules |
| `{Entity}DeleteValidationRule` | Typealias for delete validation rules |
| `{Entity}CreateBatchValidationRule` | Typealias for batch create validation rules |
| `{Entity}UpdateBatchValidationRule` | Typealias for batch update validation rules |
| `{Entity}DeleteBatchValidationRule` | Typealias for batch delete validation rules |
| `ValidationRuleContext<Client>` | Shared validation read client for one evaluation phase |
| `{Entity}CreateValidationItem` | Per-candidate input for create validators |
| `{Entity}UpdateValidationItem` | Per-entity input for update validators |
| `{Entity}DeleteValidationItem` | Per-entity input for delete validators |
| `{Entity}ValidationScope` | DSL scope inside `validation { }` |
| `{Entity}ReadRepo` | Read-only repo exposed to validators (`findById`, `query { }`, index helpers) |
| `ReadOnlyEntClient` | Stable read-only client shared by validation and privacy rule contexts; each terminal requires an explicit `ViewerContext` (schema-set-level) |

The `{Entity}PolicyScope` gains a `validation { }` method alongside
the existing `privacy { }` method. The `{Entity}WriteCandidate` is
shared between privacy and validation items.

`ValidationRuleContext` exposes the read-only `ReadOnlyEntClient`.
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
    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: EventCreateValidationItem,
    ): ValidationDecision =
        if (item.candidate.startTime >= item.candidate.endTime) {
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

    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: OrderUpdateValidationItem,
    ): ValidationDecision {
        val from = item.before.status
        val to = item.candidate.status
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
    override fun validate(
        context: ValidationRuleContext<ReadOnlyEntClient>,
        item: UserDeleteValidationItem,
    ): ValidationDecision {
        val openCount = context.client.invoices.query {
            where(Invoice.userId eq item.entity.id and (Invoice.status eq Status.OPEN))
        }.rawCount(context.readViewerContext).getOrThrow()
        return if (openCount > 0) {
            ValidationDecision.Invalid("user has $openCount open invoice(s)")
        } else {
            ValidationDecision.Valid
        }
    }
}
```
