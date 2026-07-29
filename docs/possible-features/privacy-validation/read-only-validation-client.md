# RFC: Read-Only Validation Client

## Status

Possible future feature. This is not implemented.

Revised 2026-07-28 for implementation-readiness: the original sketch's
`EntReadClient(driver, privacyContext)` shape was too small for what
generated reads actually depend on, and it left query ownership
unspecified. This revision names the type `EntValidationReadClient`,
introduces a generated read-runtime contract that both clients satisfy,
pins the exact read surface and adapter semantics, and specifies the
negative compile-test strategy.

## Summary

Replace the full `EntClient` exposed in generated validation contexts with a
read-only, System-scoped validation client.

Today validators receive a client they can use to query the database, but that
client is a full generated `EntClient`. Documentation says validators should be
read-only, but the type system still allows writes.

This RFC makes validator intent enforceable:

```kotlin
data class PostCreateValidationContext(
    val client: EntValidationReadClient,
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
- Reuse the generated query machinery structurally — queries declare the
  read-runtime contract they need, rather than the read client smuggling a
  full `EntClient` behind a facade.

## Non-Goals

- Do not remove database reads from validators.
- Do not make validators responsible for authorization.
- Do not replace database constraints with validation.
- Do not add arbitrary SQL access to validators in this RFC.
- Do not build a general application-facing read facade. The type is named
  `EntValidationReadClient` because its privacy posture (fixed
  `PrivacyBypass`) is only correct for invariant checks. A general
  viewer-scoped read facade would be a separate RFC and could subsume the
  read-runtime contract introduced here.

## Why `(driver, privacyContext)` is not enough

Generated reads depend on more state than a driver and a privacy context.
The existing fixed-context clone (`withFixedPrivacyContextForInternalUse`,
see `ClientGenerator.buildFixedContextBody`) enumerates exactly what a
functioning read path carries today:

- `privacyContextProvider` (the fixed context)
- `transactionRequirement`
- `visibleOverfetchLimit` (the visible-family overfetch cap)
- `entityInterceptors` (per-entity + global read interceptors)
- per-repo hooks, privacy config, and validation config
  (`copyHooksFrom` / `copyPrivacyFrom` / `copyValidationFrom`)

Query terminals additionally reach into the client for
`currentPrivacyContext()` and per-repo LOAD-privacy evaluation
(`c.<repo>.hasLoadPrivacy()` / `evaluateLoadPrivacy(...)`), and both the
eager-load blocks and the edge-predicate walker construct sibling queries by
passing the client through. Any read client must carry all of this — which is
what the read-runtime contract below makes explicit.

## Proposed API

### The read-runtime contract

Generate an internal interface that names exactly what generated queries
need from their host. Both `EntClient` and `EntValidationReadClient`
implement it:

```kotlin
interface EntReadRuntime {
    fun currentPrivacyContext(): PrivacyContext
    val visibleOverfetchLimit: Int

    @EntktInternal
    val entityInterceptors: EntInterceptorsConfig

