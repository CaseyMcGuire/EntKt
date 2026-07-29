# RFC: Read-Only Privacy Rule Client

## Status

Possible future feature. This is not implemented.

Drafted 2026-07-29 as the follow-up the implemented
[read-only-validation-client](../../implemented-features/privacy-validation/read-only-validation-client.md)
RFC deferred in its migration step 7 ("Keep privacy contexts using the
full client unless a separate RFC changes them"). That RFC built the
machinery this one reuses: the `@EntktInternal` `EntReadRuntime`
contract, per-entity read surfaces, and read repos whose queries and
index stages run identically under any host that implements the
contract.

## Summary

Replace the full `EntClient` exposed in generated privacy contexts
with a read-only, **viewer-scoped** client, making "privacy rules do
not write" a compile-time guarantee — the same treatment validation
contexts received.

```kotlin
data class PostLoadPrivacyContext(
    val privacy: PrivacyContext,
    val client: EntReadClient,   // was EntClient
    val entity: Post,
)
```

## Motivation

Privacy rules answer "may this viewer perform this operation?" They
read the graph to decide (ownership walks, parent-visibility checks);
they must never mutate it. Today the contexts hand rules a full
`EntClient` — the same gap validators had before the validation RFC:
documented read-only, enforced nowhere. A rule that writes runs inside
another operation's authorization phase, with the caller's frozen
privacy context, recursively triggering hooks, privacy, and
validation.

The validation client cannot simply be reused here. It bakes in
`Viewer.PrivacyBypass("validation read")` — correct for invariant
checks, which must see all rows, and exactly wrong for authorization:
a privacy rule's graph reads must run **as the caller's viewer**, or
rows invisible to that viewer would leak into authorization decisions
and flip their outcomes. Read-only-ness and privacy posture are
independent axes; the validation RFC shipped one point (read-only +
bypass), this RFC adds the other (read-only + viewer-scoped).

## Goals

- Prevent privacy rules from calling generated create, update, delete,
  edge-mutation, transaction (`withTransaction` /
  `withTransactionOrError`), re-scoping (`withPrivacyContext` /
  `bypassPrivacy_DANGEROUS`), or client-configuration
  (`transactionRequirement`, `privacyContextProvider`, the update
  defaults) APIs.
- Preserve read behavior exactly: rules read through the same frozen
  caller `PrivacyContext` they get today
  (`withFixedPrivacyContextForInternalUse(privacy)` semantics), with
  the same LOAD-privacy evaluation, read interceptors, and
  transaction-scoped driver.
- Reuse `EntReadRuntime` and the existing read-repo machinery — no new
  query plumbing.

## Non-Goals

- No general application-facing read facade. The adapter and the read
  client's constructors are **unsupported framework-internal API**,
  guarded `@EntktInternal internal`. That is a deliberate-use gate,
  not a security boundary: generated code compiles into the consuming
  application's module, so Kotlin visibility alone cannot separate
  evaluator code from application code — the validation RFC documents
  exactly this — and an application that writes
  `@OptIn(EntktInternal::class)` can still mint a reader, explicitly
  and greppably, owning the consequences. A public, supported
  `client.readOnly()` is a separate RFC.
- No change to hooks contexts (still the full client; carried open
  question).
- No change to which viewer privacy rules evaluate as. Rules read as
  the frozen caller context, exactly as today.

## Design

### The fork: one read client or two

The validation RFC named its type `EntValidationReadClient` because
the baked-in bypass posture "is only correct for invariant checks."
Extending to privacy forces the question of whether posture belongs in
the type name at all.

**Option A — one generated `EntReadClient`, posture as instance state
(recommended).** Rename `EntValidationReadClient` →
`EntReadClient` and `${Entity}ValidationReadRepo` →
`${Entity}ReadRepo`. One adapter on `EntClient`:

```kotlin
@EntktInternal
internal fun asReadClientForInternalUse(context: PrivacyContext): EntReadClient
```

The `ForInternalUse` suffix follows the existing
`withFixedPrivacyContextForInternalUse` convention, and the
`@EntktInternal` marker is what actually gates same-module application
code (plain `internal` gates nothing there — see Non-Goals). The read
client's and read repos' constructors carry the same
`@EntktInternal internal` guard.

Validation evaluators call
`client.asReadClientForInternalUse(PrivacyContext(Viewer.PrivacyBypass("validation read")))`;
privacy evaluators call `client.asReadClientForInternalUse(privacy)`.
The public `asValidationReadClient()` is removed (not deprecated) — it
only ever had generated callers, and an unguarded public method that
mints bypass-scoped readers sits awkwardly next to the deliberately
loud `bypassPrivacy_DANGEROUS`.

This matches the framework's existing model: `EntClient` itself does
not encode its posture in its type — a bypass-scoped clone from
`withPrivacyContext` has the same type as a viewer-scoped one; the
context is instance state. The validation RFC's naming argument
conflated capability (read-only — a type property) with posture (a
context property carried by the instance). One read-only client type,
same contract for both rule kinds, is the consistent shape.

**Option B — a privacy-named twin.** Keep `EntValidationReadClient`
untouched and mint `EntPrivacyReadClient` + per-entity repos
alongside. No churn on the just-shipped validation surface and every
type name states its posture — but the twin's repos are byte-identical
to the validation ones modulo the class names, a standing drift
hazard, and validators and privacy rules end up with two names for one
contract.

Option A is recommended: posture-in-the-name does not survive contact
with the second posture, and the repos are already generated from
shared builders precisely so there is one read surface.

### The opt-in marker's message must be generalized first

`@EntktInternal`'s `@RequiresOptIn` message is edge-predicate-specific
today: it tells the developer that "direct fabrication can bypass
edge-walker soundness" and to "construct edge predicates only via the
generated `EdgeRef.has(...)` / `EdgeRef.exists()` surface". That text
is already stale for the marker's newer duties (the interceptor
registry, the `EntReadRuntime` contract, the traversal seeders) and
would be actively misleading on a read-client construction site —
opting in there has nothing to do with edge predicates.

