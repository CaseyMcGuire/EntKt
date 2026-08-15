# RFC: Posture-Specific Read Client Types

## Status

Implemented 2026-07-29. Generated `EntReadClient` is now the shared
read-repository interface; validation contexts expose the concrete
`EntValidationReadClient` and privacy contexts the concrete
`EntPrivacyReadClient`, both delegating to one internal
`EntReadClientImpl` that owns repository construction and the
`EntReadRuntime` contract. The arbitrary-context
`asReadClientForInternalUse(context)` adapter is replaced by
`asValidationReadClientForInternalUse()` (fixes
`PrivacyBypass("validation read")`) and
`asPrivacyReadClientForInternalUse(privacy)` (freezes the caller's
context); both call one private `readClientImpl(context)` builder.
At implementation time, repos, queries, and the runtime raw-terminal gate were
unchanged, so this type split did not change read results.

**Follow-up 2026-08-14 — raw reads unified across postures.** The runtime
raw-terminal gate was subsequently removed. `rawCount`, `rawExists`, and raw
aggregates are now explicit storage-level reads on both concrete client types:
they run interceptors but skip entity materialization and LOAD privacy.
Materializing privacy-client reads remain viewer-scoped. The proposed narrowed
query hierarchy was
[rejected](../../possible-features/privacy-validation/privacy-safe-query-surfaces.md)
because privacy rules are trusted authorization code and raw reads are useful
for control-plane facts and avoiding recursive LOAD evaluation. References to
the gate below describe the historical design this RFC originally implemented.