    // One accessor per entity, typed to a narrow generated per-entity
    // read surface: hasLoadPrivacy() / evaluateLoadPrivacy(...) — the
    // only repo members query terminals call.
    val users: UserReadSurface
    val posts: PostReadSurface
}
```

`EntReadRuntime` is generated per schema set (like `EntClient`), and the
per-entity read surfaces are small interfaces the existing repos already
satisfy. `entityInterceptors` keeps its current `@EntktInternal` guard —
today's property is `@EntktInternal internal` because the raw
`EntInterceptorsConfig` permits wrong-entity registration via unchecked
scope-key casts, and generated queries reach it through file-level
`@OptIn`; hoisting it onto a contract interface must not widen that
access (interface members can't be `internal`, so the opt-in marker is
the guard that remains). The interface name deliberately avoids the
overloaded terms `Context` (QueryContext, PrivacyContext, validation
contexts) and `Client`.

### Generated queries accept the contract

Generated query classes change their constructor from
`(driver: Driver, client: EntClient?)` to
`(driver: Driver, client: EntReadRuntime?)`. Every internal use —
`requireClient()`, `runReadInterceptors`, the visible-family cap, the
eager-load sibling queries, the edge-predicate walker's target queries —
already stays within the contract's surface, so the change is a signature
change, not a behavior change. `EntClient` satisfies the contract, so repos
(`lateinit var client: EntClient`) construct queries exactly as today.

Verification for this step: regenerated output must differ from the
baseline only in the query constructor/parameter type names.

### The validation read client

```kotlin
class EntValidationReadClient internal constructor(...) : EntReadRuntime {
    val users: UserValidationReadRepo
    val posts: PostValidationReadRepo
}
```

Each validation read repo exposes the read surface only:

- the `byId` family (`byIdOrNull` / `byIdOrThrow` / `byIdOrError`,
  `visibleByIdOrNull`)
- the full query DSL (`query { }`) with **all** terminals — `all` /
  `first` / `visible` families, `rawCount` / `visibleCount`,
  `rawExists` / `visibleExists`, raw aggregates, and the `explain*`
  methods. These are read-only by construction; excluding some would be
  an arbitrary seam.
- generated index helpers (`byEmail(...)`-style stages) — they are query
  sugar and equally read-only.

Not present, and therefore not compilable from a validator:

- `create` / `update` / `save` / `deleteOrThrow` / `deleteOrError` /
  `deleteByIdOrError` / `deleteMany` / edge mutators
- `withTransaction` (validation runs inside the surrounding operation's
  transaction context)
- hook, policy, validation, or interceptor registration; configuration
  setters

Generated validation contexts use this client:

```kotlin
data class PostCreateValidationContext(
    val client: EntValidationReadClient,
    val candidate: PostWriteCandidate,
)
```

### Adapter semantics

`client.asValidationReadClient()` is generated on `EntClient` and must
preserve, exactly:

- the **same driver instance** — called on the operation's current client,
  so a transaction-scoped client yields a transaction-scoped read client
  (read-your-writes inside transactions is preserved)
- a fixed `PrivacyContext(Viewer.PrivacyBypass("validation read"))` — with
  writes now uncompilable, the label is finally honest
- the same `entityInterceptors` (read-path interceptors still run)
- the same per-repo privacy config (`hasLoadPrivacy` /
  `evaluateLoadPrivacy` behavior identical to the host client's)
- the same `visibleOverfetchLimit` and `transactionRequirement`

Implementation note: this is the same state set
`withFixedPrivacyContextForInternalUse` already copies; the adapter should
share that mechanism rather than duplicating the list.

### Alternative considered: delegation wrapper

A thinner design wraps the fixed-context `EntClient` clone in a read-only
facade whose repos pass the *wrapped full client* into query construction.
It requires no query signature change, but it keeps a full-capability
client reachable one private field away inside objects handed to validator
code, and the read-only guarantee stops at the facade instead of being
structural. Rejected: the contract keeps generated queries honest about
what they actually need, at the cost of one mechanical signature change.

## Privacy Semantics

Validation reads keep the current behavior: generated evaluators pass a
fixed System-scoped read client so invariants are not blocked by LOAD
privacy.

```kotlin
val validationClient = client.asValidationReadClient()
val ctx = PostCreateValidationContext(validationClient, candidate)
```

`Viewer.PrivacyBypass` bypasses privacy checks, but it does not bypass
validation of the outer operation.

## Transaction Semantics

When validation runs inside a transaction-scoped client, the read-only
validation client must use the same transaction-scoped driver. The adapter
achieves this by construction — it is invoked on the operation's current
client, whichever driver that client is pinned to.

## Interceptor Semantics

Read-path interceptors still run for validation queries. Since validation
uses System privacy, interceptor behavior that depends on `PrivacyContext`
sees `Viewer.PrivacyBypass`.

Open question: should there be a generated internal read mode for validation
that bypasses application query interceptors for invariants? V1 should preserve
current behavior unless a concrete need appears.

## Negative compile-test strategy

The "validator cannot write" guarantee is a compile-time claim, so it gets
compile-time tests using the in-repo kctfork pattern
(`codegen/src/test/.../JsonCompileFailTest.kt`): generate the full output
for a fixture schema, compile a validator snippet in-process with the test
classpath inherited, and assert on the result.

- Negative: a snippet whose validator body calls
  `ctx.client.posts.create { ... }` (and one each for `update`, `delete`,
  edge mutators) must produce `COMPILATION_ERROR` with an unresolved
  reference on the write method.
- Positive twin: the same harness compiles a validator using `query { }`,
  a terminal, and a `byId` read — proving the failure above is the missing
  write surface, not a broken snippet.

## Migration Plan

1. Generate `EntReadRuntime` + per-entity read surfaces; make `EntClient`
   implement them.
2. Change generated query constructors to accept `EntReadRuntime?`;
   verify regenerated output differs only in those type names.
3. Generate `EntValidationReadClient` + per-entity validation read repos.
4. Generate `EntClient.asValidationReadClient()` sharing the
   fixed-context clone's state copy.
5. Change generated validation contexts from `EntClient` to
   `EntValidationReadClient`.
6. Add the compile-fail/compile-pass tests and update validation docs and
   examples.
7. Keep privacy contexts using the full client unless a separate RFC
   changes them.

## Open Questions

- Should hooks also get a read-only client by default, with an explicit escape
  hatch for side-effecting hooks? This RFC only changes validation.
- Should a future general read facade (viewer-scoped, not bypass-scoped)
  reuse `EntReadRuntime`? Nothing here should preclude it.

## Test Requirements

Before implementation, add tests for:

- validation contexts expose `EntValidationReadClient`
- validator code can call query, terminal, index-helper, and by-id methods
- validator code cannot call create/update/delete/edge-mutation methods
  (compile-fail harness above)
- validator reads use System privacy
- validator reads inside a transaction use the transaction-scoped driver
- read-path interceptors run for validator queries
- existing uniqueness/existence validation examples still work
