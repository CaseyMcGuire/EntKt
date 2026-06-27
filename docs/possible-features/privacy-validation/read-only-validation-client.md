# RFC: Read-Only Validation Client

## Status

Possible future feature. This is not implemented.

## Summary

Replace the full `EntClient` exposed in generated validation contexts with a
read-only, System-scoped validation client.

Today validators receive a client they can use to query the database, but that
client is a full generated `EntClient`. Documentation says validators should be
read-only, but the type system still allows writes.

This RFC makes validator intent enforceable:

```kotlin
data class PostCreateValidationContext(
    val client: EntReadClient,
    val candidate: PostWriteCandidate,
)
```

## Motivation

Validation rules answer "is this candidate state valid?" They should not create,
update, or delete entities as a side effect.

Allowing writes from validators is surprising because those writes:

- occur inside another operation's validation phase
- may bypass the caller's intended action boundary
- make transaction behavior harder to reason about
- can recursively trigger hooks, privacy, and validation
- turn a predicate-like API into a side-effecting API

The current docs warn against mutation, but a clear API should prevent it.

## Goals

- Let validators perform database reads for uniqueness and existence checks.
- Keep validator reads System-scoped so LOAD privacy does not block invariants.
- Prevent validators from calling generated create, update, delete, or edge
  mutation APIs.
- Preserve transaction scoping for reads when validation runs inside a
  transaction.

## Non-Goals

- Do not remove database reads from validators.
- Do not make validators responsible for authorization.
- Do not replace database constraints with validation.
- Do not add arbitrary SQL access to validators in this RFC.

## Proposed API

Generate a read-only client facade:

```kotlin
class EntReadClient internal constructor(
    internal val driver: Driver,
    internal val privacyContext: PrivacyContext,
) {
    val users: UserReadRepo
    val posts: PostReadRepo

    fun <T> withPrivacyContext(
        privacy: PrivacyContext,
        block: EntReadClient.() -> T,
    ): T
}
```

Each read repo exposes read/query APIs only:

```kotlin
class PostReadRepo internal constructor(...) {
    fun byIdOrNull(id: Long): Post?
    fun byIdOrThrow(id: Long): Post
    fun byIdOrError(id: Long): EntResult<Post>
    fun visibleByIdOrNull(id: Long): Post?
    fun query(block: PostQuery.() -> Unit = {}): PostQuery
}
```

No write methods are present:

```kotlin
client.posts.create { ... }      // does not compile
client.posts.update(id) { ... }  // does not compile
client.posts.deleteOrThrow(post) // does not compile
```

Generated validation contexts use this read client:

```kotlin
data class PostCreateValidationContext(
    val client: EntReadClient,
    val candidate: PostWriteCandidate,
)
```

## Privacy Semantics

Validation reads should keep the current behavior: generated evaluators pass a
fixed System-scoped read client so invariants are not blocked by LOAD privacy.

```kotlin
val validationClient = client.asReadOnlySystemClient()
val ctx = PostCreateValidationContext(validationClient, candidate)
```

`Viewer.PrivacyBypass` bypasses privacy checks, but it does not bypass validation of
the outer operation.

## Transaction Semantics

When validation runs inside a transaction-scoped client, the read-only
validation client must use the same transaction-scoped driver.

This preserves current read-your-writes behavior inside transactions while
still preventing validator writes.

## Interceptor Semantics

Read-path interceptors should still run for validation queries unless the
validator explicitly uses a raw driver-level escape hatch. Since validation
uses System privacy, interceptor behavior that depends on `PrivacyContext`
should see `Viewer.PrivacyBypass`.

Open question: should there be a generated internal read mode for validation
that bypasses application query interceptors for invariants? V1 should preserve
current behavior unless a concrete need appears.

## Migration Plan

1. Generate read-only repo/client types beside the existing generated client.
2. Change generated validation contexts from `EntClient` to `EntReadClient`.
3. Add an adapter from full client to System-scoped read client.
4. Update validation docs and examples.
5. Keep privacy contexts using full client unless a separate RFC changes them.

## Open Questions

- Should hooks also get a read-only client by default, with an explicit escape
  hatch for side-effecting hooks? This RFC only changes validation.
- Should the read-only client expose transaction helpers? V1 probably should
  not; validation runs inside the surrounding operation's transaction context.
- Should the read-only client expose raw count/exists helpers? Yes, if those
  are already generated read methods.

## Test Requirements

Before implementation, add tests for:

- validation contexts expose the read-only client type
- validator code can call query and by-id methods
- validator code cannot call create/update/delete methods
- validator reads use System privacy
- validator reads inside a transaction use the transaction-scoped driver
- existing uniqueness/existence validation examples still work