This RFC therefore requires, before the marker is reused on the
adapter: generalize the `@RequiresOptIn` message to the actual
contract ("framework-internal API used by generated code; unsupported
for application use; opt in explicitly and own the invariants
documented on `EntktInternal`") and move the per-surface soundness
notes — including the edge-walker one, which stays valuable — into the
marker's KDoc inventory.

Alternative considered: a dedicated second marker for read-client
construction. Rejected — it fragments the single opt-in story into
parallel markers guarding the same property ("generated-code-only
API"), forces double `@file:OptIn` headers in generated files that
touch both surfaces, and splits the one thing worth grepping for.

### Adapter semantics

`asReadClientForInternalUse(context)` copies the same read-relevant
state the validation adapter copies today — same driver instance (a
transaction-scoped client yields a transaction-scoped reader), the
passed context fixed for the client's lifetime, the shared interceptor
registry, `visibleOverfetchLimit`, and the repos as per-entity read
surfaces. Privacy evaluators pass the `privacy` parameter they already
receive, so the frozen-context behavior of
`withFixedPrivacyContextForInternalUse(privacy)` is preserved
verbatim; only the write surface disappears.

With privacy evaluators off it, `withFixedPrivacyContextForInternalUse`
has no remaining callers and is removed from the generated client (its
former role as the state inventory is historical; the adapter is the
implementation now).

### What privacy rules lose

Beyond writes: `withTransaction`, `withTransactionOrError`,
`withPrivacyContext`, `bypassPrivacy_DANGEROUS`, and the mutable
client configuration same-module code can reach today
(`transactionRequirement`, `privacyContextProvider`, the update
defaults). Losing
`withPrivacyContext` is deliberate — a rule re-scoping its reads to a
different viewer is doing cross-viewer authorization, which should be
impossible to write by accident; if a real use case appears it belongs
in the open questions, not the default surface.

### Behavior preservation

Rule reads are byte-for-byte the same query machinery under the same
frozen context: LOAD privacy still evaluates per row (no bypass
short-circuit for non-bypass viewers), read interceptors still run and
observe the caller's viewer, recursion hazards (a load rule loading
its own entity type) are unchanged. The only observable change is that
rule bodies which currently mutate — or touch the removed members —
stop compiling. That is the point.

## Negative compile-test strategy

Mirror `ValidationReadClientCompileTest` with a privacy-rule snippet
(`CarLoadPrivacyRule { ctx -> ... }` over the shared Car/User
fixtures): negatives for `create`, `update`, `deleteByIdOrError`,
`deleteMany`, `withTransaction`, `withTransactionOrError`,
`withPrivacyContext`, and `bypassPrivacy_DANGEROUS` must fail with
unresolved references, plus assignment probes for every configuration
member named in Goals — `ctx.client.transactionRequirement = ...`,
`ctx.client.privacyContextProvider = ...`,
`ctx.client.defaultUpdateConsistency = ...`, and
`ctx.client.defaultRelationshipLocking = ...` (reachable same-module
`internal var`s on the full client today, absent members on the read
client, so the probes fail the same unresolved-reference way). The
positive twin compiles a rule using `query { }` + terminal, the byId
family, and an index-helper stage.

The adapter's opt-in gate gets its own pair, in an application-code
snippet holding an `EntClient` (not a rule — rules never see the full
client after this RFC):

- `client.asReadClientForInternalUse(...)` **without** opt-in must
  fail with the opt-in diagnostic — assert the message names
  `EntktInternal`, not an unresolved reference; this is a different
  failure class than the probes above, and the assertion must not
  reuse the unresolved-reference matcher.
- The same call under `@OptIn(EntktInternal::class)` must compile.

Because kctfork compiles the snippet into the same module as the
generated output, `internal` is satisfied in both cases — the pair
proves the marker is the gate, which is precisely the boundary the
Non-Goals section claims and nothing more.

## Migration Plan

1. Generalize `EntktInternal`'s `@RequiresOptIn` message and KDoc from
   the edge-predicate-specific text to the framework-internal contract
   (per-surface soundness notes move into the KDoc inventory). No
   call-site changes — the marker's identity is unchanged.
2. (Option A) Rename the generated read client and repos
   (`EntReadClient`, `${Entity}ReadRepo`); update the validation
   contexts, evaluators, compile/integration tests, and docs that name
   the old types.
3. Replace `asValidationReadClient()` with
   `@EntktInternal internal asReadClientForInternalUse(context)`;
   validation evaluators inline the bypass context at the call site;
   the read client's and read repos' constructors gain the same
   `@EntktInternal` guard.
4. Change generated privacy contexts (`Load` / `Create` / `Update` /
   `Delete`) from `EntClient` to the read client; privacy evaluators
   build it via `asReadClientForInternalUse(privacy)`.
5. Remove `withFixedPrivacyContextForInternalUse` (dead after step 4).
6. Add the compile-fail/compile-pass tests (including the opt-in gate
   pair); update codegen test pins (`PrivacyGeneratorTest`'s
   context-type assertion, `ClientGeneratorTest`).
7. Update `docs/06-privacy.md` (context shapes, evaluator wiring) and
   the validation docs' type names.

## Open Questions

- Should hooks also get a read-only client by default, with an escape
  hatch for side-effecting hooks? (Carried from the validation RFC.)
- Is there a legitimate cross-viewer read inside a privacy rule that
  justifies a scoped escape hatch, or is that always a policy-design
  smell?
- A public application-facing `client.readOnly()` facade — separate
  RFC; `EntReadClient` under Option A is the obvious carrier.

## Test Requirements

Before implementation, add tests for:

- privacy contexts expose the read client; rule code can call query,
  terminal, index-helper, and by-id methods
- rule reads are viewer-scoped, asserted on **both** denial surfaces —
  the terminals are deliberately distinct: a rule reading a
  viewer-invisible row via `byIdOrNull` observes the thrown
  `PrivacyDeniedException`, and the same read via `visibleByIdOrNull`
  collapses to `null`. One test per path, so an implementation cannot
  accidentally pin only the filtering path. This pair is the semantic
  that distinguishes this client from the validation one.
- rule reads inside a transaction use the transaction-scoped driver
- read interceptors run for rule queries and observe the caller's
  viewer, not a bypass
- rule code cannot call write, transaction, re-scoping, or
  configuration members (compile-fail harness above, including the
  `defaultUpdateConsistency` / `defaultRelationshipLocking` probes)
- `asReadClientForInternalUse` is opt-in-gated: uncompilable without
  `@OptIn(EntktInternal::class)`, compilable with it, asserted on the
  opt-in diagnostic rather than an unresolved reference
- existing privacy integration suites pass unchanged
