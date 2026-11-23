# RFC: Application-Defined Viewer

## Status

Partly implemented. Stage 1 of the [implementation plan](#implementation-plan)
is done: `ViewerContext` is the sealed pair `AsViewer` / `PrivacyBypass`, and
`Viewer.PrivacyBypass` is gone. Stages 2 and 3 are not implemented, so the
context is not generic yet and the runtime still owns `Viewer`. The Motivation
section describes the model before stage 1.

## Summary

Let each application declare its own viewer type and bind it once at code
generation. Privacy rules, hooks, and query interceptors then read typed facts
about the viewer, such as a user ID, roles, or a tenant, instead of the
runtime's `Viewer.User(id: Any)`.

`ViewerContext` stays a runtime type and becomes generic over the
application's viewer. Privacy bypass moves from being a kind of viewer to
being a kind of context, so the runtime keeps sole ownership of it. The
runtime's `Viewer` interface and its ID helpers are removed.

## Terms

This document uses these terms as follows:

- **Viewer**: who an operation runs as. After this RFC it is a value of a type
  the application defines.
- **Viewer context**: what a terminal is executed with, which is either a
  viewer or a privacy bypass. It stays a runtime type.
- **Application**: the code that uses the generated API. This document does
  not call it a "client", because `EntClient` and the rule client own that
  word.

## Motivation

The runtime owns the whole identity model today:

```kotlin
sealed interface Viewer {
    data object Anonymous : Viewer
    data class User(val id: Any) : Viewer
    data class PrivacyBypass(val reason: String) : Viewer
}

data class ViewerContext(val viewer: Viewer)
```

Every terminal takes a `ViewerContext`. The same instance reaches privacy
rules (`PrivacyRuleContext.viewerContext`), `beforeCreate` and `beforeUpdate`
hook states (`state.viewerContext`), and query interceptors
(`QueryContext.viewerContext`).

That model has three problems:

- **Application facts have nowhere to live.** A viewer can carry an ID and
  nothing else. Roles, a tenant, token scopes, or an impersonation marker must
  be looked up again inside each rule or fetched from outside the context. The
  guides already assume more than the type offers: the tenant-scoping
  interceptor in [Read-Path Interceptors](../../04-queries.md#read-path-interceptors)
  calls `ctx.viewerContext.viewer.requireTenantId()`, which no runtime type
  provides, and [Query-Time Visibility Predicates](query-time-visibility-predicates.md)
  reads `viewer.tenantId`.
- **The ID is untyped.** `viewer.id == post.authorId` compiles whatever the
  two types are, and is silently false when they differ. An allow rule written
  that way never allows; a `denyIf` written that way never denies. The privacy
  guide documents six helpers (`userIdOrNull()`, `longIdOrNull()`, and so on)
  and has to explain that they are exact type checks.
  [Audit Fields](../schema/audit-fields.md) needs extra schema configuration
  to choose a column type for `created_by` for the same reason.
- **The runtime does not use the identity.** Outside `Privacy.kt`, the runtime
  and the code generator inspect the viewer in one place, the bypass check in
  `PrivacyBatchEvaluator`, and construct one context themselves, the bypass
  context that validation rules read with. `Anonymous`, `User`, and the
  helpers exist only for application rule code.

The runtime needs one fact from a context: whether privacy is bypassed.
Everything else can belong to the application.

## Non-Goals

- Do not change privacy evaluation. Decisions, rule order, fail-closed denial,
  and the scope of bypass stay as they are.
- Do not let application code decide what counts as a privacy bypass.
- Do not reintroduce a viewer-bound client, an ambient or thread-local viewer,
  or a default context. Every terminal still takes its context explicitly.
- Do not give validation rules the viewer. Validation stays viewer-agnostic.
- Do not support more than one viewer type per generated package.
- Do not shape the API around Java callers. Java code can still use it, but
  passing a bypass context to a typed terminal produces an
  unchecked-conversion warning there.
- Do not add named rules or FK and graph predicates. Those stay in
  [Privacy Rule Primitives And Viewer Flavors](privacy-rule-primitives-and-viewer-flavors.md).

## Proposed API

### Declaring The Viewer

The application writes an ordinary class:

```kotlin
package example.auth

data class AppViewer(
    val userId: UUID?, // null when unauthenticated
    val roles: Set<Role>,
    val tenantId: UUID,
)
```

and names it once in the build:

```kotlin
entkt {
    packageName.set("example.ent")
    viewerType.set("example.auth.AppViewer")
}
```

`GenerateMain` gains the matching argument. Rules for the option:

- It is required. Code generation fails with a message naming the option when
  it is absent. There is no default viewer type, so every project has one
  identity model and no `Any`-typed ID survives.
- It names a top-level, non-generic class or interface. Nested and generic
  types are out of scope.
- Code generation runs on the schema classpath and uses only the name. The
  type can therefore live in the module that compiles the generated sources,
  or in anything that module depends on. A wrong name surfaces as an
  unresolved reference when the generated sources compile.

Guidance for the type itself:

- Make it an immutable value decided once at the application boundary. One
  context instance is shared across a whole operation, including nested rule
  reads.
- The viewer type is non-null. The application chooses how to represent an
  unauthenticated viewer: a nullable ID, a sealed hierarchy, or a dedicated
  object. The runtime no longer has an "anonymous" value.
- The runtime carries the viewer and never inspects, compares, or formats it,
  so a viewer may hold data that must not reach logs.

### ViewerContext

```kotlin
sealed interface ViewerContext<out Viewer : Any> {
    /** Run as [viewer]. Privacy rules decide what is allowed. */
    data class AsViewer<out Viewer : Any>(val viewer: Viewer) : ViewerContext<Viewer>

    /** Skip generated privacy checks. Created only by [privacyBypass_DANGEROUS]. */
    class PrivacyBypass internal constructor(val reason: String) : ViewerContext<Nothing>

    companion object {
        fun privacyBypass_DANGEROUS(reason: String): PrivacyBypass
    }
}
```

```kotlin
val viewerContext = ViewerContext.AsViewer(appViewer)
client.posts.query().all(viewerContext).getOrThrow()

val bypass = ViewerContext.privacyBypass_DANGEROUS("migration backfill")
client.users.create { email = "admin@example.com" }.save(bypass).getOrThrow()
```

- Generated terminals take `ViewerContext<AppViewer>`. A context built for an
  unrelated viewer type does not compile.
- `PrivacyBypass` is a `ViewerContext<Nothing>`, so one bypass value is
  accepted by every terminal of every generated client.
- Bypass keeps its current meaning. It skips generated LOAD, CREATE, UPDATE,
  and DELETE privacy only. Validation, hooks, query interceptors,
  transactions, and database constraints still apply.
- `privacyBypass_DANGEROUS` becomes the only way to build a bypass, so every
  bypass call site can be found by that name. Today `Viewer.PrivacyBypass(reason)`
  can also be constructed directly.
- One logical operation still shares the exact context instance it was given.

### Privacy Rules

Privacy rules are never evaluated for a bypass context: the evaluator allows
every item before any rule runs. The rule context therefore exposes the
non-bypass case directly:

```kotlin
class PrivacyRuleContext<out Viewer : Any, out Client : EntRuleClient>(
    val viewerContext: ViewerContext.AsViewer<Viewer>,
    val client: Client,
) {
    val viewer: Viewer
        get() = viewerContext.viewer
}
```

Rules read `context.viewer` and pass `context.viewerContext` to nested reads.
An ownership rule in the example application changes from:

```kotlin
val viewer = context.viewerContext.viewer as? Viewer.User
    ?: return PrivacyDecision.Continue
return if (viewer.id == item.authorId) PrivacyDecision.Allow
else PrivacyDecision.Continue
```

to:

```kotlin
return if (context.viewer.userId == item.authorId) {
    PrivacyDecision.Allow
} else {
    PrivacyDecision.Continue
}
```

`userId` is a `UUID?` and `authorId` is a `UUID`. Comparing `userId` with a
field of an unrelated type, such as a `Long`, is now a compile error.

The rule types gain the viewer as their first type parameter:

```kotlin
interface BatchPrivacyRule<in Viewer : Any, in Client : EntRuleClient, in Item>
fun interface PrivacyRule<in Viewer : Any, in Client : EntRuleClient, in Item>
fun interface ContextPrivacyRule<in Viewer : Any, in Client : EntRuleClient>
```

`allowIf`, `denyIf`, and `batchPrivacyRule` gain the same parameter.
`allowAll` becomes a `PrivacyRule<Any, EntRuleClient, Any?>` and still fits
every rule slot. Generated aliases bind the viewer beside the rule client, so
policies keep using the alias names:

```kotlin
typealias PostLoadPrivacyRule = PrivacyRule<AppViewer, ReadOnlyEntClient, Post>
```

Class-style rules name the bound context,
`PrivacyRuleContext<AppViewer, ReadOnlyEntClient>`, in their `run` signature.
Code generation does not emit an alias for that type. One can be added later
without breaking anything.

Rules are contravariant in the viewer. A rule written against an interface
that the viewer implements registers in any policy, which covers what
[viewer flavors](privacy-rule-primitives-and-viewer-flavors.md#viewer-flavors)
were proposed for:

```kotlin
interface HasRoles {
    val roles: Set<Role>
}

class AllowAdmins : ContextPrivacyRule<HasRoles, EntRuleClient> {
    override fun run(
        context: PrivacyRuleContext<HasRoles, EntRuleClient>,
    ): PrivacyDecision =
        if (Role.ADMIN in context.viewer.roles) {
            PrivacyDecision.Allow
        } else {
            PrivacyDecision.Continue
        }
}
```

Inside the runtime, the privacy evaluators take the terminal's context and the
rule client, decide bypass, and only then build the `PrivacyRuleContext`.
Today four callers build the rule context first and the evaluator inspects it.

### Hooks And Interceptors

Hooks and query interceptors run under bypass, so they receive the full
`ViewerContext<AppViewer>` and must state what bypass means for them:

```kotlin
posts(
    interceptor = QueryInterceptor { scope, context ->
        when (val viewerContext = context.viewerContext) {
            is ViewerContext.AsViewer ->
                scope.addPredicate(Post.tenantId eq viewerContext.viewer.tenantId)
            is ViewerContext.PrivacyBypass -> Unit // backfills read every tenant
        }
    },
    name = "tenant-scope",
)
```

- `QueryContext`, `QueryInterceptor`, and `GlobalQueryInterceptor` gain the
  viewer type parameter. Generated registration methods bind it.
- An interceptor that ignores the viewer, such as `ExcludeDeleted`, is
  declared for `Any` and registers on every generated client.
- Generated `{Entity}BeforeCreateState` and `{Entity}BeforeUpdateState` expose
  `viewerContext: ViewerContext<AppViewer>`.

### Validation

`ValidationRuleContext` keeps its single type parameter. `readViewerContext`
is typed `ViewerContext.PrivacyBypass`, which every rule-client terminal
accepts.

### Ent Explorer

`ent-explorer-core` becomes generic over the viewer. Its
`viewerContext { request -> ... }` provider defaults to
`ViewerContext(Viewer.Anonymous)` today. With no runtime-defined anonymous
viewer, the provider becomes required configuration.

### Removed API

- `Viewer`, `Viewer.Anonymous`, `Viewer.User`, and `Viewer.PrivacyBypass`
- `userOrNull()`, `userIdOrNull()`, `stringIdOrNull()`, `intIdOrNull()`,
  `longIdOrNull()`, and `uuidIdOrNull()` on both `Viewer` and `ViewerContext`

## Alternatives Considered

### The Application Defines The Whole Context

`ViewerContext` becomes an open runtime class that the application subclasses,
and terminals keep taking the base type. Almost no runtime signature changes.

Rejected because typed access is lost where it matters. Terminals must accept
the base type so that a bypass context can be passed. Rules, hooks, and
interceptors then reach application fields through a downcast, and a context
of the wrong subclass fails at run time inside a rule.

Bypass also has no good home in this design. As a runtime subclass, it stops
terminals from requiring the application's class. As a member that the
application's class implements, it lets application code decide what counts as
a bypass, and the runtime cannot construct the context that validation reads
need.

Choose this instead only if the width of the runtime change described under
[Impact](#impact) is judged too high for the benefit.

### Viewer Flavors

[Privacy Rule Primitives And Viewer Flavors](privacy-rule-primitives-and-viewer-flavors.md#viewer-flavors)
proposes `flavors: List<PrivacyFlavor>` on the runtime's context, read with
`hasFlavor<T>()`. It is the smallest change and is source compatible.

Rejected because a flavor is looked up by class at run time: a capability that
was never attached is a `null`, not a compile error. Flavors also keep
`id: Any` and leave open how they are stored. With an application viewer, a
capability is a property, and elevating one operation is
`ViewerContext.AsViewer(viewer.copy(canReadArchived = true))`.

### A Type Parameter On The Generated Client

Generate `EntClient<Viewer>` and let the application supply the type argument.
No build option is needed, but every generated repository, query, and rule
alias becomes generic, and application code repeats the viewer type wherever
it names one. An application has one viewer type, so binding it once at code
generation gives the same checking with concrete generated signatures.

## Reference Implementations

- **EntGo**: the viewer is application code carried in `context.Context`. The
  privacy guide's example reads it with `viewer.FromContext(ctx)`. Bypass is a
  framework call, `privacy.DecisionContext(ctx, privacy.Allow)`, separate from
  the viewer.
- **Ent.dev**: `Viewer` is an interface the application implements
  (`viewerID`, `viewer()`, `instanceKey()`, optional `context`). The framework
  ships `IDViewer` and `LoggedOutViewer` as ready-made implementations.
- **Expo Entity**: entity and privacy-policy types take a viewer-context type
  parameter bounded by the framework's `ViewerContext` class.
- **Ent Framework**: the framework owns the `VC` class, whose `principal` is a
  string. Application data is attached as `VCFlavor` objects and read by class
  (`vc.flavor(VCAdmin)`, `null` when absent). Bypass is `vc.toOmniDangerous()`.

EntGo and Ent.dev have the application define the viewer, and Expo Entity
parameterizes entities and policies over a viewer-context subtype. Of the
documentation pages consulted, only EntGo's and Ent Framework's describe a
bypass, and both keep it a framework mechanism rather than something
application viewer data declares. The flavors proposal follows Ent Framework.
This RFC moves to an application-defined viewer while keeping the
framework-owned bypass.

## Impact

This is a wide, mechanical change to public runtime signatures. The project
does not yet promise compatibility, so the cost is the volume of edits rather
than migration risk.

- **Runtime terminals.** The terminals that applications call are declared on
  runtime base classes: `EntityRepository`, `GeneratedIdRepository`,
  `EntityQueryBuilder`, `ForUpdateQuery`, and `PendingMutation`. Each gains
  the viewer type parameter, which the generated subclass binds.
- **Runtime plumbing.** Mutation inputs and operations, hook state converters,
  and the read path (`ReadQueryCompiler`, `ReadQueryExecutor`,
  `EntityGraphLoader`, `GraphStorage`, `LoadPrivacyDispatcher`) carry the
  context between a terminal and the callbacks, so they gain it too. As of
  this writing 31 runtime source files name `ViewerContext`, and 9 of them
  already carry the rule-client type parameter that the viewer would travel
  beside.
- **Code generation.** Generators name the context through one constant,
  `VIEWER_CONTEXT`, used by ten generator files. Rule aliases, hook state
  properties, interceptor registration, superclass type arguments, and the Ent
  Explorer adapters bind the configured type. No new generated logic is needed.
- **Ent Explorer.** `ent-explorer-core` becomes generic over the viewer and loses
  its anonymous default.
- **Applications and tests.** Every construction of a context and every
  class-style rule signature changes. The example application and the
  integration tests each define a viewer type.
- **Guides.** Ten documents name a `Viewer` kind or the context type: both
  README files and the getting started, queries, hooks, privacy, validation,
  privacy limitations, drivers, and Ent Explorer guides.

## Relationship To Other Proposals

- [Privacy Rule Primitives And Viewer Flavors](privacy-rule-primitives-and-viewer-flavors.md):
  this RFC would replace its "Viewer Flavors" and "Implemented Viewer Helpers"
  sections. Its named rules are independent. Its generated "FK points to
  viewer" helpers assume the runtime knows the viewer's ID, so they would take
  the viewer property that holds it.
- [Audit Fields](../schema/audit-fields.md): the actor value would come from an
  application-supplied accessor over the viewer. The accessor's type can be
  checked against the column's, which settles that proposal's storage-type
  question. Under bypass the actor stays `null`.
- [Query-Time Visibility Predicates](query-time-visibility-predicates.md),
  [Policy Test Helpers](policy-test-helpers.md), and
  [Generated Test Fixtures](../tooling/generated-test-fixtures.md) name `Viewer`
  or `Viewer.PrivacyBypass` in their sketches. They would use the application
  type and the two context cases.

## Feasibility Check

The declarations above were compiled as a standalone sketch (Kotlin 2.3.20,
JDK 17) together with a class-style rule, a rule shared through an interface,
`allowAll`, a typed terminal, and a tenant-scoping interceptor. Everything
compiles. At run time the nested read receives the same context instance, and
no rule runs under bypass. Comparing a `UUID?` viewer ID with a `Long` field
is rejected by the compiler.

Not checked: threading the type parameter through the real runtime classes.
That is stage 2 below.

## Implementation Plan

Each stage leaves the build passing and updates the guides and the
breaking-changes log for what it changes.

1. **Move bypass onto the context.** (Implemented.) Make `ViewerContext` the
   sealed pair `AsViewer` / `PrivacyBypass`, still without a type parameter
   and still holding the runtime's `Viewer`. Remove `Viewer.PrivacyBypass`,
   make the factory the only way to build a bypass, have the privacy
   evaluators decide bypass before building the rule context, and narrow
   `PrivacyRuleContext.viewerContext` and `readViewerContext` to their cases.
   Generated signatures do not change. This stage is useful on its own: bypass
   becomes a mode of an operation rather than a kind of viewer.
2. **Thread the viewer type through the runtime.** Add the type parameter to
   the context, rule types, evaluators, terminals, plumbing, interceptors, and
   `ent-explorer-core`. Generated code binds the runtime's existing `Viewer`
   type, so applications change only where they name a type that became
   generic. Behavior does not change. Until stage 3 removes it, the runtime's
   `Viewer` type shares its name with the new type parameter.
3. **Bind the application's type.** Add `viewerType` to the Gradle extension
   and `GenerateMain`, delete `Viewer` and its helpers, give the example
   application and the integration tests their own viewer types, and make the
   Ent Explorer context provider required. Revise the proposals listed above.

## Test Requirements

Before implementation, add tests for:

- a typed terminal accepts a `AsViewer` context and a bypass context, and a
  context for another viewer type does not compile
- comparing a typed viewer ID with a field of an unrelated type does not
  compile
- no privacy rule runs under bypass for LOAD, CREATE, UPDATE, or DELETE,
  including context-only and batch rules
- `PrivacyRuleContext.viewerContext` is the instance the terminal received,
  and nested rule reads can pass it on
- hooks and interceptors receive `PrivacyBypass` under bypass and `AsViewer`
  otherwise
- validation rules can read through the rule client with `readViewerContext`
- `allowAll`, and a rule declared against a supertype of the viewer, register
  in typed policy slots
- a viewer-independent interceptor such as `ExcludeDeleted` registers on any
  generated client
- a bypass context cannot be constructed except through
  `privacyBypass_DANGEROUS`, and a blank reason is still rejected
- code generation fails with a clear message when `viewerType` is missing or
  is not a top-level class name
- Ent Explorer rejects configuration that has no context provider