Pinned by `codegen`'s `ReadClientGeneratorTest` (generated shape:
interface, distinct non-alias wrappers, guarded internal constructors,
no public exposure of the impl), `ReadClientPostureCompileTest`
(cross-posture helper calls are type mismatches; an `EntReadClient`
helper accepts both postures), the updated
`ValidationReadClientCompileTest` / `PrivacyReadClientCompileTest`
(write-surface negatives and the per-adapter opt-in gate), and
`integration-tests`' `ValidationReadClientIntegrationTest` /
`PrivacyReadClientIntegrationTest` (behavior preservation: the retyped
concrete pins, plus new tests driving eager loads, traversals, and
index helpers through both posture clients — bypassed on the
validation side, viewer-scoped on the privacy side). See
[Validation → Operation Contexts](../../07-validation.md#operation-contexts)
and [Privacy → Operation Contexts](../../06-privacy.md#operation-contexts)
for the user-facing documentation, and the
[breaking-changes entry](../../breaking-changes/index.md) for caller
migration.

## Summary

Replace the single generated concrete `EntReadClient` with:

- a shared `EntReadClient` interface
- `EntValidationReadClient`, whose reads bypass LOAD privacy
- `EntPrivacyReadClient`, whose reads use the caller's viewer

Both concrete clients implement the shared interface and delegate to one
internal implementation. Generated repositories and queries remain shared.

Generated contexts expose the concrete type whose semantics they use:

```kotlin
data class PostCreateValidationContext(
    val client: EntValidationReadClient,
    val candidate: PostWriteCandidate,
)

data class PostLoadPrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
    val entity: Post,
)
```

Helpers that are genuinely posture-agnostic can continue to accept
`EntReadClient`:

```kotlin
fun loadOrganization(client: EntReadClient, id: Long): Organization? =
    client.organizations.byIdOrNull(id)
```

Helpers that rely on one posture name it explicitly:

```kotlin
fun validateUniqueness(client: EntValidationReadClient, slug: String): Boolean = ...
fun checkMembership(client: EntPrivacyReadClient, groupId: Long): Boolean = ...
```

## Background

The implemented
[Read-Only Validation Client](../../implemented-features/privacy-validation/read-only-validation-client.md)
introduced a read-only, privacy-bypass-scoped client for validators.
The later
[Read-Only Privacy Rule Client](../../implemented-features/privacy-validation/read-only-privacy-client.md)
generalized it into one concrete `EntReadClient`, with privacy posture carried
as instance state.

That one-type decision assumed both uses had the same read capability and
differed only by context. Post-implementation review added a raw-terminal
gate:

- validation readers are privacy-bypass-scoped and may use `rawCount`,
  `rawExists`, and raw aggregates
- privacy-rule readers are viewer-scoped, and those terminals throw
  `IllegalStateException`

The same concrete type therefore now describes instances with meaningfully
different behavior. The difference is authorization-relevant and should be
visible in generated context types.

## Motivation

`EntReadClient` currently communicates only that writes are unavailable. It
does not communicate what its reads are authorized to observe.

That creates several least-surprise problems:

- A helper accepting the concrete type does not state whether it expects
  viewer-scoped or privacy-bypassing reads.
- The same raw terminal succeeds in validation and throws in a privacy rule.
- Documentation must repeatedly explain hidden instance posture.
- A future compile-time privacy-safe query surface has no distinct public type
  on which to expose narrower capabilities.

Separate concrete types make the semantic distinction visible while a shared
interface preserves reuse where the distinction is irrelevant.

## Goals

- Make validation and privacy-rule read posture visible in generated types.
- Preserve one shared repository and query implementation.
- Let posture-agnostic helpers accept a common `EntReadClient` interface.
- Prevent a helper requiring validation semantics from accepting a privacy
  reader, and vice versa.
- Preserve transaction scoping, interceptors, LOAD-privacy evaluation, and
  all existing read results.
- Keep the current runtime raw-terminal gate on privacy readers.
- Create a clean future boundary for statically narrowing privacy-reader
  queries.

## Non-Goals

- Do not create parallel generated query implementations.
- Do not create separate per-entity validation and privacy repository classes.
- Do not remove raw terminals from privacy-reader query types in this RFC.
- Do not change which privacy context validators or privacy rules use.
- Do not make either concrete client constructible as supported application
  API.
- Do not add a general `client.readOnly()` application facade.
- Do not change hook contexts.

## Proposed API

### Shared interface

The generated `EntReadClient` name remains, but changes from a concrete class
to an interface containing the shared read-only repository surface:

```kotlin
interface EntReadClient {
    val users: UserReadRepo
    val posts: PostReadRepo
    val organizations: OrganizationReadRepo
}
```

It does not extend or expose the framework-internal `EntReadRuntime`. The
interface is the public contract for helpers that work under either posture.

Per-entity `${Entity}ReadRepo` types remain shared. Their query and index
helpers continue to use the internal runtime supplied when the repository is
constructed.

### Concrete semantic types

Generate two public classes with guarded internal constructors:

```kotlin
class EntValidationReadClient @EntktInternal internal constructor(
    private val delegate: EntReadClientImpl,
) : EntReadClient by delegate

class EntPrivacyReadClient @EntktInternal internal constructor(
    private val delegate: EntReadClientImpl,
) : EntReadClient by delegate
```

The internal implementation type appears only in guarded internal
constructors and private properties; no public callable signature exposes it.
The public contract is:

- the classes are real, distinct Kotlin types, not type aliases
- both implement `EntReadClient`
- neither exposes writes, transactions, privacy re-scoping, or configuration
- application code receives them through generated rule contexts
- construction remains guarded by `internal` and `@EntktInternal`

Type aliases are insufficient because aliases do not create distinct types
and would not reject cross-posture helper calls.

### Context types

All generated validation contexts use `EntValidationReadClient`:

```kotlin
data class PostCreateValidationContext(
    val client: EntValidationReadClient,
    val candidate: PostWriteCandidate,
)

data class PostUpdateValidationContext(
    val client: EntValidationReadClient,
    // ...
)

data class PostDeleteValidationContext(
    val client: EntValidationReadClient,
    // ...
)
```

All generated privacy contexts use `EntPrivacyReadClient`:

```kotlin
data class PostLoadPrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
    val entity: Post,
)

data class PostCreatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
    // ...
)

data class PostUpdatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
    // ...
)

data class PostDeletePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
    // ...
)
```

The context property remains named `client`. Call sites retain the familiar
`ctx.client.users...` shape while the static type communicates its semantics.

## Shared Implementation

Generate one internal implementation that owns the existing read state and
implements both the public interface and internal runtime contract:

```kotlin
@EntktInternal
internal class EntReadClientImpl(
    // driver, fixed privacy context, interceptors, limits, repo hosts...
) : EntReadClient, EntReadRuntime {
    override val users: UserReadRepo = ...
    override val posts: PostReadRepo = ...

    override fun currentPrivacyContext(): PrivacyContext = ...
    override fun checkPrivacyBypassingRead(terminal: String) = ...
}
```

`EntValidationReadClient` and `EntPrivacyReadClient` delegate their
`EntReadClient` members to an `EntReadClientImpl`. Repositories keep their
runtime reference pointed at that implementation, so sibling queries, eager
loads, traversals, privacy evaluation, and the raw-terminal gate all see the
same fixed posture.

The concrete wrappers do not need to implement `EntReadRuntime`; that
framework-internal contract remains on the delegate used by generated
repositories and queries.

This composition avoids:

- duplicating `${Entity}ReadRepo`
- generating validation- and privacy-specific query classes
- copying repository initialization logic
- allowing the two semantic clients to drift

## Construction

Replace the general
`asReadClientForInternalUse(context)` evaluator call surface with two
posture-specific internal adapters:

```kotlin
@EntktInternal
internal fun asValidationReadClientForInternalUse(): EntValidationReadClient

@EntktInternal
internal fun asPrivacyReadClientForInternalUse(
    privacy: PrivacyContext,
): EntPrivacyReadClient
```

Both adapters call one private/internal builder for `EntReadClientImpl`.

The validation adapter fixes:

```kotlin
PrivacyContext(Viewer.PrivacyBypass("validation read"))
```

The privacy adapter fixes the operation's existing caller `PrivacyContext`.

Separate adapters prevent generated evaluators from accidentally constructing
the wrong semantic wrapper or passing an arbitrary context to the validation
reader.

## Behavioral Contract

### Validation reader

`EntValidationReadClient`:

- is read-only by type
- bypasses generated LOAD privacy
- retains read interceptors
- sees prior writes in the current transaction
- allows raw and visible terminals
- cannot re-scope its privacy context

### Privacy reader

`EntPrivacyReadClient`:

- is read-only by type
- evaluates materialized rows under the caller's LOAD privacy
- retains read interceptors
- sees prior writes in the current transaction
- keeps the current runtime rejection for raw terminals
- cannot re-scope its privacy context

No read result changes are intended. This RFC makes the existing difference
visible in types.

## Common-Interface Semantics

Accepting `EntReadClient` means the helper promises to work correctly under
either posture. Such helpers must not assume that raw terminals are available:

```kotlin
fun findParent(client: EntReadClient, id: Long): Parent? =
    client.parents.visibleByIdOrNull(id)
```

If a helper requires raw reads or privacy bypass, it must accept
`EntValidationReadClient`. If it is specifically part of an authorization
decision, it should accept `EntPrivacyReadClient`.

The common interface intentionally allows callers to erase the semantic
distinction when they have written posture-independent code. The concrete
context types make that erasure explicit rather than automatic.

## Raw-Terminal Boundary

This RFC does not attempt to remove raw terminals from the static query type
returned by `EntPrivacyReadClient`. Generated query builders and traversal
methods are currently shared across application, validation, and privacy
reads. Statically removing raw terminals only for privacy readers requires a
narrowed query graph, not merely a different client class.

Until that separate change is implemented:

- privacy-reader raw terminals continue to throw `IllegalStateException`
- validation-reader raw terminals continue to work
- the gate must execute before `*OrError` catches, preserving the existing
  fail-loud behavior

The distinct concrete types provide a future attachment point for a narrowed
privacy-safe query surface without requiring another context-type rename.

## Compatibility

This is a breaking generated-API change because context properties change
their concrete type and `EntReadClient` changes from a class to an interface.

Common source patterns remain valid:

```kotlin
fun helper(client: EntReadClient) = ...

class Rule : PostLoadPrivacyRule {
    override fun run(ctx: PostLoadPrivacyContext): PrivacyDecision {
        return helper(ctx.client)
    }
}
```

Code that names the concrete semantics must migrate:

- validator-only helpers use `EntValidationReadClient`
- privacy-rule-only helpers use `EntPrivacyReadClient`
- posture-independent helpers keep `EntReadClient`
- unsupported code constructing or depending on the concrete
  `EntReadClient` class must stop doing so

## Breaking Changes Log Requirement

Implementation is not complete until
[`docs/breaking-changes/index.md`](../../breaking-changes/index.md) contains a
newest-first entry under `## Unreleased`.

The entry must tell callers:

- `EntReadClient` is now an interface
- validation contexts expose `EntValidationReadClient`
- privacy contexts expose `EntPrivacyReadClient`
- both concrete types implement `EntReadClient`
- posture-agnostic helper parameters may remain `EntReadClient`
- posture-specific helper parameters should migrate to the matching concrete
  type

This entry must land with the implementation rather than being deferred until
a release is cut.

## Migration Plan

1. Change generated `EntReadClient` from a class to the shared repository
   interface.
2. Move the existing state, repository construction, and `EntReadRuntime`
   implementation into internal `EntReadClientImpl`.
3. Generate `EntValidationReadClient` and `EntPrivacyReadClient` as distinct
   classes delegating to the shared implementation.
4. Replace `asReadClientForInternalUse(context)` with the two semantic
   adapters.
5. Change generated validation and privacy context property types.
6. Update evaluator construction for all three validation operations and all
   four privacy operations.
7. Update generated KDoc and the numbered privacy and validation guides.
8. Amend the predecessor implemented-feature notes to point to this RFC and,
   once implemented, record that the one-concrete-client choice was
   superseded.
9. Add the required newest-first `## Unreleased` breaking-changes entry.
10. Regenerate examples and run the codegen and integration suites.

## Test Requirements

### Generated shape

- `EntReadClient` is an interface containing every generated read repository.
- `EntValidationReadClient` and `EntPrivacyReadClient` are distinct classes
  implementing that interface.
- The concrete classes are not type aliases.
- Both concrete constructors remain `internal` and `@EntktInternal`.
- One shared `${Entity}ReadRepo` type is generated per entity.
- One internal implementation owns repository initialization and
  `EntReadRuntime`; wrappers do not duplicate it.
- No generated public signature exposes `EntReadClientImpl`.

### Context typing

- Create, update, and delete validation contexts expose
  `EntValidationReadClient`.
- Load, create, update, and delete privacy contexts expose
  `EntPrivacyReadClient`.
- A helper accepting `EntReadClient` compiles with either context's client.
- A helper accepting `EntValidationReadClient` rejects
  `EntPrivacyReadClient`, and vice versa, in compile-fail tests.

### Construction boundary

- Validation evaluators use `asValidationReadClientForInternalUse()`.
- Privacy evaluators use `asPrivacyReadClientForInternalUse(privacy)`.
- Application code cannot call either adapter without opting into
  `EntktInternal`.
- The validation adapter always constructs a privacy-bypass-scoped delegate.
- The privacy adapter always freezes the supplied caller context.

### Behavior preservation

- Validation reads bypass LOAD privacy.
- Privacy-rule reads enforce the caller's LOAD privacy on both strict and
  visible terminal families.
- Raw terminals work in validation contexts.
- Raw terminals throw before `*OrError` conversion in privacy contexts.
- Both readers preserve transaction-scoped read-your-writes behavior.
- Both readers run read interceptors with the correct fixed context.
- Traversals, eager loads, indexed helpers, and by-id methods work through
  both clients.
- Existing privacy and validation integration suites pass after type-name
  updates.

### Documentation

- Privacy documentation names `EntPrivacyReadClient` and its viewer-scoped
  semantics.
- Validation documentation names `EntValidationReadClient` and its
  privacy-bypass semantics.
- The breaking-changes log contains the required `## Unreleased` entry.

## Decisions

- `EntReadClient` is the shared interface name.
- `EntValidationReadClient` and `EntPrivacyReadClient` are real concrete
  types.
- Both concrete types use one shared internal implementation.
- Per-entity repositories and query classes remain shared.
- Context property names remain `client`.
- Posture-specific internal adapters replace the arbitrary-context adapter.
- The runtime raw-terminal gate remains until a separate narrowed-query design
  replaces it.
